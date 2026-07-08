package com.example.tinymlrecorder.ui

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tinymlrecorder.R
import java.util.UUID

class DebugLogActivity : AppCompatActivity() {

    private lateinit var logContainer: TextView
    private lateinit var statusText: TextView
    private lateinit var clearButton: Button
    private lateinit var backButton: Button
    private lateinit var pauseButton: Button
    private lateinit var copyButton: Button
    private lateinit var markRepButton: Button
    private lateinit var showComparisonButton: Button
    private lateinit var scrollView: ScrollView

    private var bluetoothGatt: BluetoothGatt? = null
    private var isPaused = false

    private val FITNESS_SERVICE_UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    private val REP_CHAR_UUID        = UUID.fromString("00002A57-0000-1000-8000-00805F9B34FB")
    private val EXERCISE_CHAR_UUID   = UUID.fromString("00002A58-0000-1000-8000-00805F9B34FB")
    private val SPEED_CHAR_UUID      = UUID.fromString("00002A59-0000-1000-8000-00805F9B34FB")
    private val LOG_CHAR_UUID        = UUID.fromString("00002A60-0000-1000-8000-00805F9B34FB")
    private val CCCD_UUID            = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private val descriptorWriteQueue = ArrayDeque<BluetoothGattDescriptor>()
    private var isWritingDescriptor  = false

    // All log lines with timestamp
    private val logLines = mutableListOf<Triple<String, Int, Long>>()

    // Manual rep tracking
    private var repStartTime   = 0L
    private var repStartLogIdx = 0
    private var isHolding      = false

    // Completed manual reps
    data class ManualRep(
        val repNumber:  Int,
        val startTime:  Long,
        val endTime:    Long,
        val logsDuring: List<String>
    )
    private val manualReps = mutableListOf<ManualRep>()

    private val sessionStart = System.currentTimeMillis()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_log)

        logContainer         = findViewById(R.id.logContainer)
        statusText           = findViewById(R.id.debugStatusText)
        clearButton          = findViewById(R.id.clearButton)
        backButton           = findViewById(R.id.debugBackButton)
        pauseButton          = findViewById(R.id.pauseButton)
        copyButton           = findViewById(R.id.copyButton)
        markRepButton        = findViewById(R.id.markRepButton)
        showComparisonButton = findViewById(R.id.showComparisonButton)
        scrollView           = findViewById(R.id.debugScrollView)

        clearButton.setOnClickListener {
            logLines.clear()
            manualReps.clear()
            isHolding = false
            resetMarkButton()
            logContainer.text = ""
            statusText.text = "Log cleared."
        }

        backButton.setOnClickListener { disconnectAndGoBack() }

        pauseButton.setOnClickListener {
            isPaused = !isPaused
            if (isPaused) {
                pauseButton.text = "▶ Resume"
                pauseButton.setBackgroundResource(R.drawable.button_dark)
            } else {
                pauseButton.text = "⏸ Pause"
                pauseButton.setBackgroundResource(R.drawable.button_blue)
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        copyButton.setOnClickListener {
            if (logLines.isEmpty()) {
                Toast.makeText(this, "Log is empty.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val fullText = logLines.joinToString("\n") { it.first }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Debug Log", fullText))
            Toast.makeText(this, "Log copied!", Toast.LENGTH_SHORT).show()
        }

        /* ── hold to record rep ── */
        markRepButton.setOnTouchListener { _, event ->
            when (event.action) {

                MotionEvent.ACTION_DOWN -> {
                    // finger down = rep start
                    isHolding      = true
                    repStartTime   = System.currentTimeMillis()
                    repStartLogIdx = logLines.size

                    markRepButton.text = "🔴 RECORDING..."
                    markRepButton.setBackgroundColor(Color.parseColor("#FF3B30"))
                    statusText.text = "Holding — rep started at ${formatTime(repStartTime)}"
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (!isHolding) return@setOnTouchListener true

                    // finger up = rep end
                    isHolding      = false
                    val repEnd     = System.currentTimeMillis()
                    val duration   = repEnd - repStartTime

                    // ignore accidental taps under 200ms
                    if (duration < 200) {
                        resetMarkButton()
                        statusText.text = "Too short — hold for the full rep duration."
                        return@setOnTouchListener true
                    }

                    val logsInWindow = logLines
                        .drop(repStartLogIdx)
                        .map { it.first }

                    val rep = ManualRep(
                        repNumber  = manualReps.size + 1,
                        startTime  = repStartTime,
                        endTime    = repEnd,
                        logsDuring = logsInWindow
                    )
                    manualReps.add(rep)

                    appendLog(
                        "── MANUAL REP #${rep.repNumber} | " +
                                "start=${formatTime(repStartTime)} " +
                                "end=${formatTime(repEnd)} " +
                                "dur=${duration}ms ──",
                        Color.parseColor("#FFD60A")
                    )

                    resetMarkButton()
                    statusText.text =
                        "Rep #${rep.repNumber} saved (${duration}ms). " +
                                "${manualReps.size} reps total."
                    true
                }

                else -> false
            }
        }

        showComparisonButton.setOnClickListener {
            if (manualReps.isEmpty()) {
                Toast.makeText(this, "No manual reps recorded yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            RepComparisonActivity.manualReps = manualReps.toMutableList()
            startActivity(Intent(this, RepComparisonActivity::class.java))
        }

        val deviceAddress = intent.getStringExtra("device_address")
        if (deviceAddress == null) {
            statusText.text = "No device address. Go back and reconnect."
            return
        }

        connectToDevice(deviceAddress)
    }

    private fun resetMarkButton() {
        markRepButton.text = "🟢 HOLD for rep"
        markRepButton.setBackgroundColor(Color.parseColor("#34C759"))
    }

    private fun formatTime(ms: Long): String {
        val elapsed = ms - sessionStart
        val min     = elapsed / 60000
        val sec     = (elapsed % 60000) / 1000
        val millis  = elapsed % 1000
        return "%d:%02d.%03d".format(min, sec, millis)
    }

    @SuppressWarnings("MissingPermission")
    private fun connectToDevice(address: String) {
        statusText.text = "Connecting to watch..."
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = bluetoothManager.adapter.getRemoteDevice(address)
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private fun appendLog(text: String, color: Int) {
        val now = System.currentTimeMillis()
        runOnUiThread {
            logLines.add(Triple(text, color, now))
            val sb = android.text.SpannableStringBuilder()
            for ((line, c, _) in logLines) {
                val start = sb.length
                sb.append(line).append("\n")
                sb.setSpan(
                    android.text.style.ForegroundColorSpan(c),
                    start, sb.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            logContainer.text = sb
            if (!isPaused) {
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressWarnings("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    runOnUiThread { statusText.text = "Connected. Discovering services..." }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    runOnUiThread { statusText.text = "Disconnected from watch." }
                    appendLog("--- DISCONNECTED ---", Color.parseColor("#FF3B30"))
                }
            }
        }

        @SuppressWarnings("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { statusText.text = "Service discovery failed." }
                return
            }

            val fitnessService = gatt.getService(FITNESS_SERVICE_UUID)
            if (fitnessService == null) {
                runOnUiThread { statusText.text = "Fitness service not found." }
                return
            }

            fitnessService.getCharacteristic(REP_CHAR_UUID)?.let      { enableNotification(gatt, it) }
            fitnessService.getCharacteristic(EXERCISE_CHAR_UUID)?.let { enableNotification(gatt, it) }
            fitnessService.getCharacteristic(SPEED_CHAR_UUID)?.let    { enableNotification(gatt, it) }
            fitnessService.getCharacteristic(LOG_CHAR_UUID)?.let      { enableNotification(gatt, it) }

            runOnUiThread { statusText.text = "Live — hold button to record reps" }
            appendLog("=== CONNECTED — waiting for reps ===", Color.parseColor("#0A84FF"))
        }

        @SuppressWarnings("MissingPermission")
        private fun enableNotification(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(CCCD_UUID) ?: return
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            descriptorWriteQueue.addLast(descriptor)
            if (!isWritingDescriptor) writeNextDescriptor(gatt)
        }

        @SuppressWarnings("MissingPermission")
        private fun writeNextDescriptor(gatt: BluetoothGatt) {
            val next = descriptorWriteQueue.removeFirstOrNull()
            if (next != null) {
                isWritingDescriptor = true
                gatt.writeDescriptor(next)
            } else {
                isWritingDescriptor = false
            }
        }

        @SuppressWarnings("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            writeNextDescriptor(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {

                REP_CHAR_UUID -> {
                    val count = characteristic.getIntValue(
                        BluetoothGattCharacteristic.FORMAT_SINT32, 0) ?: 0
                    appendLog("REP #$count counted", Color.parseColor("#34C759"))
                }

                EXERCISE_CHAR_UUID -> {
                    val exercise = String(
                        characteristic.value ?: byteArrayOf(), Charsets.UTF_8).trim()
                    if (exercise == "idle") {
                        appendLog("--- SET ENDED (idle) ---", Color.parseColor("#FF9500"))
                    } else {
                        appendLog("EXERCISE STARTED: $exercise", Color.parseColor("#0A84FF"))
                    }
                }

                SPEED_CHAR_UUID -> {
                    val speed = String(
                        characteristic.value ?: byteArrayOf(), Charsets.UTF_8).trim()
                    val color = when (speed) {
                        "fast" -> Color.parseColor("#34C759")
                        "slow" -> Color.parseColor("#FF3B30")
                        else   -> Color.parseColor("#FF9500")
                    }
                    appendLog("SPEED: $speed", color)
                }

                LOG_CHAR_UUID -> {
                    val msg = String(
                        characteristic.value ?: byteArrayOf(), Charsets.UTF_8).trim()

                    val color = when {
                        msg.startsWith("REP #")            -> Color.parseColor("#34C759")
                        msg.startsWith("REP BLOCKED")      -> Color.parseColor("#FF3B30")
                        msg.startsWith("SET END")          -> Color.parseColor("#FF9500")
                        msg.startsWith("SWITCH CONFIRMED") -> Color.parseColor("#0A84FF")
                        msg.startsWith("Switch pending")   -> Color.parseColor("#5E5CE6")
                        msg.startsWith("[DBG")             -> Color.parseColor("#636366")
                        msg.startsWith("  ax=")            -> Color.parseColor("#5E5CE6")
                        msg.startsWith("  gx=")            -> Color.parseColor("#5E5CE6")
                        msg.startsWith("GYRO")             -> Color.parseColor("#FF9500")
                        msg.startsWith("ACCEL")            -> Color.parseColor("#FF9500")
                        msg.startsWith("ADAPTIVE")         -> Color.parseColor("#FF9500")
                        msg.startsWith("CALIB")            -> Color.parseColor("#0A84FF")
                        msg.startsWith("=== CALIB")        -> Color.parseColor("#34C759")
                        msg.startsWith("TOO FAST")         -> Color.parseColor("#FF3B30")
                        else                               -> Color.parseColor("#EBEBF5")
                    }

                    appendLog(msg, color)
                }
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun disconnectAndGoBack() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        finish()
    }

    @SuppressWarnings("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}