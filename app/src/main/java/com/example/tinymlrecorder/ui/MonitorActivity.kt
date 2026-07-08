package com.example.tinymlrecorder

import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.example.tinymlrecorder.ui.DebugLogActivity
import java.util.UUID

class MonitorActivity : AppCompatActivity() {

    private lateinit var exerciseText: TextView
    private lateinit var countLabel: TextView
    private lateinit var repText: TextView
    private lateinit var speedText: TextView
    private lateinit var coachText: TextView
    private lateinit var setText: TextView
    private lateinit var totalText: TextView
    private lateinit var statusText: TextView
    private lateinit var backButton: Button
    private lateinit var statsButton: Button
    private lateinit var resetButton: Button
    private lateinit var debugButton: Button
    private lateinit var exerciseVideo: VideoView
    private lateinit var videoContainer: FrameLayout

    private var bluetoothGatt: BluetoothGatt? = null
    private var currentExercise  = ""
    private var currentVideoResId = 0
    private var deviceAddress    = ""

    private val FITNESS_SERVICE_UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    private val REP_CHAR_UUID        = UUID.fromString("00002A57-0000-1000-8000-00805F9B34FB")
    private val EXERCISE_CHAR_UUID   = UUID.fromString("00002A58-0000-1000-8000-00805F9B34FB")
    private val SPEED_CHAR_UUID      = UUID.fromString("00002A59-0000-1000-8000-00805F9B34FB")
    private val CCCD_UUID            = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private val descriptorWriteQueue = ArrayDeque<BluetoothGattDescriptor>()
    private var isWritingDescriptor  = false

    companion object {
        val exerciseSetCount = mutableMapOf<String, Int>()
        val exerciseTotals   = mutableMapOf<String, Int>()
        val setHistory       = mutableListOf<Triple<String, Int, Int>>()
        var wasExercising    = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)

        exerciseText   = findViewById(R.id.exerciseText)
        countLabel     = findViewById(R.id.repLabel)
        repText        = findViewById(R.id.repText)
        speedText      = findViewById(R.id.speedText)
        coachText      = findViewById(R.id.coachText)
        setText        = findViewById(R.id.setText)
        totalText      = findViewById(R.id.totalText)
        statusText     = findViewById(R.id.monitorStatusText)
        backButton     = findViewById(R.id.backButton)
        statsButton    = findViewById(R.id.statsButton)
        resetButton    = findViewById(R.id.resetButton)
        debugButton    = findViewById(R.id.debugButton)
        exerciseVideo  = findViewById(R.id.exerciseVideo)
        videoContainer = findViewById(R.id.videoContainer)

        backButton.setOnClickListener { disconnectAndGoBack() }

        statsButton.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        debugButton.setOnClickListener {
            val intent = Intent(this, DebugLogActivity::class.java)
            intent.putExtra("device_address", deviceAddress)
            startActivity(intent)
        }

        resetButton.setOnClickListener {
            exerciseSetCount.clear()
            exerciseTotals.clear()
            setHistory.clear()
            wasExercising             = false
            currentExercise           = ""
            currentVideoResId         = 0
            repText.text              = "0"
            speedText.text            = ""
            coachText.text            = ""
            setText.text              = "0"
            totalText.text            = ""
            exerciseText.text         = "—"
            countLabel.text           = "Reps"
            statusText.text           = "✓ Stats reset. Start your exercise!"
            videoContainer.visibility = View.GONE
            exerciseVideo.stopPlayback()
        }

        refreshCurrentStats()

        deviceAddress = intent.getStringExtra("device_address") ?: ""
        if (deviceAddress.isEmpty()) {
            statusText.text = "No device address. Go back and reconnect."
            return
        }

        connectToDevice(deviceAddress)
    }

    private fun showExerciseVideo(exercise: String) {
        val resId = when (exercise.lowercase().trim()) {
            "bicep curls"      -> R.raw.bicep
            "bench press"      -> R.raw.bench
            "front raises"     -> R.raw.front
            "lat pull down"    -> R.raw.lat
            "lateral raises"   -> R.raw.lateral
            "overhead triceps" -> R.raw.overhead
            else               -> 0
        }
        if (resId != 0) {
            if (resId == currentVideoResId && exerciseVideo.isPlaying) return

            currentVideoResId         = resId
            val uri = Uri.parse("android.resource://${packageName}/$resId")
            videoContainer.visibility = View.VISIBLE
            exerciseVideo.setVideoURI(uri)
            exerciseVideo.setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                exerciseVideo.start()
            }
        } else {
            currentVideoResId         = 0
            videoContainer.visibility = View.GONE
            exerciseVideo.stopPlayback()
            android.util.Log.d("EXERCISE_DEBUG", "No video mapped for: '$exercise'")
        }
    }

    private fun refreshCurrentStats() {
        val setNum = exerciseSetCount[currentExercise] ?: 0
        setText.text = if (currentExercise == "") "" else setNum.toString()
        totalText.text = if (currentExercise == "") "" else {
            val total = exerciseTotals[currentExercise] ?: 0
            val label = if (currentExercise == "walking") "steps" else "reps"
            "$total $label total"
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun connectToDevice(address: String) {
        statusText.text = "Connecting to watch..."
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = bluetoothManager.adapter.getRemoteDevice(address)
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
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
                runOnUiThread { statusText.text = "Fitness service not found on watch." }
                return
            }

            fitnessService.getCharacteristic(REP_CHAR_UUID)?.let      { enableNotification(gatt, it) }
            fitnessService.getCharacteristic(EXERCISE_CHAR_UUID)?.let { enableNotification(gatt, it) }
            fitnessService.getCharacteristic(SPEED_CHAR_UUID)?.let    { enableNotification(gatt, it) }

            runOnUiThread { statusText.text = "✓ Live — start your exercise!" }
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

                    runOnUiThread {
                        repText.text = count.toString()

                        if (setHistory.isNotEmpty()) {
                            val last = setHistory.last()
                            if (last.first == currentExercise) {
                                val prevCount = last.third
                                val diff = count - prevCount
                                setHistory[setHistory.lastIndex] =
                                    Triple(currentExercise, last.second, count)
                                val currentTotal = exerciseTotals[currentExercise] ?: 0
                                exerciseTotals[currentExercise] =
                                    (currentTotal + diff).coerceAtLeast(0)
                            }
                        }

                        refreshCurrentStats()
                    }
                }

                EXERCISE_CHAR_UUID -> {
                    val exercise = String(
                        characteristic.value ?: byteArrayOf(), Charsets.UTF_8).trim()

                    runOnUiThread {
                        android.util.Log.d("EXERCISE_DEBUG", "Received exercise: '$exercise'")

                        when (exercise.lowercase().trim()) {
                            "idle" -> {
                                val finalReps = repText.text.toString().toIntOrNull() ?: 0

                                if (finalReps == 0 && setHistory.isNotEmpty()) {
                                    val last = setHistory.last()
                                    if (last.first == currentExercise) {
                                        setHistory.removeAt(setHistory.lastIndex)
                                        val currentSets = exerciseSetCount[currentExercise] ?: 0
                                        if (currentSets > 0)
                                            exerciseSetCount[currentExercise] = currentSets - 1
                                        if ((exerciseSetCount[currentExercise] ?: 0) == 0) {
                                            exerciseSetCount.remove(currentExercise)
                                            exerciseTotals.remove(currentExercise)
                                        }
                                    }
                                }

                                wasExercising             = false
                                currentExercise           = ""
                                currentVideoResId         = 0
                                exerciseText.text         = "—"
                                countLabel.text           = "Reps"
                                repText.text              = "0"
                                speedText.text            = ""
                                coachText.text            = ""
                                statusText.text           = "✓ Set done! Rest and go again."
                                videoContainer.visibility = View.GONE
                                exerciseVideo.stopPlayback()
                                refreshCurrentStats()
                            }
                            else -> {
                                if (!wasExercising) {
                                    wasExercising = true

                                    val prevSets = exerciseSetCount[exercise] ?: 0
                                    val newSetNum = prevSets + 1
                                    exerciseSetCount[exercise] = newSetNum
                                    setHistory.add(Triple(exercise, newSetNum, 0))

                                    if (!exerciseTotals.containsKey(exercise)) {
                                        exerciseTotals[exercise] = 0
                                    }
                                }

                                currentExercise       = exercise
                                exerciseText.text     = exercise.replace("_", " ").uppercase()
                                countLabel.text       = if (exercise.lowercase() == "walking") "Steps" else "Reps"
                                statusText.text       = "✓ Live — keep going!"
                                showExerciseVideo(exercise)
                                refreshCurrentStats()
                            }
                        }
                    }
                }

                SPEED_CHAR_UUID -> {
                    val speed = String(
                        characteristic.value ?: byteArrayOf(), Charsets.UTF_8).trim()

                    val currentReps = repText.text.toString().toIntOrNull() ?: 0

                    runOnUiThread {
                        if (currentExercise.lowercase() == "walking") return@runOnUiThread

                        when (speed) {
                            "fast" -> {
                                speedText.setTextColor(
                                    android.graphics.Color.parseColor("#34C759"))
                                speedText.text = "Fast"
                                coachText.setTextColor(
                                    android.graphics.Color.parseColor("#34C759"))
                                coachText.text = "Try adding weights"
                            }
                            "slow" -> {
                                speedText.setTextColor(
                                    android.graphics.Color.parseColor("#FF3B30"))
                                speedText.text = "Slow"
                                if (currentReps < 6) {
                                    coachText.setTextColor(
                                        android.graphics.Color.parseColor("#FF3B30"))
                                    coachText.text = "Try less weight"
                                } else {
                                    coachText.text = ""
                                }
                            }
                            "normal" -> {
                                speedText.setTextColor(
                                    android.graphics.Color.parseColor("#FF9500"))
                                speedText.text = "Normal"
                                coachText.text = ""
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun disconnectAndGoBack() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    @SuppressWarnings("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        exerciseVideo.stopPlayback()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}