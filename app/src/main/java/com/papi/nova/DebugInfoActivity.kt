package com.papi.nova

import android.app.AlertDialog
import android.content.Context
import android.hardware.Sensor
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.utils.DeviceUtils

@Suppress("DEPRECATION")
class DebugInfoActivity : NovaActivity(), View.OnClickListener {
    private lateinit var gamepadInfoText: TextView
    private var vibrator: Vibrator? = null
    private lateinit var vibratorButton: Button
    private val inputDevices = ArrayList<InputDevice>()
    private var onlineVibrator: Vibrator? = null
    private lateinit var amplitudeButton: Button
    private var simulatedAmplitude = 220

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_axitest)

        gamepadInfoText = findViewById(R.id.tx_game_pad_info)
        val contentText = findViewById<TextView>(R.id.tx_content)
        vibratorButton = findViewById(R.id.bt_vibrator)
        amplitudeButton = findViewById(R.id.bt_vibrator_value)

        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val kernelVersion = System.getProperty("os.version")
        val content = StringBuilder()
            .append(getString(R.string.debug_info_android_version))
            .append(DeviceUtils.getSDKVersionName())
            .append("\t")
            .append(getString(R.string.debug_info_api_version))
            .append(Build.VERSION.SDK_INT)
            .append("\n")
            .append(getString(R.string.debug_info_kernel_version))
            .append(kernelVersion)
            .append("\n")
            .append(getString(R.string.debug_info_brand_model))
            .append(DeviceUtils.getManufacturer())
            .append("\t-\t")
            .append(DeviceUtils.getModel())
        contentText.text = content.toString()

        val hasVibrator = (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).hasVibrator()
        val vibrationContent = if (hasVibrator) {
            getString(R.string.debug_info_has_vibration_motor)
        } else {
            getString(R.string.debug_info_no_vibration_motor)
        }
        vibratorButton.text = getString(R.string.debug_info_test_device_vibration, vibrationContent)

        showSimulateAmplitude()
    }

    private fun showSimulateAmplitude() {
        amplitudeButton.text = getString(R.string.debug_info_vibration_amplitude, simulatedAmplitude)
    }

    private fun cancelRumble() {
        onlineVibrator?.cancel()
        vibrator?.cancel()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.bt_vibrator_cancle -> {
                cancelRumble()
                return
            }

            R.id.bt_vibrator -> {
                showDeviceVibrationPicker()
                return
            }

            R.id.bt_vibrator_gamepad -> {
                showGamepadVibrationPicker()
                return
            }

            R.id.bt_update_gamepad -> {
                updateGamePad()
                return
            }

            R.id.bt_vibrator_value -> {
                val seekBar = createSeekBar()
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.debug_info_set_amplitude))
                    .setView(seekBar)
                    .create()
                    .show()
            }
        }
    }

    private fun showDeviceVibrationPicker() {
        val titles = arrayOf(
            getString(R.string.debug_info_simple_vibration),
            getString(R.string.debug_info_continuous_hd_vibration),
        )
        AlertDialog.Builder(this)
            .setItems(titles) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> vibrator?.vibrate(1000)
                    1 -> vibrator?.let(::rumble)
                }
            }
            .setTitle(getString(R.string.debug_info_please_choose))
            .create()
            .show()
    }

    private fun showGamepadVibrationPicker() {
        if (inputDevices.isEmpty()) {
            Toast.makeText(this, getString(R.string.debug_info_no_gamepad_detected), Toast.LENGTH_LONG).show()
            return
        }

        val deviceNames = Array(inputDevices.size) { index -> inputDevices[index].name }
        AlertDialog.Builder(this)
            .setItems(deviceNames) { dialog, which ->
                dialog.dismiss()
                val selectedDevice = inputDevices[which]
                val selectedVibrator = selectedDevice.vibrator
                if (selectedVibrator.hasVibrator()) {
                    showGamepadVibrationModePicker(selectedVibrator)
                } else {
                    Toast.makeText(this, getString(R.string.debug_info_no_vibrator), Toast.LENGTH_SHORT).show()
                }
            }
            .setTitle(getString(R.string.debug_info_please_choose))
            .create()
            .show()
    }

    private fun showGamepadVibrationModePicker(selectedVibrator: Vibrator) {
        val titles = arrayOf(
            getString(R.string.debug_info_simple_vibration),
            getString(R.string.debug_info_continuous_hd_vibration),
        )
        AlertDialog.Builder(this)
            .setItems(titles) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> selectedVibrator.vibrate(1000)
                    1 -> {
                        cancelRumble()
                        onlineVibrator = selectedVibrator
                        rumble(selectedVibrator)
                    }
                }
            }
            .setTitle(getString(R.string.debug_info_please_choose))
            .create()
            .show()
    }

    private fun createSeekBar(): SeekBar {
        return SeekBar(this).apply {
            max = 255
            progress = simulatedAmplitude
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    simulatedAmplitude = progress
                    showSimulateAmplitude()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
    }

    private fun rumble(vibrator: Vibrator) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(1000), intArrayOf(simulatedAmplitude), 0))
        } else {
            val pwmPeriod = 20L
            val onTime = ((simulatedAmplitude / 255.0) * pwmPeriod).toLong()
            val offTime = pwmPeriod - onTime
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .build()
            vibrator.vibrate(longArrayOf(0, onTime, offTime), 0, audioAttributes)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        onlineVibrator?.cancel()
    }

    private fun updateGamePad() {
        inputDevices.clear()
        val details = StringBuilder().append("\n")
        val deviceIds = InputDevice.getDeviceIds()
        for (deviceId in deviceIds) {
            val device = InputDevice.getDevice(deviceId) ?: continue
            val sources = device.sources
            if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            ) {
                if (getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_X) != null &&
                    getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_Y) != null
                ) {
                    inputDevices.add(device)
                    appendGamepadDetails(details, device)
                }
            }
        }
        gamepadInfoText.text = getString(R.string.debug_info_number_of_gamepads) +
            inputDevices.size + "\n" + details.toString()
    }

    private fun appendGamepadDetails(details: StringBuilder, device: InputDevice) {
        details.append(getString(R.string.debug_info_name)).append(device.name).append("\n")
        details.append(getString(R.string.debug_info_sensors))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            var sensor = ""
            if (device.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
                sensor += getString(R.string.debug_info_accelerometer)
            }
            if (device.sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
                sensor += getString(R.string.debug_info_gyroscope)
            }
            if (sensor.isEmpty()) {
                details.append(getString(R.string.debug_info_no_relevant_driver))
            } else {
                details.append(sensor)
            }
            details.append("\n")
        } else {
            details.append(getString(R.string.debug_info_no_api_below_android12)).append("\n")
        }

        details
            .append(getString(R.string.debug_info_vid_pid))
            .append(device.vendorId)
            .append("_")
            .append(device.productId)
            .append("\t    [")
            .append(String.format("%04x", device.vendorId))
            .append("_")
            .append(String.format("%04x", device.productId))
            .append("]")
            .append("\n")
            .append(getString(R.string.debug_info_vibration))
            .append(
                if (device.vibrator.hasVibrator()) {
                    getString(R.string.debug_info_supported)
                } else {
                    getString(R.string.debug_info_not_supported)
                },
            )
            .append("\n")
            .append(getString(R.string.debug_info_details))
            .append("\n")
            .append(device.toString())
            .append("\n")
    }

    companion object {
        private fun getMotionRangeForJoystickAxis(device: InputDevice, axis: Int): InputDevice.MotionRange? {
            return device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
                ?: device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
        }
    }
}
