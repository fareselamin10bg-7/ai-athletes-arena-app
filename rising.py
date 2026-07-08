"""
Rest Period Analyser
====================
AI Athletes Arena — TinyML Wearable Fitness System
Author : Fares Elamin

Analyses between-repetition rest periods from raw 15-second IMU
recordings to derive per-exercise gyroscope floor thresholds for
the firmware's getGyroFloor() function.

For each exercise, the pipeline:
  1. Smooths the gyroscope magnitude using a Savitzky-Golay filter
  2. Detects rest periods as samples below the 20th percentile
  3. Computes rest-period gyroscope and accelerometer statistics
  4. Reports the 75th percentile gyro value as the firmware floor threshold
  5. Saves a diagnostic two-panel plot for the first recording
"""

import os
import json
import csv
import numpy as np
import matplotlib.pyplot as plt
from scipy.signal import savgol_filter

# ===================================================================
# SETTINGS
# ===================================================================

BASE_INPUT_DIR = r"C:\Users\HP\Desktop\segmenting"

EXERCISES = [
    "Bench press",
    "Bicep curls",
    "Front raises",
    "Lateral raises",
    "Overhead triceps",
]

SAMPLE_RATE      = 50  # IMU sampling rate (Hz)
SMOOTHING_WINDOW = 7   # Savitzky-Golay filter window length (samples)

# ===================================================================
# FILE READERS
# ===================================================================

def read_json(filepath: str) -> list:
    """
    Read a six-axis IMU recording from an Edge Impulse JSON export.
    Returns a list of [ax, ay, az, gx, gy, gz] rows.
    """
    with open(filepath, "r") as f:
        raw = json.load(f)
    values = []
    for row in raw["payload"]["values"]:
        if len(row) >= 6:
            values.append([float(row[i]) for i in range(6)])
    return values


def read_csv(filepath: str) -> list:
    """
    Read a six-axis IMU recording from a CSV file.
    Supports labelled headers (accX, accY, ...) and
    unlabelled columns with an optional leading timestamp column.
    Returns a list of [ax, ay, az, gx, gy, gz] rows.
    """
    with open(filepath, "r") as f:
        reader = csv.reader(f)
        header = [h.strip().lower() for h in next(reader)]

    known_cols = {"accx", "accy", "accz", "gyrx", "gyry", "gyrz"}
    values = []

    if known_cols.issubset(set(header)):
        # Named header found — use column indices directly
        idx = {col: header.index(col) for col in known_cols}
        with open(filepath, "r") as f:
            reader = csv.reader(f)
            next(reader)
            for row in reader:
                if len(row) >= 6:
                    values.append([
                        float(row[idx["accx"]]), float(row[idx["accy"]]),
                        float(row[idx["accz"]]), float(row[idx["gyrx"]]),
                        float(row[idx["gyry"]]), float(row[idx["gyrz"]]),
                    ])
    else:
        # Fallback: assume positional columns, skip optional timestamp column
        offset = 1 if len(header) >= 7 else 0
        with open(filepath, "r") as f:
            reader = csv.reader(f)
            next(reader)
            for row in reader:
                if len(row) >= 6:
                    values.append([float(row[offset + i]) for i in range(6)])

    return values


def read_file(filepath: str) -> list:
    """
    Dispatch to the correct file reader based on extension.
    Supports .json (Edge Impulse format) and .csv.
    """
    ext = os.path.splitext(filepath)[1].lower()
    if ext == ".json":
        return read_json(filepath)
    if ext == ".csv":
        return read_csv(filepath)
    raise ValueError(f"Unsupported file type: {ext}")

# ===================================================================
# REST PERIOD DETECTION
# ===================================================================

def find_rest_segments(gyro_smooth: np.ndarray,
                        min_duration_samples: int = 3) -> tuple:
    """
    Identify contiguous rest periods in the smoothed gyroscope signal.

    A sample is considered resting if its magnitude falls below the
    20th percentile of the recording. This threshold adapts automatically
    to each participant's movement style and recording intensity.

    Segments shorter than min_duration_samples (default 3 = 60 ms)
    are discarded as noise.

    Returns:
        segments  : list of (start, end) index tuples
        threshold : the 20th percentile value used as the rest boundary
    """
    threshold  = np.percentile(gyro_smooth, 20)
    is_resting = gyro_smooth < threshold

    segments = []
    in_rest  = False
    start    = 0

    for i, resting in enumerate(is_resting):
        if resting and not in_rest:
            in_rest = True
            start   = i
        elif not resting and in_rest:
            in_rest = False
            if i - start > min_duration_samples:
                segments.append((start, i))

    # Handle recording that ends while still in a rest period
    if in_rest and len(is_resting) - start > min_duration_samples:
        segments.append((start, len(is_resting)))

    return segments, threshold


def collect_rest_statistics(
    gyro_smooth:  np.ndarray,
    accel_smooth: np.ndarray,
    segments:     list,
    sample_rate:  int,
) -> tuple:
    """
    Extract per-segment statistics from detected rest periods.

    For each segment, computes:
      - Mean gyroscope magnitude (deg/s)
      - Mean accelerometer magnitude (m/s²)
      - Duration (ms)

    Returns:
        (rest_gyro_values, rest_accel_values, rest_durations_ms)
    """
    rest_gyro      = []
    rest_accel     = []
    rest_durations = []

    for start, end in segments:
        rest_gyro.append(float(np.mean(gyro_smooth[start:end])))
        rest_accel.append(float(np.mean(accel_smooth[start:end])))
        rest_durations.append((end - start) * (1000 / sample_rate))

    return rest_gyro, rest_accel, rest_durations

# ===================================================================
# PLOTTING
# ===================================================================

def plot_exercise(
    exercise_name:  str,
    filename:       str,
    gyro_smooth:    np.ndarray,
    accel_smooth:   np.ndarray,
    segments:       list,
    rest_threshold: float,
    sample_rate:    int,
) -> None:
    """
    Save a two-panel diagnostic plot for one recording.

    Top panel    : smoothed gyroscope magnitude with rest threshold
                   and green-shaded rest periods
    Bottom panel : smoothed accelerometer magnitude with gravity reference
                   and green-shaded rest periods

    Output saved as <exercise_name>_rest_analysis.png
    """
    time_axis = np.arange(len(gyro_smooth)) / sample_rate

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(14, 7), sharex=True)
    fig.suptitle(f"{exercise_name}  —  {filename}", fontsize=13)

    # Gyroscope panel
    ax1.plot(time_axis, gyro_smooth, color="royalblue",
             label="Gyro magnitude (smoothed)")
    ax1.axhline(rest_threshold, color="red", linestyle="--", linewidth=1,
                label=f"Rest threshold (20th pct = {rest_threshold:.1f} deg/s)")
    for start, end in segments:
        ax1.axvspan(start / sample_rate, end / sample_rate,
                    alpha=0.2, color="green")
    ax1.set_ylabel("deg/s")
    ax1.legend(fontsize=9)
    ax1.set_title("Gyroscope magnitude  —  green = detected rest periods")

    # Accelerometer panel
    ax2.plot(time_axis, accel_smooth, color="darkorange",
             label="Accel magnitude (smoothed)")
    ax2.axhline(9.8, color="black", linestyle="--", linewidth=1,
                label="9.8 m/s²  (gravity reference)")
    for start, end in segments:
        ax2.axvspan(start / sample_rate, end / sample_rate,
                    alpha=0.2, color="green")
    ax2.set_ylabel("m/s²")
    ax2.set_xlabel("Time (s)")
    ax2.legend(fontsize=9)
    ax2.set_title("Accelerometer magnitude  —  green = detected rest periods")

    plt.tight_layout()
    output_name = exercise_name.replace(" ", "_") + "_rest_analysis.png"
    plt.savefig(output_name, dpi=120, bbox_inches="tight")
    plt.close()
    print(f"  Plot saved: {output_name}")

# ===================================================================
# PER-EXERCISE ANALYSIS
# ===================================================================

def analyze_exercise(exercise_name: str) -> dict | None:
    """
    Process all recordings for one exercise class.

    For each recording:
      - Computes gyroscope and accelerometer magnitudes
      - Applies Savitzky-Golay smoothing
      - Detects rest periods using find_rest_segments()
      - Collects statistics using collect_rest_statistics()

    Aggregates statistics across all files and computes:
      - 75th percentile gyro → firmware gyro floor threshold
      - 25th percentile duration → minimum rest duration reference

    Saves a diagnostic plot for the first file only.

    Returns a dictionary of results, or None if no rest segments
    were found across any file.
    """
    input_dir = os.path.join(BASE_INPUT_DIR, exercise_name)

    print("=" * 60)
    print(f"EXERCISE : {exercise_name.upper()}")

    if not os.path.exists(input_dir):
        print("  ERROR: input folder not found — skipping")
        return None

    all_files = sorted(
        f for f in os.listdir(input_dir)
        if f.lower().endswith((".csv", ".json"))
    )

    if not all_files:
        print("  WARNING: no CSV or JSON files found — skipping")
        return None

    print(f"  Files : {len(all_files)}")

    all_rest_gyro      = []
    all_rest_accel     = []
    all_rest_durations = []
    plot_saved         = False

    for filename in all_files:
        filepath = os.path.join(input_dir, filename)

        try:
            values = read_file(filepath)
        except Exception as exc:
            print(f"  ERROR in {filename}: {exc}")
            continue

        if len(values) < SMOOTHING_WINDOW + 2:
            continue

        arr = np.array(values)

        # Compute signal magnitudes across all three axes
        accel_mag = np.sqrt(arr[:, 0]**2 + arr[:, 1]**2 + arr[:, 2]**2)
        gyro_mag  = np.sqrt(arr[:, 3]**2 + arr[:, 4]**2 + arr[:, 5]**2)

        # Smooth both signals before analysis
        gyro_smooth  = savgol_filter(gyro_mag,  SMOOTHING_WINDOW, 2)
        accel_smooth = savgol_filter(accel_mag, SMOOTHING_WINDOW, 2)

        segments, rest_threshold = find_rest_segments(gyro_smooth)

        rest_gyro, rest_accel, rest_durations = collect_rest_statistics(
            gyro_smooth, accel_smooth, segments, SAMPLE_RATE
        )

        all_rest_gyro.extend(rest_gyro)
        all_rest_accel.extend(rest_accel)
        all_rest_durations.extend(rest_durations)

        # Save diagnostic plot for the first file only
        if not plot_saved:
            plot_saved = True
            plot_exercise(
                exercise_name, filename,
                gyro_smooth, accel_smooth,
                segments, rest_threshold, SAMPLE_RATE
            )

    if not all_rest_gyro:
        print("  WARNING: no rest segments detected across any file")
        return None

    all_rest_gyro      = np.array(all_rest_gyro)
    all_rest_accel     = np.array(all_rest_accel)
    all_rest_durations = np.array(all_rest_durations)

    # Derive firmware thresholds from aggregated statistics
    gyro_threshold     = np.percentile(all_rest_gyro,      75)
    duration_threshold = int(np.percentile(all_rest_durations, 25))

    # Print full statistics report
    print()
    print("  REST PERIOD STATISTICS (across all files):")
    print()
    print("  Gyro during rest (deg/s):")
    print(f"    mean   = {np.mean(all_rest_gyro):.1f}")
    print(f"    median = {np.median(all_rest_gyro):.1f}")
    print(f"    max    = {np.max(all_rest_gyro):.1f}")
    print(f"    std    = {np.std(all_rest_gyro):.1f}")
    print()
    print("  Accel during rest (m/s²):")
    print(f"    mean   = {np.mean(all_rest_accel):.2f}")
    print(f"    median = {np.median(all_rest_accel):.2f}")
    print(f"    std    = {np.std(all_rest_accel):.2f}")
    print()
    print("  Rest duration (ms):")
    print(f"    mean   = {np.mean(all_rest_durations):.0f}")
    print(f"    median = {np.median(all_rest_durations):.0f}")
    print(f"    min    = {np.min(all_rest_durations):.0f}")
    print(f"    max    = {np.max(all_rest_durations):.0f}")
    print()
    print("  SUGGESTED ARDUINO VALUES:")
    print(f"    GYRO_REST_THRESHOLD   = {gyro_threshold:.0f}f  "
          f"(75th percentile of rest gyro)")
    print(f"    GYRO_REST_DURATION_MS = {duration_threshold}  "
          f"(25th percentile of rest duration)")

    return {
        "gyro_threshold":     gyro_threshold,
        "duration_threshold": duration_threshold,
        "gyro_mean":          float(np.mean(all_rest_gyro)),
        "gyro_median":        float(np.median(all_rest_gyro)),
        "gyro_max":           float(np.max(all_rest_gyro)),
        "gyro_std":           float(np.std(all_rest_gyro)),
    }

# ===================================================================
# ENTRY POINT
# ===================================================================

def main() -> None:
    """
    Run the rest period analysis pipeline across all exercises.
    Prints firmware-ready getGyroFloor() threshold values at the end.
    """
    print("=" * 60)
    print("  REST PERIOD ANALYSER")
    print("  AI Athletes Arena — Threshold Derivation Tool")
    print("=" * 60)
    print(f"  Input directory  : {BASE_INPUT_DIR}")
    print(f"  Exercises        : {len(EXERCISES)}")
    print(f"  Sample rate      : {SAMPLE_RATE} Hz")
    print(f"  Smoothing window : {SMOOTHING_WINDOW} samples")
    print()

    all_results = {}

    for exercise_name in EXERCISES:
        result = analyze_exercise(exercise_name)
        if result is not None:
            all_results[exercise_name] = result
        print()

    # Print firmware-ready threshold values
    print("=" * 60)
    print("  FIRMWARE-READY THRESHOLDS")
    print("  Paste into getGyroFloor() in Arduino sketch")
    print("=" * 60)
    print()

    for exercise, vals in all_results.items():
        print(f'    if (exercise == "{exercise}")')
        print(f'        return {vals["gyro_threshold"]:.0f}.0f;'
              f'  // mean={vals["gyro_mean"]:.1f}  '
              f'max={vals["gyro_max"]:.1f}  '
              f'std={vals["gyro_std"]:.1f}')
        print()

    print()
    print("  PNG plots saved next to this script.")
    print()
    print("  DONE")


if __name__ == "__main__":
    main()
