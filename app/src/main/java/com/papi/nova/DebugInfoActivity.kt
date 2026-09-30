package com.papi.nova

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
import android.widget.TextView
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaFocusReturn
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.novaSurfaces
import com.papi.nova.utils.DeviceUtils
import com.papi.nova.utils.UiHelper
import java.util.Locale

@Suppress("DEPRECATION")
class DebugInfoActivity : NovaActivity(), View.OnClickListener {
    private lateinit var gamepadInfoText: TextView
    private var vibrator: Vibrator? = null
    private lateinit var vibratorButton: Button
    private val inputDevices = ArrayList<InputDevice>()
    private var onlineVibrator: Vibrator? = null
    private lateinit var amplitudeButton: Button
    private var simulatedAmplitude = DebugInfoPages.DEFAULT_AMPLITUDE

    // What each list marks as current: the type this device ran last, the gamepad chosen last,
    // and the type each gamepad ran last.
    private var deviceVibration: DebugVibration? = null
    private var chosenGamepadId: Int? = null
    private val gamepadVibrations = HashMap<Int, DebugVibration>()

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_axitest)
        UiHelper.padContentForSystemBars(this)

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
            R.id.bt_vibrator_cancle -> cancelRumble()
            R.id.bt_vibrator -> showDeviceVibrationPage(view)
            R.id.bt_vibrator_gamepad -> showGamepadRumblePages(view)
            R.id.bt_update_gamepad -> updateGamePad()
            R.id.bt_vibrator_value -> showAmplitudePage(view)
        }
    }

    /** The amplitude as a Slider page: Save sets it, and B leaves it as it was. */
    private fun showAmplitudePage(opener: View) {
        novaSurfaces.open(
            DebugInfoPages.amplitude(this, simulatedAmplitude) { amplitude ->
                simulatedAmplitude = amplitude
                showSimulateAmplitude()
            },
            returnFocus = NovaFocusReturn.View(opener),
        )
    }

    private fun showDeviceVibrationPage(opener: View) {
        novaSurfaces.open(
            DebugInfoPages.vibration(
                context = this,
                key = DebugInfoPages.DEVICE_VIBRATION_PAGE,
                title = getString(R.string.debug_info_device_vibration_title),
                current = deviceVibration,
                amplitude = simulatedAmplitude,
            ) { type ->
                deviceVibration = type
                when (type) {
                    DebugVibration.Simple -> vibrator?.vibrate(DebugInfoPages.SIMPLE_VIBRATION_MILLIS)
                    DebugVibration.Continuous -> vibrator?.let(::rumble)
                }
            },
            returnFocus = NovaFocusReturn.View(opener),
        )
    }

    private fun showGamepadRumblePages(opener: View) {
        if (inputDevices.isEmpty()) {
            // Said on a page in the panel the gamepads open in, and B goes back to the button: a
            // Toast floated over the screen and was gone before it could be read (audit X2).
            novaSurfaces.open(
                NovaCommonPage.Notice(
                    key = DebugInfoPages.NO_GAMEPAD_PAGE,
                    title = getString(R.string.debug_info_test_gamepad_rumble),
                    message = getString(R.string.debug_info_no_gamepad_detected),
                    closeLabel = getString(R.string.nova_panel_close),
                ),
                returnFocus = NovaFocusReturn.View(opener),
            )
            return
        }
        showGamepadRumblePages(inputDevices.map(::debugGamepad), opener, picked = null)
    }

    /**
     * The gamepad list, with [picked]'s vibration types over it when a gamepad has been picked.
     *
     * Picking a gamepad leaves the list, as every Choice page does. The pick puts the list straight
     * back, marked with that gamepad, under its types, in the same frame, so the panel never closes:
     * picking a type returns to the list, and so does B.
     */
    internal fun showGamepadRumblePages(gamepads: List<DebugGamepad>, opener: View, picked: DebugGamepad?) {
        val surfaces = novaSurfaces
        surfaces.open(
            DebugInfoPages.gamepads(this, gamepads, current = chosenGamepadId) { gamepad ->
                chosenGamepadId = gamepad.id
                showGamepadRumblePages(gamepads, opener, picked = gamepad)
            },
            returnFocus = NovaFocusReturn.View(opener),
        )
        if (picked == null) return
        surfaces.present(
            DebugInfoPages.vibration(
                context = this,
                key = DebugInfoPages.GAMEPAD_VIBRATION_PAGE,
                title = picked.name,
                current = gamepadVibrations[picked.id],
                amplitude = simulatedAmplitude,
            ) { type ->
                gamepadVibrations[picked.id] = type
                inputDevices.firstOrNull { it.id == picked.id }?.let { rumbleGamepad(it.vibrator, type) }
            },
        )
    }

    private fun rumbleGamepad(selectedVibrator: Vibrator, type: DebugVibration) {
        when (type) {
            DebugVibration.Simple -> selectedVibrator.vibrate(DebugInfoPages.SIMPLE_VIBRATION_MILLIS)
            DebugVibration.Continuous -> {
                cancelRumble()
                onlineVibrator = selectedVibrator
                rumble(selectedVibrator)
            }
        }
    }

    private fun debugGamepad(device: InputDevice) = DebugGamepad(
        id = device.id,
        name = device.name,
        vidPid = "%04x_%04x".format(Locale.ROOT, device.vendorId, device.productId),
        hasVibrator = device.vibrator.hasVibrator(),
    )

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

/** What a vibration test runs: one short buzz, or a rumble at the set amplitude until it is stopped. */
internal enum class DebugVibration { Simple, Continuous }

/** A gamepad the rumble test lists; one with no vibrator is shown with the reason and cannot be picked. */
internal data class DebugGamepad(val id: Int, val name: String, val vidPid: String, val hasVibrator: Boolean)

/** The debug screen's panel pages: the amplitude, the vibration types and the gamepads. */
internal object DebugInfoPages {
    const val DEFAULT_AMPLITUDE = 220
    const val SIMPLE_VIBRATION_MILLIS = 1000L

    /** A vibration amplitude runs from off to full strength. */
    val AmplitudeRange = 0..255

    /** Left and Right move the amplitude five at a time; the page's field takes any exact value. */
    const val AMPLITUDE_STEP = 5

    const val AMPLITUDE_PAGE = "debug-amplitude"
    const val DEVICE_VIBRATION_PAGE = "debug-device-vibration"
    const val GAMEPADS_PAGE = "debug-gamepads"

    /** Said in place of the gamepad list when none is connected. */
    const val NO_GAMEPAD_PAGE = "debug-no-gamepad"
    const val GAMEPAD_VIBRATION_PAGE = "debug-gamepad-vibration"

    fun amplitude(context: Context, value: Int, onSave: (Int) -> Unit) = NovaCommonPage.Slider(
        key = AMPLITUDE_PAGE,
        title = context.getString(R.string.debug_info_amplitude_title),
        value = value,
        range = AmplitudeRange,
        step = AMPLITUDE_STEP,
        format = { context.getString(R.string.debug_info_amplitude_value, it, AmplitudeRange.last) },
        onSave = onSave,
    )

    fun vibration(
        context: Context,
        key: String,
        title: String,
        current: DebugVibration?,
        amplitude: Int,
        onChoose: (DebugVibration) -> Unit,
    ) = NovaCommonPage.Choice(
        key = key,
        title = title,
        options = listOf(
            NovaOption(DebugVibration.Simple, context.getString(R.string.debug_info_simple_vibration)),
            NovaOption(
                DebugVibration.Continuous,
                context.getString(R.string.debug_info_continuous_hd_vibration),
                caption = context.getString(R.string.debug_info_continuous_caption, amplitude),
            ),
        ),
        current = current,
        onChoose = onChoose,
    )

    fun gamepads(
        context: Context,
        gamepads: List<DebugGamepad>,
        current: Int?,
        onChoose: (DebugGamepad) -> Unit,
    ) = NovaCommonPage.Choice(
        key = GAMEPADS_PAGE,
        title = context.getString(R.string.debug_info_test_gamepad_rumble),
        options = gamepads.map { gamepad ->
            NovaOption(
                value = gamepad.id,
                label = gamepad.name,
                caption = context.getString(R.string.debug_info_vid_pid) + gamepad.vidPid,
                disabledReason = if (gamepad.hasVibrator) null else context.getString(R.string.debug_info_no_vibrator),
            )
        },
        current = current,
        onChoose = { id -> gamepads.firstOrNull { it.id == id }?.let(onChoose) },
    )
}
