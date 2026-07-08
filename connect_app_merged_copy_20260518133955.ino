/*
 * AI Athletes Arena — TinyML Wearable Fitness System
 * Arduino Firmware — nRF52840 (Seeed XIAO Sense)
 * Author : Fares Elamin
 *
 * Runs a continuous 50 Hz IMU sampling loop that:
 *   1. Streams raw six-axis IMU data over BLE (IMU service 180C)
 *   2. Runs the Edge Impulse TinyML classifier every 1000 ms
 *   3. Detects the active exercise and monitors for idle
 *   4. Counts repetitions using a dual-phase architecture:
 *        PHASE_DUAL   — ML + gyro peak must agree within 1500 ms (reps 1-2)
 *        PHASE_LOCKIN — pure slope-based peak detection (rep 3 onwards)
 *   5. Broadcasts rep count, exercise name, and speed feedback
 *      over BLE (fitness service 180A)
 */

#include <peak-detection-v1_inferencing.h>
#include <LSM6DS3.h>
#include <Wire.h>
#include <ArduinoBLE.h>

/* ── unit conversion ── */
#define CONVERT_G_TO_MS2        9.80665f

/* ══════════════════════════════════════════════════════════════
   TUNABLE PARAMETERS
   ══════════════════════════════════════════════════════════════ */

/* Classifier confidence thresholds */
#define CONF_START              0.80f   // Minimum confidence to accept an exercise label
#define CONF_IDLE               0.97f   // Stricter threshold for idle — prevents premature set end
#define CONF_SWITCH             0.70f   // Minimum confidence to consider an exercise switch

/* Idle confirmation — requires N consecutive idle predictions before ending a set */
#define IDLE_CONFIRM_COUNT      3

/* PHASE_DUAL window — ML and gyro peak must both fire within this window (ms) */
#define DUAL_WINDOW_MS          1500

/* Number of dual-confirmed reps before transitioning to PHASE_LOCKIN */
#define LOCKIN_REPS             2

/* PHASE_LOCKIN — detected peak must be at least this fraction of ref_peak */
#define PEAK_RATIO              0.55f

/* Minimum time between two counted reps — prevents double counting (ms) */
#define MIN_REP_GAP_MS          1000

/* Exercise switch — requires N consecutive predictions of the new exercise */
#define SWITCH_CONFIRM_COUNT    3

/* Slope detector thresholds — used to detect rising and falling edges */
#define SLOPE_RISE_THRESH       1.5f
#define SLOPE_FALL_THRESH      -1.5f

/* Rep speed classification thresholds (ms) */
#define SPEED_FAST_MS           1100   // Below this → fast rep
#define SPEED_SLOW_MS           2000   // Above this → slow rep

/* Slope buffer — 5 samples at 50 Hz = 100 ms lookback window */
#define SLOPE_BUF               5

/* Disable Edge Impulse debug output */
static const bool debug_nn = false;

/* ══════════════════════════════════════════════════════════════
   HARDWARE
   ══════════════════════════════════════════════════════════════ */

LSM6DS3 imu(I2C_MODE, 0x6A);   // STMicroelectronics LSM6DS3 six-axis IMU via I2C

/* ══════════════════════════════════════════════════════════════
   BLE SERVICES AND CHARACTERISTICS
   ══════════════════════════════════════════════════════════════ */

/* IMU streaming service — raw six-axis data at 50 Hz */
BLEService imuService("180C");
BLECharacteristic imuDataCharacteristic("2A56", BLENotify | BLERead, 60);

/* Fitness service — exercise state, rep count, speed, and debug log */
BLEService fitnessService("180A");
BLEIntCharacteristic    repCharacteristic    ("2A57", BLERead | BLENotify);
BLEStringCharacteristic exerciseCharacteristic("2A58", BLERead | BLENotify, 20);
BLEStringCharacteristic speedCharacteristic  ("2A59", BLERead | BLENotify, 20);
BLEStringCharacteristic logCharacteristic    ("2A60", BLERead | BLENotify, 100);

/* Send a log message over both Serial and BLE */
void bleLog(String msg) {
    Serial.println(msg);
    logCharacteristic.writeValue(msg);
}

/* ══════════════════════════════════════════════════════════════
   PER-EXERCISE GYROSCOPE FLOOR THRESHOLDS
   Derived from the 75th percentile of rest-period gyro values
   measured during data collection. Suppresses low-amplitude
   peaks that occur during between-rep rest periods.
   ══════════════════════════════════════════════════════════════ */
float getGyroFloor(String exercise) {
    if (exercise == "Bench press")      return 9.0f;
    if (exercise == "Bicep curls")      return 84.0f;
    if (exercise == "Front raises")     return 54.0f;
    if (exercise == "Lateral raises")   return 66.0f;
    if (exercise == "Overhead triceps") return 35.0f;
    return 0.0f;
}

/* ══════════════════════════════════════════════════════════════
   LABEL HELPERS
   ══════════════════════════════════════════════════════════════ */

/* Returns true if the classifier label corresponds to an exercise class */
bool isExerciseLabel(String label) {
    return (label == "Bench press start"      ||
            label == "Bicep curls start"      ||
            label == "Front raises start"     ||
            label == "Lateral raises start"   ||
            label == "Overhead triceps start");
}

/* Strips the "_start" suffix to produce the display exercise name */
String getExerciseName(String label) {
    if (label == "Bench press start")      return "Bench press";
    if (label == "Bicep curls start")      return "Bicep curls";
    if (label == "Front raises start")     return "Front raises";
    if (label == "Lateral raises start")   return "Lateral raises";
    if (label == "Overhead triceps start") return "Overhead triceps";
    return "";
}

/* ══════════════════════════════════════════════════════════════
   SYSTEM STATE
   ══════════════════════════════════════════════════════════════ */

enum Phase { PHASE_DUAL, PHASE_LOCKIN };

/* Exercise and rep tracking */
Phase         phase           = PHASE_DUAL;
String        active_exercise = "";
int           rep_count       = 0;
unsigned long last_rep_time   = 0;

/* Idle confirmation counter */
int           idle_confirm    = 0;

/* Circular slope buffer — stores the last SLOPE_BUF gyro magnitude samples */
float         gyro_buf[SLOPE_BUF];
int           gyro_buf_idx  = 0;
bool          gyro_buf_full = false;

/* Peak tracking — used by both phases */
bool          peak_rising  = false;   // True while gyro is on a rising slope
float         peak_max     = 0;       // Maximum gyro magnitude seen during current rise
float         cur_valley   = 0;       // Minimum gyro magnitude seen since last peak

/* Adaptive reference peak — average of peaks from PHASE_DUAL reps */
float         ref_peak     = 0;
float         peak_sum     = 0;
int           peak_cnt     = 0;

/* PHASE_DUAL confirmation state */
bool          ml_fired        = false;   // True when ML has fired and is waiting for a peak
unsigned long ml_fired_time   = 0;
bool          peak_fired      = false;   // True when a peak has fired and is waiting for ML
unsigned long peak_fired_time = 0;
float         peak_fired_val  = 0;
float         peak_fired_valley = 0;

/* Exercise switch state */
String        pending_switch = "";
int           switch_confirm = 0;

/* Latest IMU readings — stored globally for logging in countRep() */
float cur_ax = 0, cur_ay = 0, cur_az = 0;
float cur_gx = 0, cur_gy = 0, cur_gz = 0;
float cur_gyro_mag  = 0;
float cur_accel_mag = 0;

/* ══════════════════════════════════════════════════════════════
   SLOPE BUFFER
   Implements a circular buffer of SLOPE_BUF gyro magnitude samples.
   Slope = newest sample − oldest sample over the 100 ms window.
   ══════════════════════════════════════════════════════════════ */
void pushBuf(float val) {
    gyro_buf[gyro_buf_idx] = val;
    gyro_buf_idx = (gyro_buf_idx + 1) % SLOPE_BUF;
    if (gyro_buf_idx == 0) gyro_buf_full = true;
}

float getSlope() {
    if (!gyro_buf_full) return 0;
    float newest = gyro_buf[(gyro_buf_idx - 1 + SLOPE_BUF) % SLOPE_BUF];
    float oldest = gyro_buf[gyro_buf_idx];
    return newest - oldest;
}

/* ══════════════════════════════════════════════════════════════
   RESET HELPERS
   ══════════════════════════════════════════════════════════════ */

/* Clear the dual-confirm firing state for both ML and peak */
void resetDual() {
    ml_fired          = false;
    ml_fired_time     = 0;
    peak_fired        = false;
    peak_fired_time   = 0;
    peak_fired_val    = 0;
    peak_fired_valley = 0;
}

/* Clear peak tracking and slope buffer */
void resetPeakTracking() {
    peak_rising   = false;
    peak_max      = 0;
    cur_valley    = 0;
    gyro_buf_idx  = 0;
    gyro_buf_full = false;
    for (int i = 0; i < SLOPE_BUF; i++) gyro_buf[i] = 0;
}

/* Full system reset — called when idle is confirmed at end of set */
void resetSet() {
    active_exercise = "";
    rep_count       = 0;
    last_rep_time   = 0;
    phase           = PHASE_DUAL;
    ref_peak        = 0;
    peak_sum        = 0;
    peak_cnt        = 0;
    pending_switch  = "";
    switch_confirm  = 0;
    cur_valley      = 0;
    idle_confirm    = 0;
    resetDual();
    resetPeakTracking();
    exerciseCharacteristic.writeValue("idle");
    speedCharacteristic.writeValue("normal");
}

/* ══════════════════════════════════════════════════════════════
   COUNT REP
   Called whenever a valid repetition is confirmed by either
   PHASE_DUAL (ML + peak agreement) or PHASE_LOCKIN (peak alone).
   Updates rep count, speed feedback, BLE characteristics, and
   transitions to PHASE_LOCKIN after LOCKIN_REPS confirmed reps.
   ══════════════════════════════════════════════════════════════ */
void countRep(float peak_val, float valley_val, unsigned long now, String source) {
    rep_count++;

    /* Compute inter-rep duration for speed classification */
    unsigned long dur = (last_rep_time > 0) ? (now - last_rep_time) : 0;
    last_rep_time = now;

    String speed = "normal";
    if (dur > 0 && dur < SPEED_FAST_MS) speed = "fast";
    else if (dur > SPEED_SLOW_MS)       speed = "slow";

    /* Log rep details over BLE */
    bleLog("REP #" + String(rep_count) +
           " [" + source + "]" +
           " | " + active_exercise +
           " | peak=" + String(peak_val, 1) +
           " | valley=" + String(valley_val, 1) +
           " | " + speed);

    bleLog("  gx=" + String(cur_gx, 1) +
           " gy=" + String(cur_gy, 1) +
           " gz=" + String(cur_gz, 1) +
           " |gyro=" + String(cur_gyro_mag, 1));

    bleLog("  ax=" + String(cur_ax, 1) +
           " ay=" + String(cur_ay, 1) +
           " az=" + String(cur_az, 1) +
           " |acc=" + String(cur_accel_mag, 1));

    /* Notify Android app */
    repCharacteristic.writeValue(rep_count);
    exerciseCharacteristic.writeValue(active_exercise);
    speedCharacteristic.writeValue(speed);

    /* Accumulate peak amplitude for adaptive ref_peak (up to first 3 reps) */
    if (peak_cnt < 3) {
        peak_sum += peak_val;
        peak_cnt++;
    }

    cur_valley   = cur_gyro_mag;
    idle_confirm = 0;

    resetDual();

    /* Transition to PHASE_LOCKIN after LOCKIN_REPS dual-confirmed reps.
       ref_peak is set to the average peak amplitude from PHASE_DUAL. */
    if (phase == PHASE_DUAL && rep_count >= LOCKIN_REPS) {
        float saved_ref = (peak_cnt > 0) ? (peak_sum / peak_cnt) : 0;
        phase    = PHASE_LOCKIN;
        ref_peak = saved_ref;
        resetPeakTracking();
        bleLog("=== LOCKED IN | ref_peak=" + String(ref_peak, 1) +
               " | min=" + String(ref_peak * PEAK_RATIO, 1) +
               " | peaks_used=" + String(peak_cnt) + " ===");
    }
}

/* ══════════════════════════════════════════════════════════════
   SETUP
   ══════════════════════════════════════════════════════════════ */
void setup() {
    Serial.begin(115200);
    delay(2000);

    if (imu.begin() != 0) { Serial.println("IMU failed"); while (1); }
    if (!BLE.begin())      { Serial.println("BLE failed"); while (1); }

    BLE.setLocalName("TinyML-Watch");

    /* Register characteristics with their respective services */
    imuService.addCharacteristic(imuDataCharacteristic);
    fitnessService.addCharacteristic(repCharacteristic);
    fitnessService.addCharacteristic(exerciseCharacteristic);
    fitnessService.addCharacteristic(speedCharacteristic);
    fitnessService.addCharacteristic(logCharacteristic);

    BLE.addService(imuService);
    BLE.addService(fitnessService);
    BLE.setAdvertisedService(imuService);

    /* Initialise BLE characteristic values */
    repCharacteristic.writeValue(0);
    exerciseCharacteristic.writeValue("idle");
    speedCharacteristic.writeValue("normal");
    logCharacteristic.writeValue("=== WATCH READY ===");

    BLE.advertise();
    Serial.println("BLE ready");
}

/* ══════════════════════════════════════════════════════════════
   MAIN LOOP
   Runs continuously. Waits for a BLE central to connect,
   then enters the inner loop which:
     - Samples the IMU at 50 Hz
     - Streams raw data over BLE
     - Fills the Edge Impulse classifier buffer
     - Runs slope-based peak detection
     - Runs the TinyML classifier every 1000 ms
   ══════════════════════════════════════════════════════════════ */
void loop() {
    BLE.poll();
    BLEDevice central = BLE.central();

    if (central && central.connected()) {
        Serial.print("Connected: ");
        Serial.println(central.address());
        bleLog("=== CONNECTED ===");

        float buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE];
        size_t bufIdx        = 0;
        unsigned long lastSample = 0;
        unsigned long lastDbg    = 0;

        while (central.connected()) {
            BLE.poll();
            unsigned long now = millis();

            /* ── IMU sampling at 50 Hz (every 20 ms) ── */
            if (now - lastSample >= 20) {
                lastSample = now;

                /* Read all six IMU axes */
                cur_ax = imu.readFloatAccelX() * CONVERT_G_TO_MS2;
                cur_ay = imu.readFloatAccelY() * CONVERT_G_TO_MS2;
                cur_az = imu.readFloatAccelZ() * CONVERT_G_TO_MS2;
                cur_gx = imu.readFloatGyroX();
                cur_gy = imu.readFloatGyroY();
                cur_gz = imu.readFloatGyroZ();

                /* Compute vector magnitudes */
                cur_gyro_mag  = sqrt(cur_gx*cur_gx + cur_gy*cur_gy + cur_gz*cur_gz);
                cur_accel_mag = sqrt(cur_ax*cur_ax + cur_ay*cur_ay + cur_az*cur_az);

                /* Update slope buffer with latest gyro magnitude */
                pushBuf(cur_gyro_mag);

                /* Track valley (minimum gyro) when not on a rising slope */
                if (!peak_rising) {
                    if (cur_gyro_mag < cur_valley || cur_valley == 0)
                        cur_valley = cur_gyro_mag;
                }

                /* Stream raw IMU data to Android app over BLE */
                char sb[60];
                sprintf(sb, "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
                        cur_ax, cur_ay, cur_az, cur_gx, cur_gy, cur_gz);
                imuDataCharacteristic.writeValue((uint8_t*)sb, strlen(sb));

                /* Append sample to Edge Impulse classifier buffer */
                if (bufIdx < EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE) {
                    buffer[bufIdx + 0] = cur_ax;
                    buffer[bufIdx + 1] = cur_ay;
                    buffer[bufIdx + 2] = cur_az;
                    buffer[bufIdx + 3] = cur_gx;
                    buffer[bufIdx + 4] = cur_gy;
                    buffer[bufIdx + 5] = cur_gz;
                    bufIdx += EI_CLASSIFIER_RAW_SAMPLES_PER_FRAME;
                }

                /* Skip peak detection if no exercise is active */
                if (active_exercise == "") goto skip_peak;

                {
                    float slope     = getSlope();
                    float floor_val = getGyroFloor(active_exercise);

                    /* ════════════════════════════════════════════════
                       PHASE_DUAL — reps 1 and 2
                       Both ML classifier and gyro peak must agree
                       within DUAL_WINDOW_MS for a rep to be counted.
                       ════════════════════════════════════════════════ */
                    if (phase == PHASE_DUAL) {

                        /* Rising edge detected */
                        if (slope > SLOPE_RISE_THRESH) {
                            peak_rising = true;
                            if (cur_gyro_mag > peak_max) peak_max = cur_gyro_mag;

                        /* Falling edge detected — evaluate peak */
                        } else if (slope < SLOPE_FALL_THRESH && peak_rising) {

                            float detected_peak   = peak_max;
                            float detected_valley = cur_valley;
                            peak_max    = 0;
                            peak_rising = false;
                            cur_valley  = cur_gyro_mag;

                            if (detected_peak < floor_val) {
                                /* Peak below gyro floor — suppress */
                                bleLog("PEAK FLOOR | peak=" + String(detected_peak, 1) +
                                       " | floor=" + String(floor_val, 1));

                            } else if (now - last_rep_time < MIN_REP_GAP_MS) {
                                /* Peak too soon after last rep — suppress */
                                bleLog("PEAK TOO FAST | " +
                                       String(now - last_rep_time) + "ms");

                            } else if (ml_fired &&
                                       now - ml_fired_time <= DUAL_WINDOW_MS) {
                                /* ML already fired — dual confirm achieved */
                                bleLog("DUAL OK — peak confirms ML");
                                countRep(detected_peak, detected_valley, now, "DUAL");

                            } else {
                                /* ML not yet fired — store peak and wait */
                                peak_fired        = true;
                                peak_fired_time   = now;
                                peak_fired_val    = detected_peak;
                                peak_fired_valley = detected_valley;
                                bleLog("PEAK waiting for ML | peak=" +
                                       String(detected_peak, 1));
                            }
                        }

                        /* Expire stale peak if ML never arrived */
                        if (peak_fired && now - peak_fired_time > DUAL_WINDOW_MS) {
                            bleLog("PEAK EXPIRED — no ML arrived");
                            peak_fired = false;
                        }

                        /* Expire stale ML if peak never arrived */
                        if (ml_fired && now - ml_fired_time > DUAL_WINDOW_MS) {
                            bleLog("ML EXPIRED — no peak arrived");
                            ml_fired = false;
                        }
                    }

                    /* ════════════════════════════════════════════════
                       PHASE_LOCKIN — rep 3 onwards
                       Pure slope-based peak detection.
                       Peak must exceed ref_peak * PEAK_RATIO.
                       ML classifier runs only for idle detection.
                       ════════════════════════════════════════════════ */
                    else if (phase == PHASE_LOCKIN) {

                        /* Rising edge */
                        if (slope > SLOPE_RISE_THRESH) {
                            peak_rising = true;
                            if (cur_gyro_mag > peak_max) peak_max = cur_gyro_mag;

                        /* Falling edge — evaluate peak against relative threshold */
                        } else if (slope < SLOPE_FALL_THRESH && peak_rising) {

                            float detected_peak   = peak_max;
                            float detected_valley = cur_valley;
                            peak_max    = 0;
                            peak_rising = false;
                            cur_valley  = cur_gyro_mag;

                            float min_peak = ref_peak * PEAK_RATIO;

                            if (detected_peak < min_peak) {
                                /* Peak too small relative to reference — ignore */
                                bleLog("LCK IGNORED | peak=" +
                                       String(detected_peak, 1) +
                                       " | min=" + String(min_peak, 1));

                            } else if (now - last_rep_time < MIN_REP_GAP_MS) {
                                /* Too soon after last rep */
                                bleLog("LCK TOO FAST | " +
                                       String(now - last_rep_time) + "ms");

                            } else {
                                countRep(detected_peak, detected_valley,
                                         now, "LCK");
                            }
                        }
                    }
                }

                skip_peak:

                /* Periodic debug log every 500 ms */
                if (now - lastDbg >= 500) {
                    lastDbg = now;
                    bleLog("[DBG] ph=" +
                           String(phase == PHASE_DUAL ? "DUAL" : "LCK") +
                           " | gyro=" + String(cur_gyro_mag, 1) +
                           " | slope=" + String(getSlope(), 1) +
                           " | ml=" + String(ml_fired ? "T" : "F") +
                           " | pk=" + String(peak_fired ? "T" : "F") +
                           " | reps=" + String(rep_count) +
                           " | ref=" + String(ref_peak, 1) +
                           " | idle_c=" + String(idle_confirm) +
                           " | ex=" + active_exercise);
                }
            }

            /* ════════════════════════════════════════════════════
               EDGE IMPULSE CLASSIFIER
               Runs every time the 1000 ms buffer is full (50 samples).
               Identifies the active exercise and monitors for idle.
               ════════════════════════════════════════════════════ */
            if (bufIdx >= EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE) {
                bufIdx = 0;

                signal_t signal;
                if (numpy::signal_from_buffer(buffer,
                        EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE,
                        &signal) != 0) continue;

                ei_impulse_result_t result = {0};
                if (run_classifier(&signal, &result, debug_nn) != EI_IMPULSE_OK)
                    continue;

                /* Find the highest-confidence class */
                float  max_val = 0;
                int    max_idx = -1;
                for (size_t i = 0; i < EI_CLASSIFIER_LABEL_COUNT; i++) {
                    if (result.classification[i].value > max_val) {
                        max_val = result.classification[i].value;
                        max_idx = i;
                    }
                }
                if (max_idx < 0) continue;

                String label = String(result.classification[max_idx].label);

                /* ── Idle detection ── */
                if (label == "idle" && max_val >= CONF_IDLE) {
                    idle_confirm++;
                    bleLog("IDLE detect " + String(idle_confirm) +
                           "/" + String(IDLE_CONFIRM_COUNT) +
                           " | conf=" + String(max_val, 2));

                    /* End the set after IDLE_CONFIRM_COUNT consecutive idle predictions */
                    if (idle_confirm >= IDLE_CONFIRM_COUNT &&
                        active_exercise != "") {
                        bleLog("SET END | " + active_exercise +
                               " | reps=" + String(rep_count));
                        resetSet();
                        bleLog("--- IDLE ---");
                    }
                    continue;
                }

                /* Any exercise prediction resets the idle confirmation counter */
                if (label != "idle" && idle_confirm > 0) {
                    bleLog("IDLE RESET — exercise motion detected");
                    idle_confirm = 0;
                }

                if (!isExerciseLabel(label)) continue;
                if (max_val < CONF_START)    continue;

                String        exercise = getExerciseName(label);
                unsigned long now_ms   = millis();

                /* ── First exercise detection — start a new set ── */
                if (active_exercise == "") {
                    active_exercise = exercise;
                    pending_switch  = "";
                    switch_confirm  = 0;
                    idle_confirm    = 0;
                    resetPeakTracking();
                    resetDual();

                    /* Seed ml_fired so that if a gyro peak arrives within
                       DUAL_WINDOW_MS, rep 1 is counted immediately */
                    ml_fired      = true;
                    ml_fired_time = now_ms;

                    exerciseCharacteristic.writeValue(active_exercise);
                    bleLog("EXERCISE STARTED: " + active_exercise);
                    bleLog("ML seeded — gyro peak within " +
                           String(DUAL_WINDOW_MS) + "ms counts as rep 1");
                    continue;
                }

                /* ── Same exercise in PHASE_LOCKIN — ML only watches for idle ── */
                if (exercise == active_exercise && phase == PHASE_LOCKIN) {
                    idle_confirm = 0;
                    continue;
                }

                /* ── Same exercise in PHASE_DUAL — attempt dual confirmation ── */
                if (exercise == active_exercise && phase == PHASE_DUAL) {

                    idle_confirm = 0;

                    /* Cancel any pending switch if the original exercise is re-detected */
                    if (pending_switch != "") {
                        bleLog("SWITCH CANCELLED — back to " + active_exercise);
                        pending_switch = "";
                        switch_confirm = 0;
                    }

                    if (now_ms - last_rep_time < MIN_REP_GAP_MS) {
                        bleLog("ML TOO FAST — ignored | " +
                               String(now_ms - last_rep_time) + "ms");
                        continue;
                    }

                    if (peak_fired &&
                        now_ms - peak_fired_time <= DUAL_WINDOW_MS) {
                        /* Peak already fired — dual confirm achieved */
                        bleLog("DUAL OK — ML confirms peak");
                        countRep(peak_fired_val, peak_fired_valley,
                                 now_ms, "DUAL");

                    } else if (!ml_fired) {
                        /* First ML firing — store and wait for peak */
                        ml_fired      = true;
                        ml_fired_time = now_ms;
                        bleLog("ML fired | conf=" + String(max_val, 2) +
                               " | waiting for peak...");

                    } else {
                        /* ML already fired — refresh timestamp */
                        ml_fired_time = now_ms;
                        bleLog("ML re-fired | conf=" + String(max_val, 2));
                    }
                    continue;
                }

                /* ── Different exercise detected — switch logic ── */
                if (exercise != active_exercise) {

                    /* Require CONF_SWITCH confidence before counting toward a switch */
                    if (max_val < CONF_SWITCH) {
                        pending_switch = "";
                        switch_confirm = 0;
                        continue;
                    }

                    if (exercise == pending_switch) {
                        switch_confirm++;
                    } else {
                        pending_switch = exercise;
                        switch_confirm = 1;
                    }

                    bleLog("Switch pending: " + pending_switch +
                           " " + String(switch_confirm) +
                           "/" + String(SWITCH_CONFIRM_COUNT));

                    /* Switch confirmed — reset state for new exercise */
                    if (switch_confirm >= SWITCH_CONFIRM_COUNT) {
                        bleLog("SWITCH CONFIRMED: " + active_exercise +
                               " -> " + pending_switch);
                        active_exercise = pending_switch;
                        rep_count       = 0;
                        last_rep_time   = 0;
                        phase           = PHASE_DUAL;
                        ref_peak        = 0;
                        peak_sum        = 0;
                        peak_cnt        = 0;
                        pending_switch  = "";
                        switch_confirm  = 0;
                        idle_confirm    = 0;
                        cur_valley      = 0;
                        resetPeakTracking();
                        resetDual();

                        /* Seed ml_fired for first rep of new exercise */
                        ml_fired      = true;
                        ml_fired_time = now_ms;

                        exerciseCharacteristic.writeValue(active_exercise);
                        repCharacteristic.writeValue(rep_count);
                        speedCharacteristic.writeValue("normal");
                        bleLog("NOW DOING: " + active_exercise);
                        bleLog("ML seeded — first rep ready");
                    }
                    continue;
                }
            }
        }

        Serial.println("Disconnected");
        BLE.advertise();
    }
}