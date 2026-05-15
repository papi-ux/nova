package com.papi.nova.binding.input.virtual_controller.keyboard

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import android.view.KeyEvent
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.papi.nova.GameMenu
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.binding.input.KeyboardTranslator.getModifier
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.input.KeyboardPacket
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.utils.KeyConfigHelper
import com.papi.nova.utils.KeyMapper
import org.json.JSONException
import org.json.JSONObject
import java.lang.reflect.Field

object KeyBoardControllerConfigurationLoader {
    const val OSC_PREFERENCE = "keyboard_axi_list"
    const val OSC_PREFERENCE_VALUE = "OSC_Keyboard"

    private val MODIFIER_KEY_CODES = hashSetOf(
        KeyEvent.KEYCODE_ALT_LEFT,
        KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT,
        KeyEvent.KEYCODE_META_RIGHT
    )

    @JvmStatic
    fun isModifierKey(keyCode: Int): Boolean = MODIFIER_KEY_CODES.contains(keyCode)

    @JvmStatic
    fun screenScale(units: Int, height: Int): Int = (height.toFloat() / 72f * units).toInt()

    @JvmStatic
    fun screenScaleSwitch(result: Int, height: Int): Int = result * 72 / height

    @JvmStatic
    fun createDiaitalPadButton(
        elementId: String,
        keyCodeLeft: Int,
        keyCodeRight: Int,
        keyCodeUp: Int,
        keyCodeDown: Int,
        controller: KeyBoardController,
        context: Context
    ): KeyboardDigitalPadButton {
        val button = KeyboardDigitalPadButton(controller, context, elementId)
        button.addDigitalPadListener(object : KeyboardDigitalPadButton.DigitalPadListener {
            override fun onDirectionChange(direction: Int) {
                sendDpadKey(controller, direction, KeyboardDigitalPadButton.DIGITAL_PAD_DIRECTION_LEFT, keyCodeLeft)
                sendDpadKey(controller, direction, KeyboardDigitalPadButton.DIGITAL_PAD_DIRECTION_RIGHT, keyCodeRight)
                sendDpadKey(controller, direction, KeyboardDigitalPadButton.DIGITAL_PAD_DIRECTION_UP, keyCodeUp)
                sendDpadKey(controller, direction, KeyboardDigitalPadButton.DIGITAL_PAD_DIRECTION_DOWN, keyCodeDown)
            }
        })
        return button
    }

    private fun sendDpadKey(controller: KeyBoardController, direction: Int, directionMask: Int, keyCode: Int) {
        val event = KeyEvent(
            if ((direction and directionMask) != 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode
        )
        event.source = 3
        controller.sendKeyEvent(event)
    }

    @JvmStatic
    fun createKeyBoardAnalogStickButton(
        controller: KeyBoardController,
        elementId: String,
        context: Context,
        keylist: IntArray
    ): KeyBoardAnalogStickButton {
        val analogStick = KeyBoardAnalogStickButton(controller, elementId, context, keylist)
        analogStick.setListener(object : KeyBoardAnalogStickButton.KeyBoardAnalogStickListener {
            override fun onkeyEvent(code: Int, isPress: Boolean) {
                val keyEvent = KeyEvent(if (isPress) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)
                keyEvent.source = 2
                controller.sendKeyEvent(keyEvent)
            }
        })
        return analogStick
    }

    private fun createKeyBoardAnalogStickButton2(
        controller: KeyBoardController,
        elementId: String,
        context: Context,
        keylist: IntArray
    ): KeyBoardAnalogStickButtonFree {
        val analogStick = KeyBoardAnalogStickButtonFree(controller, elementId, context, keylist)
        analogStick.setListener(object : KeyBoardAnalogStickButtonFree.KeyBoardAnalogStickListener {
            override fun onkeyEvent(code: Int, isPress: Boolean) {
                val keyEvent = KeyEvent(if (isPress) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)
                keyEvent.source = 2
                controller.sendKeyEvent(keyEvent)
            }
        })
        return analogStick
    }

    @JvmStatic
    fun createDigitalButton(
        elementId: String,
        keyShort: Int,
        type: Int,
        layer: Int,
        text: String,
        icon: Int,
        sticky: Boolean,
        controller: KeyBoardController,
        context: Context
    ): KeyBoardDigitalButton {
        val button = KeyBoardDigitalButton(controller, elementId, layer, context)
        button.setText(text)
        button.setIcon(icon)

        if (elementId.startsWith("m_s_") || elementId.startsWith("key_s_")) {
            button.setEnableSwitchDown(true)
        }

        if (sticky) {
            button.addDigitalButtonListener(object : KeyBoardDigitalButton.DigitalButtonListener {
                override fun onClick() {
                    if (button.isSticky()) {
                        button.setSticky(false)
                        return
                    }
                    val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyShort)
                    keyEvent.source = type
                    controller.sendKeyEvent(keyEvent)
                }

                override fun onLongClick() {
                    button.setSticky(true)
                    controller.vibrate(-1)
                }

                override fun onRelease() {
                    if (button.isSticky()) return
                    val keyEvent = KeyEvent(KeyEvent.ACTION_UP, keyShort)
                    keyEvent.source = type
                    controller.sendKeyEvent(keyEvent)
                }
            })
        } else {
            button.addDigitalButtonListener(object : KeyBoardDigitalButton.DigitalButtonListener {
                override fun onClick() {
                    val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyShort)
                    keyEvent.source = type
                    controller.sendKeyEvent(keyEvent)
                }

                override fun onLongClick() = Unit

                override fun onRelease() {
                    val keyEvent = KeyEvent(KeyEvent.ACTION_UP, keyShort)
                    keyEvent.source = type
                    controller.sendKeyEvent(keyEvent)
                }
            })
        }
        return button
    }

    @JvmStatic
    fun createCustomButton(
        elementId: String,
        keys: ShortArray,
        layer: Int,
        text: String?,
        icon: Int,
        sticky: Boolean,
        controller: KeyBoardController,
        conn: NvConnection?,
        context: Context
    ): KeyBoardDigitalButton {
        val button = KeyBoardDigitalButton(controller, elementId, layer, context)
        button.setText(text.orEmpty())
        button.setIcon(icon)

        if (sticky) {
            button.addDigitalButtonListener(object : KeyBoardDigitalButton.DigitalButtonListener {
                override fun onClick() {
                    if (button.isSticky()) {
                        button.setSticky(false)
                        return
                    }

                    val modifier = byteArrayOf(0)
                    for (key in keys) {
                        conn?.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, modifier[0], 0.toByte())
                        modifier[0] = (modifier[0].toInt() or getModifier(key).toInt()).toByte()
                    }
                }

                override fun onLongClick() {
                    button.setSticky(true)
                    controller.vibrate(-1)
                }

                override fun onRelease() {
                    if (button.isSticky()) return

                    val modifier = byteArrayOf(0)
                    for (i in keys.indices.reversed()) {
                        modifier[0] = (modifier[0].toInt() and getModifier(keys[i]).toInt().inv()).toByte()
                        conn?.sendKeyboardInput(keys[i], KeyboardPacket.KEY_UP, modifier[0], 0.toByte())
                    }
                }
            })
        } else {
            button.addDigitalButtonListener(object : KeyBoardDigitalButton.DigitalButtonListener {
                private val modifier = ByteArray(1)

                override fun onClick() {
                    controller.vibrate(KeyEvent.ACTION_DOWN)
                    modifier[0] = 0
                    for (key in keys) {
                        conn?.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, modifier[0], 0.toByte())
                        modifier[0] = (modifier[0].toInt() or getModifier(key).toInt()).toByte()
                    }
                }

                override fun onLongClick() = Unit

                override fun onRelease() {
                    controller.vibrate(KeyEvent.ACTION_UP)
                    for (i in keys.indices.reversed()) {
                        val key = keys[i]
                        modifier[0] = (modifier[0].toInt() and getModifier(key).toInt().inv()).toByte()
                        conn?.sendKeyboardInput(key, KeyboardPacket.KEY_UP, modifier[0], 0.toByte())
                    }
                }
            })
        }
        return button
    }

    @JvmStatic
    fun createDigitalTouchButton(
        elementId: String,
        keyShort: Int,
        type: Int,
        layer: Int,
        text: String,
        icon: Int,
        controller: KeyBoardController,
        context: Context
    ): KeyBoardTouchPadButton {
        val button = KeyBoardTouchPadButton(controller, elementId, layer, context)
        button.setText(text)
        button.setIcon(icon)
        button.addDigitalButtonListener(object : KeyBoardTouchPadButton.DigitalButtonListener {
            override fun onClick() {
                val code = if (keyShort == 9) 3 else 1
                val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, code)
                keyEvent.source = type
                controller.sendKeyEvent(keyEvent)
            }

            override fun onLongClick() = Unit

            override fun onMove(x: Int, y: Int) {
                controller.sendMouseMove(x, y)
            }

            override fun onRelease() {
                val code = if (keyShort == 9) 3 else 1
                val keyEvent = KeyEvent(KeyEvent.ACTION_UP, code)
                keyEvent.source = type
                controller.sendKeyEvent(keyEvent)
            }
        })
        return button
    }

    @JvmStatic
    fun createDefaultLayout(controller: KeyBoardController, context: Context, conn: NvConnection?) {
        val screen = context.resources.displayMetrics
        val config = PreferenceConfiguration.readPreferences(context)
        val height = screen.heightPixels
        val rightDisplacement = screen.widthPixels - screen.heightPixels * 16 / 9

        var buttonSize = 10
        var w = screenScale(buttonSize, height)
        val maxW = screen.widthPixels / 18
        if (w > maxW) {
            buttonSize = screenScaleSwitch(maxW, height)
            w = screenScale(buttonSize, height)
        }

        val result = try {
            context.assets.open("config/keyboard.json").use { input ->
                String(input.readBytes(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
        if (TextUtils.isEmpty(result)) return

        try {
            val jsonObject = JSONObject(result)
            val data = jsonObject.getJSONObject("data")
            val keystrokeList = data.getJSONArray("keystroke")
            val dpadList = data.getJSONArray("dpad")
            val rockerList = data.getJSONArray("rocker")
            val mouseList = data.getJSONArray("mouse")

            for (i in 0 until dpadList.length()) {
                val obj = dpadList.getJSONObject(i)
                controller.addElement(
                    createDiaitalPadButton(
                        obj.optString("elementId"),
                        obj.optInt("leftCode"),
                        obj.optInt("rightCode"),
                        obj.optInt("upCode"),
                        obj.optInt("downCode"),
                        controller,
                        context
                    ),
                    screenScale(92, height) + rightDisplacement,
                    screenScale(41, height),
                    (w * 2.5).toInt(),
                    (w * 2.5).toInt()
                )
            }

            for (i in 0 until rockerList.length()) {
                val obj = rockerList.getJSONObject(i)
                val keys = intArrayOf(
                    obj.optInt("upCode"),
                    obj.optInt("downCode"),
                    obj.optInt("leftCode"),
                    obj.optInt("rightCode"),
                    obj.optInt("middleCode")
                )

                val element = if (config.enableNewAnalogStick) {
                    createKeyBoardAnalogStickButton2(controller, obj.optString("elementId"), context, keys)
                } else {
                    createKeyBoardAnalogStickButton(controller, obj.optString("elementId"), context, keys)
                }
                controller.addElement(element, screenScale(4, height), screenScale(41, height), (w * 2.5).toInt(), (w * 2.5).toInt())
            }

            for (i in 0 until mouseList.length()) {
                val obj = mouseList.getJSONObject(i)
                obj.put("type", 1)
                keystrokeList.put(obj)
            }

            val buttonSum = 14.0
            var baseCount = 0
            for (i in 0 until keystrokeList.length()) {
                baseCount = i
                val obj = keystrokeList.getJSONObject(i)
                val name = obj.optString("name")
                val type = obj.optInt("type")
                val code = obj.optInt("code")
                val switchButton = obj.optInt("switchButton")
                var elementId = if (type == 0) "key_$code" else "m_$code"
                if (switchButton == 1) {
                    elementId = if (type == 0) "key_s_$code" else "m_s_$code"
                }
                val lastIndex = (i / buttonSum).toInt()
                val x = screenScale(1 + (i % buttonSum).toInt() * buttonSize, height)
                val y = screenScale(buttonSize + lastIndex * buttonSize, height)

                val element = if (TextUtils.equals("m_9", elementId) ||
                    TextUtils.equals("m_10", elementId) ||
                    TextUtils.equals("m_11", elementId)
                ) {
                    createDigitalTouchButton(elementId, code, type, 1, name, -1, controller, context)
                } else {
                    createDigitalButton(elementId, code, type, 1, name, -1, config.stickyModifierKey && isModifierKey(code), controller, context)
                }
                controller.addElement(element, x, y, w, w)
                LimeLog.info("x:$x,y:$y,W&H:$w,${screenScale(buttonSize, height)}")
            }

            addCustomKeys(controller, context, conn, height, buttonSize, w, buttonSum, baseCount + 1)
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }

        controller.setOpacity(config.oscOpacity)
    }

    private fun addCustomKeys(
        controller: KeyBoardController,
        context: Context,
        conn: NvConnection?,
        height: Int,
        buttonSize: Int,
        w: Int,
        buttonSum: Double,
        offset: Int
    ) {
        val preferences = context.getSharedPreferences(GameMenu.PREF_NAME, Activity.MODE_PRIVATE)
        val value = preferences.getString(GameMenu.KEY_NAME, "").orEmpty()
        if (TextUtils.isEmpty(value)) return

        try {
            val shortcutFile = KeyConfigHelper.parseShortcutFile(value)
            if (shortcutFile?.data == null || shortcutFile.data.isEmpty()) return

            val data = shortcutFile.data
            for (idx in data.indices) {
                val shortcut = data[idx]
                val shortcutId = shortcut.id
                val id = if (shortcutId.isNullOrEmpty()) idx.toString() else shortcutId
                val name = shortcut.name
                val keys = shortcut.keys
                val vkKeyCodes = ShortArray(keys.size)

                for (j in keys.indices) {
                    vkKeyCodes[j] = parseVirtualKeyCode(keys[j]).toShort()
                }

                val lastIndex = ((idx + offset) / buttonSum).toInt()
                val x = screenScale((1 + ((idx + offset) % buttonSum) * buttonSize).toInt(), height)
                val y = screenScale(buttonSize + lastIndex * buttonSize, height)

                controller.addElement(
                    createCustomButton("custom_$id", vkKeyCodes, 1, name, -1, shortcut.sticky, controller, conn, context),
                    x,
                    y,
                    w,
                    w
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, context.getString(R.string.wrong_import_format), Toast.LENGTH_SHORT).show()
        }
    }

    @JvmStatic
    fun parseVirtualKeyCode(code: String): Int {
        return when {
            code.startsWith("0x") -> code.substring(2).toInt(16)
            code.startsWith("VK_") -> {
                val field: Field = KeyMapper::class.java.getDeclaredField(code)
                field.getInt(null)
            }
            else -> throw IllegalArgumentException("Unknown key code: $code")
        }
    }

    @JvmStatic
    fun saveProfile(controller: KeyBoardController, context: Context) {
        val name = PreferenceManager.getDefaultSharedPreferences(context).getString(OSC_PREFERENCE, OSC_PREFERENCE_VALUE)
        val prefEditor: SharedPreferences.Editor = context.getSharedPreferences(name, Activity.MODE_PRIVATE).edit()

        for (element in controller.getElements()) {
            val prefKey = element.elementId
            try {
                prefEditor.putString(prefKey, element.getConfiguration().toString())
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }
        prefEditor.apply()
    }

    @JvmStatic
    fun loadFromPreferences(controller: KeyBoardController, context: Context) {
        val name = PreferenceManager.getDefaultSharedPreferences(context).getString(OSC_PREFERENCE, OSC_PREFERENCE_VALUE)
        val pref = context.getSharedPreferences(name, Activity.MODE_PRIVATE)

        for (element in controller.getElements()) {
            val prefKey = element.elementId
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
