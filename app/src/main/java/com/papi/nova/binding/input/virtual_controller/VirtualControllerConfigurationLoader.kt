package com.papi.nova.binding.input.virtual_controller

import android.app.Activity
import android.content.Context
import com.papi.nova.R
import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.preferences.PreferenceConfiguration
import org.json.JSONException
import org.json.JSONObject

object VirtualControllerConfigurationLoader {
    const val OSC_PREFERENCE = "OSC"
    private const val OSC_PREFERENCE_COMPACT_HANDHELD = "OSC_compact_handheld"

    private fun screenScale(units: Int, height: Int): Int = (height.toFloat() / 72f * units).toInt()

    private fun createDigitalPad(controller: VirtualController, context: Context): DigitalPad {
        val digitalPad = DigitalPad(controller, context)
        digitalPad.addDigitalPadListener(object : DigitalPad.DigitalPadListener {
            override fun onDirectionChange(direction: Int) {
                val inputContext = controller.getControllerInputContext()

                inputContext.inputMap = if ((direction and DigitalPad.DIGITAL_PAD_DIRECTION_LEFT) != 0) {
                    inputContext.inputMap or ControllerPacket.LEFT_FLAG
                } else {
                    inputContext.inputMap and ControllerPacket.LEFT_FLAG.inv()
                }
                inputContext.inputMap = if ((direction and DigitalPad.DIGITAL_PAD_DIRECTION_RIGHT) != 0) {
                    inputContext.inputMap or ControllerPacket.RIGHT_FLAG
                } else {
                    inputContext.inputMap and ControllerPacket.RIGHT_FLAG.inv()
                }
                inputContext.inputMap = if ((direction and DigitalPad.DIGITAL_PAD_DIRECTION_UP) != 0) {
                    inputContext.inputMap or ControllerPacket.UP_FLAG
                } else {
                    inputContext.inputMap and ControllerPacket.UP_FLAG.inv()
                }
                inputContext.inputMap = if ((direction and DigitalPad.DIGITAL_PAD_DIRECTION_DOWN) != 0) {
                    inputContext.inputMap or ControllerPacket.DOWN_FLAG
                } else {
                    inputContext.inputMap and ControllerPacket.DOWN_FLAG.inv()
                }

                controller.sendControllerInputContext(10, 0x22)
            }
        })
        return digitalPad
    }

    private fun createDigitalButton(
        elementId: Int,
        keyShort: Int,
        keyLong: Int,
        layer: Int,
        text: String,
        icon: Int,
        iconPress: Int,
        controller: VirtualController,
        context: Context
    ): DigitalButton {
        val button = DigitalButton(controller, elementId, layer, context)
        button.setText(text)
        button.setIcon(icon)
        button.setIconPress(iconPress)
        button.addDigitalButtonListener(object : DigitalButton.DigitalButtonListener {
            override fun onClick() {
                val inputContext = controller.getControllerInputContext()
                inputContext.inputMap = inputContext.inputMap or keyShort
                controller.sendControllerInputContext()
            }

            override fun onLongClick() {
                val inputContext = controller.getControllerInputContext()
                inputContext.inputMap = inputContext.inputMap or keyLong
                controller.sendControllerInputContext()
            }

            override fun onRelease() {
                val inputContext = controller.getControllerInputContext()
                inputContext.inputMap = inputContext.inputMap and keyShort.inv()
                inputContext.inputMap = inputContext.inputMap and keyLong.inv()
                controller.sendControllerInputContext()
            }
        })
        return button
    }

    private fun createLeftTrigger(
        layer: Int,
        text: String,
        icon: Int,
        iconPress: Int,
        controller: VirtualController,
        context: Context
    ): DigitalButton {
        val button = LeftTrigger(controller, layer, context)
        button.setText(text)
        button.setIcon(icon)
        button.setIconPress(iconPress)
        return button
    }

    private fun createRightTrigger(
        layer: Int,
        text: String,
        icon: Int,
        iconPress: Int,
        controller: VirtualController,
        context: Context
    ): DigitalButton {
        val button = RightTrigger(controller, layer, context)
        button.setText(text)
        button.setIcon(icon)
        button.setIconPress(iconPress)
        return button
    }

    private fun createLeftStick(controller: VirtualController, context: Context): AnalogStick =
        LeftAnalogStick(controller, context)

    private fun createRightStick(controller: VirtualController, context: Context): AnalogStick =
        RightAnalogStick(controller, context)

    private fun createLeftStick2(controller: VirtualController, context: Context): AnalogStickFree =
        LeftAnalogStickFree(controller, context)

    private fun createRightStick2(controller: VirtualController, context: Context): AnalogStickFree =
        RightAnalogStickFree(controller, context)

    private const val TRIGGER_L_BASE_X = 1
    private const val TRIGGER_R_BASE_X = 92
    private const val TRIGGER_DISTANCE = 23
    private const val TRIGGER_BASE_Y = 31
    private const val TRIGGER_WIDTH = 12
    private const val TRIGGER_HEIGHT = 9

    private const val BUTTON_BASE_X = 106
    private const val BUTTON_BASE_Y = 1
    private const val BUTTON_SIZE = 10

    private const val DPAD_BASE_X = 4
    private const val DPAD_BASE_Y = 41
    private const val DPAD_SIZE = 30

    private const val ANALOG_L_BASE_X = 6
    private const val ANALOG_L_BASE_Y = 4
    private const val ANALOG_R_BASE_X = 98
    private const val ANALOG_R_BASE_Y = 42
    private const val ANALOG_SIZE = 26

    private const val L3_R3_BASE_Y = 60

    private const val START_X = 83
    private const val BACK_X = 34
    private const val START_BACK_Y = 64
    private const val START_BACK_WIDTH = 12
    private const val START_BACK_HEIGHT = 7

    private const val GUIDE_X = START_X - BACK_X
    private const val GUIDE_Y = START_BACK_Y

    private const val COMPACT_TRIGGER_LEFT_X = 2
    private const val COMPACT_TRIGGER_RIGHT_X = 114
    private const val COMPACT_TRIGGER_SHOULDER_LEFT_X = 15
    private const val COMPACT_TRIGGER_SHOULDER_RIGHT_X = 101
    private const val COMPACT_TRIGGER_BASE_Y = 2
    private const val COMPACT_TRIGGER_WIDTH = 11
    private const val COMPACT_TRIGGER_HEIGHT = 8

    private const val COMPACT_BUTTON_BASE_X = 105
    private const val COMPACT_BUTTON_BASE_Y = 22
    private const val COMPACT_BUTTON_SIZE = 10

    private const val COMPACT_DPAD_X = 2
    private const val COMPACT_DPAD_Y = 24
    private const val COMPACT_DPAD_SIZE = 22

    private const val COMPACT_LEFT_STICK_X = 4
    private const val COMPACT_LEFT_STICK_Y = 44
    private const val COMPACT_RIGHT_STICK_X = 96
    private const val COMPACT_RIGHT_STICK_Y = 44
    private const val COMPACT_STICK_SIZE = 24

    private const val COMPACT_BACK_X = 46
    private const val COMPACT_GUIDE_X = 59
    private const val COMPACT_START_X = 72
    private const val COMPACT_TOP_BUTTON_Y = 4
    private const val COMPACT_TOP_BUTTON_WIDTH = 10
    private const val COMPACT_TOP_BUTTON_HEIGHT = 6

    private const val COMPACT_L3_X = 35
    private const val COMPACT_R3_X = 83
    private const val COMPACT_BOTTOM_BUTTON_Y = 59
    private const val COMPACT_BOTTOM_BUTTON_WIDTH = 10
    private const val COMPACT_BOTTOM_BUTTON_HEIGHT = 6
    private const val COMPACT_TRACKPAD_X = 54
    private const val COMPACT_TRACKPAD_Y = 58
    private const val COMPACT_TRACKPAD_WIDTH = 20
    private const val COMPACT_TRACKPAD_HEIGHT = 8

    private fun addAnalogSticks(
        controller: VirtualController,
        context: Context,
        config: PreferenceConfiguration,
        height: Int,
        rightDisplacement: Int,
        leftStickX: Int,
        leftStickY: Int,
        rightStickX: Int,
        rightStickY: Int,
        stickSize: Int
    ) {
        if (config.enableNewAnalogStick) {
            controller.addElement(createLeftStick2(controller, context), screenScale(leftStickX, height), screenScale(leftStickY, height), screenScale(stickSize, height), screenScale(stickSize, height))
            controller.addElement(createRightStick2(controller, context), screenScale(rightStickX, height) + rightDisplacement, screenScale(rightStickY, height), screenScale(stickSize, height), screenScale(stickSize, height))
        } else {
            controller.addElement(createLeftStick(controller, context), screenScale(leftStickX, height), screenScale(leftStickY, height), screenScale(stickSize, height), screenScale(stickSize, height))
            controller.addElement(createRightStick(controller, context), screenScale(rightStickX, height) + rightDisplacement, screenScale(rightStickY, height), screenScale(stickSize, height), screenScale(stickSize, height))
        }
    }

    private fun createFullConsoleLayout(
        controller: VirtualController,
        context: Context,
        config: PreferenceConfiguration,
        height: Int,
        rightDisplacement: Int
    ) {
        controller.addElement(createDigitalPad(controller, context), screenScale(DPAD_BASE_X, height), screenScale(DPAD_BASE_Y, height), screenScale(DPAD_SIZE, height), screenScale(DPAD_SIZE, height))

        controller.addElement(createDigitalButton(VirtualControllerElement.EID_A, if (!config.flipFaceButtons) ControllerPacket.A_FLAG else ControllerPacket.B_FLAG, 0, 1, if (!config.flipFaceButtons) "A" else "B", R.drawable.facebutton_a, R.drawable.facebutton_a_press, controller, context), screenScale(BUTTON_BASE_X, height) + rightDisplacement, screenScale(BUTTON_BASE_Y + 2 * BUTTON_SIZE, height), screenScale(BUTTON_SIZE, height), screenScale(BUTTON_SIZE, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_B, if (config.flipFaceButtons) ControllerPacket.A_FLAG else ControllerPacket.B_FLAG, 0, 1, if (config.flipFaceButtons) "A" else "B", R.drawable.facebutton_b, R.drawable.facebutton_b_press, controller, context), screenScale(BUTTON_BASE_X + BUTTON_SIZE, height) + rightDisplacement, screenScale(BUTTON_BASE_Y + BUTTON_SIZE, height), screenScale(BUTTON_SIZE, height), screenScale(BUTTON_SIZE, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_X, if (!config.flipFaceButtons) ControllerPacket.X_FLAG else ControllerPacket.Y_FLAG, 0, 1, if (!config.flipFaceButtons) "X" else "Y", R.drawable.facebutton_x, R.drawable.facebutton_x_press, controller, context), screenScale(BUTTON_BASE_X - BUTTON_SIZE, height) + rightDisplacement, screenScale(BUTTON_BASE_Y + BUTTON_SIZE, height), screenScale(BUTTON_SIZE, height), screenScale(BUTTON_SIZE, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_Y, if (config.flipFaceButtons) ControllerPacket.X_FLAG else ControllerPacket.Y_FLAG, 0, 1, if (config.flipFaceButtons) "X" else "Y", R.drawable.facebutton_y, R.drawable.facebutton_y_press, controller, context), screenScale(BUTTON_BASE_X, height) + rightDisplacement, screenScale(BUTTON_BASE_Y, height), screenScale(BUTTON_SIZE, height), screenScale(BUTTON_SIZE, height))

        controller.addElement(createLeftTrigger(1, "LT", R.drawable.facebutton_zl, R.drawable.facebutton_zl_press, controller, context), screenScale(TRIGGER_L_BASE_X, height), screenScale(TRIGGER_BASE_Y, height), screenScale(TRIGGER_WIDTH, height), screenScale(TRIGGER_HEIGHT, height))
        controller.addElement(createRightTrigger(1, "RT", R.drawable.facebutton_zr, R.drawable.facebutton_zr_press, controller, context), screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement, screenScale(TRIGGER_BASE_Y, height), screenScale(TRIGGER_WIDTH, height), screenScale(TRIGGER_HEIGHT, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_LB, ControllerPacket.LB_FLAG, 0, 1, "LB", R.drawable.facebutton_l, R.drawable.facebutton_l_press, controller, context), screenScale(TRIGGER_L_BASE_X + TRIGGER_DISTANCE, height), screenScale(TRIGGER_BASE_Y, height), screenScale(TRIGGER_WIDTH, height), screenScale(TRIGGER_HEIGHT, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_RB, ControllerPacket.RB_FLAG, 0, 1, "RB", R.drawable.facebutton_r, R.drawable.facebutton_r_press, controller, context), screenScale(TRIGGER_R_BASE_X, height) + rightDisplacement, screenScale(TRIGGER_BASE_Y, height), screenScale(TRIGGER_WIDTH, height), screenScale(TRIGGER_HEIGHT, height))

        addAnalogSticks(controller, context, config, height, rightDisplacement, ANALOG_L_BASE_X, ANALOG_L_BASE_Y, ANALOG_R_BASE_X, ANALOG_R_BASE_Y, ANALOG_SIZE)

        controller.addElement(createDigitalButton(VirtualControllerElement.EID_BACK, ControllerPacket.BACK_FLAG, 0, 2, "BACK", R.drawable.facebutton_minus, R.drawable.facebutton_minus_press, controller, context), screenScale(BACK_X, height), screenScale(START_BACK_Y, height), screenScale(START_BACK_WIDTH, height), screenScale(START_BACK_HEIGHT, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_START, ControllerPacket.PLAY_FLAG, 0, 3, "START", R.drawable.facebutton_plus, R.drawable.facebutton_plus_press, controller, context), screenScale(START_X, height) + rightDisplacement, screenScale(START_BACK_Y, height), screenScale(START_BACK_WIDTH, height), screenScale(START_BACK_HEIGHT, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_LSB, ControllerPacket.LS_CLK_FLAG, 0, 1, "L3", R.drawable.facebutton_l3, R.drawable.facebutton_l3_press, controller, context), screenScale(TRIGGER_L_BASE_X, height), screenScale(L3_R3_BASE_Y, height), screenScale(TRIGGER_WIDTH, height), screenScale(TRIGGER_HEIGHT, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_RSB, ControllerPacket.RS_CLK_FLAG, 0, 1, "R3", R.drawable.facebutton_r3, R.drawable.facebutton_r3_press, controller, context), screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement, screenScale(L3_R3_BASE_Y, height), screenScale(TRIGGER_WIDTH, height), screenScale(TRIGGER_HEIGHT, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_TOUCHPAD, ControllerPacket.TOUCHPAD_FLAG, 0, 1, "Trackpad", R.drawable.facebutton_touchpad_press, R.drawable.facebutton_touchpad, controller, context), screenScale(50, height), screenScale(50, height), screenScale(20, height), screenScale(12, height))

        if (config.showGuideButton) {
            controller.addElement(createDigitalButton(VirtualControllerElement.EID_GDB, ControllerPacket.SPECIAL_BUTTON_FLAG, 0, 1, "GUIDE", -1, -1, controller, context), screenScale(GUIDE_X, height) + rightDisplacement, screenScale(GUIDE_Y, height), screenScale(START_BACK_WIDTH, height), screenScale(START_BACK_HEIGHT, height))
        }
    }

    private fun createCompactHandheldLayout(
        controller: VirtualController,
        context: Context,
        config: PreferenceConfiguration,
        height: Int,
        rightDisplacement: Int
    ) {
        controller.addElement(createLeftTrigger(1, "LT", R.drawable.facebutton_zl, R.drawable.facebutton_zl_press, controller, context), screenScale(COMPACT_TRIGGER_LEFT_X, height), screenScale(COMPACT_TRIGGER_BASE_Y, height), screenScale(COMPACT_TRIGGER_WIDTH, height), screenScale(COMPACT_TRIGGER_HEIGHT, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_LB, ControllerPacket.LB_FLAG, 0, 1, "LB", R.drawable.facebutton_l, R.drawable.facebutton_l_press, controller, context), screenScale(COMPACT_TRIGGER_SHOULDER_LEFT_X, height), screenScale(COMPACT_TRIGGER_BASE_Y, height), screenScale(COMPACT_TRIGGER_WIDTH, height), screenScale(COMPACT_TRIGGER_HEIGHT, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_RB, ControllerPacket.RB_FLAG, 0, 1, "RB", R.drawable.facebutton_r, R.drawable.facebutton_r_press, controller, context), screenScale(COMPACT_TRIGGER_SHOULDER_RIGHT_X, height) + rightDisplacement, screenScale(COMPACT_TRIGGER_BASE_Y, height), screenScale(COMPACT_TRIGGER_WIDTH, height), screenScale(COMPACT_TRIGGER_HEIGHT, height))
        controller.addElement(createRightTrigger(1, "RT", R.drawable.facebutton_zr, R.drawable.facebutton_zr_press, controller, context), screenScale(COMPACT_TRIGGER_RIGHT_X, height) + rightDisplacement, screenScale(COMPACT_TRIGGER_BASE_Y, height), screenScale(COMPACT_TRIGGER_WIDTH, height), screenScale(COMPACT_TRIGGER_HEIGHT, height))

        controller.addElement(createDigitalButton(VirtualControllerElement.EID_BACK, ControllerPacket.BACK_FLAG, 0, 2, "BACK", R.drawable.facebutton_minus, R.drawable.facebutton_minus_press, controller, context), screenScale(COMPACT_BACK_X, height), screenScale(COMPACT_TOP_BUTTON_Y, height), screenScale(COMPACT_TOP_BUTTON_WIDTH, height), screenScale(COMPACT_TOP_BUTTON_HEIGHT, height))
        if (config.showGuideButton) {
            controller.addElement(createDigitalButton(VirtualControllerElement.EID_GDB, ControllerPacket.SPECIAL_BUTTON_FLAG, 0, 1, "GUIDE", -1, -1, controller, context), screenScale(COMPACT_GUIDE_X, height), screenScale(COMPACT_TOP_BUTTON_Y, height), screenScale(COMPACT_TOP_BUTTON_WIDTH, height), screenScale(COMPACT_TOP_BUTTON_HEIGHT, height))
        }
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_START, ControllerPacket.PLAY_FLAG, 0, 3, "START", R.drawable.facebutton_plus, R.drawable.facebutton_plus_press, controller, context), screenScale(COMPACT_START_X, height), screenScale(COMPACT_TOP_BUTTON_Y, height), screenScale(COMPACT_TOP_BUTTON_WIDTH, height), screenScale(COMPACT_TOP_BUTTON_HEIGHT, height))

        controller.addElement(createDigitalPad(controller, context), screenScale(COMPACT_DPAD_X, height), screenScale(COMPACT_DPAD_Y, height), screenScale(COMPACT_DPAD_SIZE, height), screenScale(COMPACT_DPAD_SIZE, height))

        addAnalogSticks(controller, context, config, height, rightDisplacement, COMPACT_LEFT_STICK_X, COMPACT_LEFT_STICK_Y, COMPACT_RIGHT_STICK_X, COMPACT_RIGHT_STICK_Y, COMPACT_STICK_SIZE)

        controller.addElement(createDigitalButton(VirtualControllerElement.EID_A, if (!config.flipFaceButtons) ControllerPacket.A_FLAG else ControllerPacket.B_FLAG, 0, 1, if (!config.flipFaceButtons) "A" else "B", R.drawable.facebutton_a, R.drawable.facebutton_a_press, controller, context), screenScale(COMPACT_BUTTON_BASE_X, height) + rightDisplacement, screenScale(COMPACT_BUTTON_BASE_Y + 2 * COMPACT_BUTTON_SIZE, height), screenScale(COMPACT_BUTTON_SIZE, height), screenScale(COMPACT_BUTTON_SIZE, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_B, if (config.flipFaceButtons) ControllerPacket.A_FLAG else ControllerPacket.B_FLAG, 0, 1, if (config.flipFaceButtons) "A" else "B", R.drawable.facebutton_b, R.drawable.facebutton_b_press, controller, context), screenScale(COMPACT_BUTTON_BASE_X + COMPACT_BUTTON_SIZE, height) + rightDisplacement, screenScale(COMPACT_BUTTON_BASE_Y + COMPACT_BUTTON_SIZE, height), screenScale(COMPACT_BUTTON_SIZE, height), screenScale(COMPACT_BUTTON_SIZE, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_X, if (!config.flipFaceButtons) ControllerPacket.X_FLAG else ControllerPacket.Y_FLAG, 0, 1, if (!config.flipFaceButtons) "X" else "Y", R.drawable.facebutton_x, R.drawable.facebutton_x_press, controller, context), screenScale(COMPACT_BUTTON_BASE_X - COMPACT_BUTTON_SIZE, height) + rightDisplacement, screenScale(COMPACT_BUTTON_BASE_Y + COMPACT_BUTTON_SIZE, height), screenScale(COMPACT_BUTTON_SIZE, height), screenScale(COMPACT_BUTTON_SIZE, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_Y, if (config.flipFaceButtons) ControllerPacket.X_FLAG else ControllerPacket.Y_FLAG, 0, 1, if (config.flipFaceButtons) "X" else "Y", R.drawable.facebutton_y, R.drawable.facebutton_y_press, controller, context), screenScale(COMPACT_BUTTON_BASE_X, height) + rightDisplacement, screenScale(COMPACT_BUTTON_BASE_Y, height), screenScale(COMPACT_BUTTON_SIZE, height), screenScale(COMPACT_BUTTON_SIZE, height))

        controller.addElement(createDigitalButton(VirtualControllerElement.EID_LSB, ControllerPacket.LS_CLK_FLAG, 0, 1, "L3", R.drawable.facebutton_l3, R.drawable.facebutton_l3_press, controller, context), screenScale(COMPACT_L3_X, height), screenScale(COMPACT_BOTTOM_BUTTON_Y, height), screenScale(COMPACT_BOTTOM_BUTTON_WIDTH, height), screenScale(COMPACT_BOTTOM_BUTTON_HEIGHT, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_TOUCHPAD, ControllerPacket.TOUCHPAD_FLAG, 0, 1, "Trackpad", R.drawable.facebutton_touchpad_press, R.drawable.facebutton_touchpad, controller, context), screenScale(COMPACT_TRACKPAD_X, height), screenScale(COMPACT_TRACKPAD_Y, height), screenScale(COMPACT_TRACKPAD_WIDTH, height), screenScale(COMPACT_TRACKPAD_HEIGHT, height))
        controller.addElement(createDigitalButton(VirtualControllerElement.EID_RSB, ControllerPacket.RS_CLK_FLAG, 0, 1, "R3", R.drawable.facebutton_r3, R.drawable.facebutton_r3_press, controller, context), screenScale(COMPACT_R3_X, height), screenScale(COMPACT_BOTTOM_BUTTON_Y, height), screenScale(COMPACT_BOTTOM_BUTTON_WIDTH, height), screenScale(COMPACT_BOTTOM_BUTTON_HEIGHT, height))
    }

    @JvmStatic
    fun getOscPreferenceName(context: Context): String {
        val config = PreferenceConfiguration.readPreferences(context)
        return if (PreferenceConfiguration.ONSCREEN_CONTROLLER_LAYOUT_PRESET_COMPACT_HANDHELD == config.onscreenControllerLayoutPreset) {
            OSC_PREFERENCE_COMPACT_HANDHELD
        } else {
            OSC_PREFERENCE
        }
    }

    @JvmStatic
    fun clearProfile(context: Context) {
        context.getSharedPreferences(getOscPreferenceName(context), Activity.MODE_PRIVATE).edit().clear().apply()
    }

    @JvmStatic
    fun createDefaultLayout(controller: VirtualController, context: Context) {
        val screen = context.resources.displayMetrics
        val config = PreferenceConfiguration.readPreferences(context)
        val rightDisplacement = screen.widthPixels - screen.heightPixels * 16 / 9
        val height = screen.heightPixels

        if (!config.onlyL3R3) {
            if (PreferenceConfiguration.ONSCREEN_CONTROLLER_LAYOUT_PRESET_COMPACT_HANDHELD == config.onscreenControllerLayoutPreset) {
                createCompactHandheldLayout(controller, context, config, height, rightDisplacement)
            } else {
                createFullConsoleLayout(controller, context, config, height, rightDisplacement)
            }
        } else {
            controller.addElement(createDigitalButton(VirtualControllerElement.EID_LSB, ControllerPacket.LS_CLK_FLAG, 0, 1, "L3", -1, -1, controller, context), screenScale(TRIGGER_L_BASE_X, height), screenScale(L3_R3_BASE_Y, height), screenScale(TRIGGER_WIDTH, height), screenScale(TRIGGER_HEIGHT, height))
            controller.addElement(createDigitalButton(VirtualControllerElement.EID_RSB, ControllerPacket.RS_CLK_FLAG, 0, 1, "R3", -1, -1, controller, context), screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement, screenScale(L3_R3_BASE_Y, height), screenScale(TRIGGER_WIDTH, height), screenScale(TRIGGER_HEIGHT, height))
        }

        controller.setOpacity(config.oscOpacity)
    }

    @JvmStatic
    fun saveProfile(controller: VirtualController, context: Context) {
        val prefEditor = context.getSharedPreferences(getOscPreferenceName(context), Activity.MODE_PRIVATE).edit()

        for (element in controller.getElements()) {
            val prefKey = element.elementId.toString()
            try {
                prefEditor.putString(prefKey, element.getConfiguration().toString())
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }

        prefEditor.apply()
    }

    @JvmStatic
    fun loadFromPreferences(controller: VirtualController, context: Context) {
        val pref = context.getSharedPreferences(getOscPreferenceName(context), Activity.MODE_PRIVATE)

        for (element in controller.getElements()) {
            val prefKey = element.elementId.toString()
            val jsonConfig = pref.getString(prefKey, null)
            if (jsonConfig != null) {
                try {
                    element.loadConfiguration(JSONObject(jsonConfig))
                } catch (e: JSONException) {
                    e.printStackTrace()
                    pref.edit().remove(prefKey).apply()
                }
            }
        }
    }
}
