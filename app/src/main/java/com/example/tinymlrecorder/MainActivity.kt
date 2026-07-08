package com.example.tinymlrecorder

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var exerciseInput: EditText
    private lateinit var setsInput: EditText
    private lateinit var connectButton: Button
    private lateinit var startButton: Button
    private lateinit var shareButton: Button
    private lateinit var monitorButton: Button
    private lateinit var statusText: TextView
    private lateinit var samplesText: TextView

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleScanner: BluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDeviceAddress: String? = null
    private var isConnected = false
    private var lastSavedFile: File? = null

    private val handler = Handler(Looper.getMainLooper())
    private val PERMISSION_REQUEST_CODE = 1

    private val SERVICE_UUID        = UUID.fromString("0000180C-0000-1000-8000-00805F9B34FB")
    private val CHARACTERISTIC_UUID = UUID.fromString("00002A56-0000-1000-8000-00805F9B34FB")
    private val CCCD_UUID           = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private var isRecording = false
    private val segmentDurationMs = 15000L

    // Multi-set state
    private var totalSets = 1
    private var currentSet = 0
    private val csvRows = mutableListOf<String>()
    private var sampleIndex = 0
    private var countdownSeconds = 15
    private var currentExerciseName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        exerciseInput = findViewById(R.id.exerciseInput)
        setsInput     = findViewById(R.id.setsInput)
        connectButton = findViewById(R.id.connectButton)
        startButton   = findViewById(R.id.startButton)
        shareButton   = findViewById(R.id.shareButton)
        monitorButton = findViewById(R.id.monitorButton)
        statusText    = findViewById(R.id.statusText)
        samplesText   = findViewById(R.id.samplesText)

        exerciseInput.setHintTextColor(android.graphics.Color.parseColor("#666666"))
        setsInput.setHintTextColor(android.graphics.Color.parseColor("#666666"))

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter.bluetoothLeScanner

        startButton.isEnabled   = false
        shareButton.isEnabled   = false
        monitorButton.isEnabled = false

        connectButton.setOnClickListener {
            if (isConnected) disconnectWatch()
            else checkPermissionsAndScan()
        }

        startButton.setOnClickListener {
            val exerciseName = exerciseInput.text.toString().trim()
            if (exerciseName.isEmpty()) {
                statusText.text = "Please enter exercise name first"
                return@setOnClickListener
            }
            val setsStr = setsInput.text.toString().trim()
            totalSets = if (setsStr.isEmpty()) 1 else setsStr.toIntOrNull()?.coerceAtLeast(1) ?: 1
            currentExerciseName = exerciseName
            startCountdown()
        }

        shareButton.setOnClickListener { shareLastFile() }

        monitorButton.setOnClickListener {
            val address = connectedDeviceAddress ?: run {
                statusText.text = "Not connected. Connect first."
                return@setOnClickListener
            }
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            isConnected = false
            val intent = Intent(this, MonitorActivity::class.java)
            intent.putExtra("device_address", address)
            startActivity(intent)
        }

        statusText.text = "Press Connect to find watch"
        samplesText.text = ""
    }

    // ── 3-2-1 COUNTDOWN BEFORE RECORDING ──────────────────────────────────────

    private fun startCountdown() {
        startButton.isEnabled   = false
        connectButton.isEnabled = false
        shareButton.isEnabled   = false
        monitorButton.isEnabled = false

        statusText.text  = "Get ready..."
        samplesText.text = ""

        var count = 3
        val countRunnable = object : Runnable {
            override fun run() {
                if (count > 0) {
                    statusText.text = "$count"
                    count--
                    handler.postDelayed(this, 1000)
                } else {
                    statusText.text = "GO!"
                    handler.postDelayed({ startNextSet() }, 500)
                }
            }
        }
        handler.post(countRunnable)
    }

    // ── SET MANAGEMENT ─────────────────────────────────────────────────────────

    private fun startNextSet() {
        currentSet++
        csvRows.clear()
        sampleIndex = 0
        countdownSeconds = 15
        isRecording = true

        statusText.text  = "● Recording set $currentSet / $totalSets — $currentExerciseName"
        samplesText.text = "Samples: 0 | Time left: 15s"

        // Live countdown display
        val countdownRunnable = object : Runnable {
            override fun run() {
                if (isRecording && countdownSeconds > 0) {
                    countdownSeconds--
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(countdownRunnable, 1000)

        // End of this 15-second segment
        handler.postDelayed({
            isRecording = false
            saveCSVForSet(currentExerciseName, currentSet)
        }, segmentDurationMs)
    }

    // ── SAVE ONE CSV SEGMENT ───────────────────────────────────────────────────

    private fun saveCSVForSet(exerciseName: String, setNumber: Int) {
        if (csvRows.isEmpty()) {
            runOnUiThread {
                statusText.text = "No data for set $setNumber. Check connection."
                resetButtons()
            }
            return
        }

        val fileName = "${exerciseName}_set${setNumber}_${System.currentTimeMillis()}.csv"
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )

        try {
            val writer = FileWriter(file)
            writer.write("timestamp,accX,accY,accZ,gyrX,gyrY,gyrZ\n")
            csvRows.forEach { row -> writer.write("$row\n") }
            writer.flush()
            writer.close()

            lastSavedFile = file

            runOnUiThread {
                statusText.text = "✓ Set $setNumber/$totalSets saved — $fileName"
                samplesText.text = "Samples in set: ${csvRows.size}"
            }

            // If more sets remain, show a brief "get ready" then start next
            if (currentSet < totalSets) {
                handler.postDelayed({
                    runOnUiThread { statusText.text = "Set $setNumber done! Next set starting..." }
                    handler.postDelayed({ startNextSet() }, 1500)
                }, 300)
            } else {
                // All sets done
                runOnUiThread {
                    statusText.text = "✓ All $totalSets sets saved to Downloads!"
                    samplesText.text = "Done! $totalSets CSV files saved."
                    resetButtons()
                    shareButton.isEnabled = true
                }
            }

        } catch (e: Exception) {
            runOnUiThread {
                statusText.text = "Save failed: ${e.message}"
                resetButtons()
            }
        }
    }

    private fun resetButtons() {
        startButton.isEnabled   = true
        connectButton.isEnabled = true
        monitorButton.isEnabled = isConnected
        currentSet = 0
    }

    // ── BLE ────────────────────────────────────────────────────────────────────

    private fun checkPermissionsAndScan() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) startScan()
            else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) startScan()
            else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                startScan()
            else
                statusText.text = "Permissions required. Please grant all."
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun startScan() {
        connectButton.isEnabled = false
        statusText.text = "Scanning for TinyML-Watch..."

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner.startScan(null, scanSettings, scanCallback)

        handler.postDelayed({
            bleScanner.stopScan(scanCallback)
            if (!isConnected) {
                statusText.text = "Watch not found. Is it powered on?"
                connectButton.isEnabled = true
                connectButton.text = "Connect"
            }
        }, 15000)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressWarnings("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name
                ?: result.scanRecord?.deviceName
                ?: return
            runOnUiThread { samplesText.text = "Found: $deviceName" }
            if (deviceName == "TinyML-Watch") {
                bleScanner.stopScan(this)
                runOnUiThread { statusText.text = "Watch found! Connecting..." }
                connectToWatch(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                statusText.text = "Scan failed. Error: $errorCode"
                connectButton.isEnabled = true
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun connectToWatch(device: BluetoothDevice) {
        connectedDeviceAddress = device.address
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    @SuppressWarnings("MissingPermission")
    private fun disconnectWatch() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
        connectedDeviceAddress = null
        connectButton.text = "Connect"
        connectButton.isEnabled = true
        startButton.isEnabled = false
        monitorButton.isEnabled = false
        samplesText.text = ""
        statusText.text = "Disconnected. Press Connect to reconnect."
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressWarnings("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    runOnUiThread { statusText.text = "Connected! Requesting MTU..." }
                    gatt.requestMtu(128)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    isRecording = false
                    bluetoothGatt = null
                    runOnUiThread {
                        statusText.text = "Watch disconnected. Press Connect to retry."
                        connectButton.text = "Connect"
                        connectButton.isEnabled = true
                        startButton.isEnabled = false
                        monitorButton.isEnabled = false
                        samplesText.text = ""
                    }
                }
            }
        }

        @SuppressWarnings("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            runOnUiThread { statusText.text = "MTU set to $mtu. Discovering services..." }
            gatt.discoverServices()
        }

        @SuppressWarnings("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { statusText.text = "Service discovery failed" }
                return
            }

            val characteristic = gatt
                .getService(SERVICE_UUID)
                ?.getCharacteristic(CHARACTERISTIC_UUID)

            if (characteristic == null) {
                runOnUiThread { statusText.text = "IMU characteristic not found" }
                return
            }

            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            descriptor?.let { gatt.writeDescriptor(it) }

            isConnected = true

            runOnUiThread {
                statusText.text = "✓ Watch connected! Enter exercise and press REC"
                connectButton.text = "Disconnect"
                connectButton.isEnabled = true
                startButton.isEnabled = true
                monitorButton.isEnabled = true
                samplesText.text = "Samples: 0"
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val bytes = characteristic.value ?: return
            val raw = String(bytes, Charsets.UTF_8).trim()

            runOnUiThread {
                if (!isRecording) samplesText.text = "Live: $raw"
            }

            if (!isRecording) return

            val timestamp = sampleIndex * 40
            csvRows.add("$timestamp,$raw")
            sampleIndex++

            runOnUiThread {
                samplesText.text = "Samples: $sampleIndex | Time left: ${countdownSeconds}s"
            }
        }
    }

    private fun shareLastFile() {
        val file = lastSavedFile ?: run {
            statusText.text = "No file to share yet. Record first."
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "com.example.tinymlrecorder.provider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share CSV via..."))
        } catch (e: Exception) {
            statusText.text = "Share failed: ${e.message}"
        }
    }

    @SuppressWarnings("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}