package com.papi.nova.binding.input.virtual_controller.keyboard

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.papi.nova.Game
import com.papi.nova.GameMenu
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.utils.KeyConfigHelper
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class KeyBoardController(
    private val conn: NvConnection?,
    private var frame_layout: FrameLayout,
    private val context: Context
) {
    enum class ControllerMode {
        Active,
        MoveButtons,
        ResizeButtons,
        DisableEnableButtons
    }

    @JvmField var shown = false

    private val handler = Handler(Looper.getMainLooper())
    private var currentMode = ControllerMode.Active
    private val buttonConfigure = Button(context)
    private val buttonClearAll = Button(context)
    private val buttonAddKeys = Button(context)
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val elements = ArrayList<keyBoardVirtualControllerElement>()

    init {
        buttonConfigure.alpha = 0.5f
        buttonConfigure.isFocusable = false
        buttonConfigure.setBackgroundResource(R.drawable.ic_keyboard_setting)

        buttonConfigure.setOnLongClickListener {
            Toast.makeText(context, context.getString(R.string.keyboard_configure_movable), Toast.LENGTH_SHORT).show()
            buttonConfigure.tag = "movable"
            @Suppress("DEPRECATION")
            vibrator.vibrate(100L)
            true
        }

        buttonConfigure.setOnTouchListener(object : View.OnTouchListener {
            private var dX = 0f
            private var dY = 0f
            private var lastTouchX = 0f
            private var lastTouchY = 0f
            private var isMoving = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                if ("movable" == view.tag) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            dX = view.x - event.rawX
                            dY = view.y - event.rawY
                            lastTouchX = event.rawX
                            lastTouchY = event.rawY
                            isMoving = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            var newX = event.rawX + dX
                            var newY = event.rawY + dY
                            if (kotlin.math.abs(event.rawX - lastTouchX) > 5 ||
                                kotlin.math.abs(event.rawY - lastTouchY) > 5
                            ) {
                                isMoving = true
                            }

                            if (isMoving) {
                                newX = newX.coerceIn(0f, (frame_layout.width - view.width).toFloat())
                                newY = newY.coerceIn(0f, (frame_layout.height - view.height).toFloat())
                                view.x = newX
                                view.y = newY
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            view.tag = null
                            if (!isMoving) {
                                view.performClick()
                            }
                        }
                    }
                    return true
                }
                return false
            }
        })

        buttonConfigure.setOnClickListener {
            val message: String
            if (currentMode == ControllerMode.Active) {
                currentMode = ControllerMode.DisableEnableButtons
                showElements()
                showControlButtons(true)
                message = context.getString(R.string.configuration_mode_disable_enable_buttons)
            } else if (currentMode == ControllerMode.DisableEnableButtons) {
                currentMode = ControllerMode.MoveButtons
                showEnabledElements()
                showControlButtons(false)
                message = context.getString(R.string.configuration_mode_move_buttons)
            } else if (currentMode == ControllerMode.MoveButtons) {
                currentMode = ControllerMode.ResizeButtons
                message = context.getString(R.string.configuration_mode_resize_buttons)
            } else {
                currentMode = ControllerMode.Active
                KeyBoardControllerConfigurationLoader.saveProfile(this@KeyBoardController, context)
                message = context.getString(R.string.configuration_mode_exiting)
            }

            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            buttonConfigure.invalidate()
            for (element in elements) {
                element.invalidate()
            }
        }

        buttonClearAll.setBackgroundColor(Color.DKGRAY)
        buttonClearAll.text = context.getString(R.string.keyboard_clear_all)
        buttonClearAll.alpha = 0.7f
        buttonClearAll.visibility = View.GONE
        buttonClearAll.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.keyboard_clear_all_confirm_title))
                .setMessage(context.getString(R.string.keyboard_clear_all_confirm_message))
                .setPositiveButton(context.getString(R.string.yes)) { _, _ ->
                    for (element in elements) {
                        element.hidden = true
                        element.visibility = View.GONE
                    }
                    KeyBoardControllerConfigurationLoader.saveProfile(this@KeyBoardController, context)
                    vibrate(KeyEvent.ACTION_DOWN)
                }
                .setNegativeButton(context.getString(R.string.no), null)
                .show()
        }

        buttonAddKeys.setBackgroundColor(Color.DKGRAY)
        buttonAddKeys.text = context.getString(R.string.keyboard_add_keys)
        buttonAddKeys.alpha = 0.7f
        buttonAddKeys.visibility = View.GONE
        buttonAddKeys.setOnClickListener { showKeySelectionDialog() }

        refreshLayout()
    }

    fun getHandler(): Handler = handler

    fun hide(temporary: Boolean) {
        for (element in elements) {
            element.visibility = View.GONE
        }

        buttonConfigure.visibility = View.GONE
        if (!temporary) {
            shown = false
        }
    }

    fun hide() {
        hide(false)
    }

    fun show() {
        showEnabledElements()
        buttonConfigure.visibility = View.VISIBLE
        shown = true
    }

    fun showElements() {
        for (element in elements) {
            element.visibility = if (currentMode == ControllerMode.DisableEnableButtons) {
                if (element.hidden) View.GONE else View.VISIBLE
            } else {
                if (element.hidden || !element.enabled) View.GONE else View.VISIBLE
            }
        }
    }

    fun showEnabledElements() {
        for (element in elements) {
            element.visibility = if (currentMode == ControllerMode.DisableEnableButtons) {
                if (element.hidden) View.GONE else View.VISIBLE
            } else {
                if (!element.hidden && element.enabled) View.VISIBLE else View.GONE
            }
        }
    }

    fun toggleVisibility() {
        if (buttonConfigure.visibility == View.VISIBLE) {
            hide()
        } else {
            show()
        }
    }

    fun removeElements() {
        for (element in elements) {
            frame_layout.removeView(element)
        }
        elements.clear()
        frame_layout.removeView(buttonConfigure)
        frame_layout.removeView(buttonClearAll)
        frame_layout.removeView(buttonAddKeys)
    }

    fun setOpacity(opacity: Int) {
        for (element in elements) {
            element.setOpacity(opacity)
        }
    }

    fun addElement(element: keyBoardVirtualControllerElement, x: Int, y: Int, width: Int, height: Int) {
        elements.add(element)
        val layoutParams = FrameLayout.LayoutParams(width, height)
        layoutParams.setMargins(x, y, 0, 0)
        frame_layout.addView(element, layoutParams)
    }

    fun getElements(): List<keyBoardVirtualControllerElement> = elements

    fun refreshLayout() {
        removeElements()

        val screen = context.resources.displayMetrics
        val buttonSize = (screen.heightPixels * 0.06f).toInt()

        val configParams = FrameLayout.LayoutParams(buttonSize, buttonSize)
        configParams.leftMargin = 20 + buttonSize
        configParams.topMargin = 15
        frame_layout.addView(buttonConfigure, configParams)

        buttonClearAll.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        buttonAddKeys.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val clearAllWidth = buttonClearAll.measuredWidth
        val addKeysWidth = buttonAddKeys.measuredWidth

        val totalWidth = clearAllWidth + addKeysWidth + 3
        val screenCenter = screen.widthPixels / 2
        val startX = screenCenter - totalWidth / 2

        val clearParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        clearParams.leftMargin = startX
        clearParams.topMargin = 15
        frame_layout.addView(buttonClearAll, clearParams)

        val addParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        addParams.leftMargin = startX + clearAllWidth + 3
        addParams.topMargin = 15
        frame_layout.addView(buttonAddKeys, addParams)

        KeyBoardControllerConfigurationLoader.createDefaultLayout(this, context, conn)
        KeyBoardControllerConfigurationLoader.loadFromPreferences(this, context)
    }

    fun getControllerMode(): ControllerMode = currentMode

    fun sendKeyEvent(keyEvent: KeyEvent) {
        if (Game.instance == null || !Game.instance.connected) {
            return
        }

        if (keyEvent.source == 1) {
            Game.instance.mouseButtonEvent(keyEvent.keyCode, KeyEvent.ACTION_DOWN == keyEvent.action)
        } else {
            Game.instance.onKey(null, keyEvent.keyCode, keyEvent)
        }

        if (keyEvent.source != 2) {
            vibrate(keyEvent.action)
        }
    }

    fun sendMouseMove(x: Int, y: Int) {
        if (Game.instance == null || !Game.instance.connected) {
            return
        }
        Game.instance.mouseMove(x, y)
    }

    fun vibrate(action: Int) {
        if (PreferenceConfiguration.readPreferences(context).enableKeyboardVibrate && vibrator.hasVibrator()) {
            when (action) {
                KeyEvent.ACTION_DOWN -> frame_layout.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                KeyEvent.ACTION_UP -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        frame_layout.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
                    } else {
                        frame_layout.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
                else -> frame_layout.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    private fun showControlButtons(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        buttonClearAll.visibility = visibility
        buttonAddKeys.visibility = visibility
    }

    private fun showKeySelectionDialog() {
        try {
            val jsonConfig = context.assets.open("config/keyboard.json").use { input ->
                String(input.readBytes(), Charsets.UTF_8)
            }

            val json = JSONObject(jsonConfig)
            val data = json.getJSONObject("data")
            val keystrokeList = data.getJSONArray("keystroke")
            val mouseList = data.getJSONArray("mouse")
            val rockerList = data.getJSONArray("rocker")
            val dpadList = data.getJSONArray("dpad")

            val allItemsList = ArrayList<JSONObject>()
            val keyNamesList = ArrayList<String>()

            for (i in 0 until keystrokeList.length()) {
                val key = keystrokeList.getJSONObject(i)
                key.put("type", 0)
                allItemsList.add(key)
                keyNamesList.add(key.getString("name"))
            }

            for (i in 0 until mouseList.length()) {
                val obj = mouseList.getJSONObject(i)
                obj.put("type", 1)
                allItemsList.add(obj)
                keyNamesList.add(obj.getString("name"))
            }

            for (i in 0 until rockerList.length()) {
                val obj = rockerList.getJSONObject(i)
                obj.put("type", 2)
                allItemsList.add(obj)
                keyNamesList.add(obj.getString("name") + " (Joystick)")
            }

            for (i in 0 until dpadList.length()) {
                val obj = dpadList.getJSONObject(i)
                obj.put("type", 3)
                allItemsList.add(obj)
                keyNamesList.add(obj.getString("name") + " (D-Pad)")
            }

            addCustomShortcutChoices(allItemsList, keyNamesList)

            val keyNames = keyNamesList.toTypedArray()
            val checkedItems = BooleanArray(keyNames.size)

            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.keyboard_select_keys))
                .setMultiChoiceItems(keyNames, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton(context.getString(R.string.keyboard_add)) { _, _ ->
                    addSelectedKeys(checkedItems, allItemsList)
                }
                .setNegativeButton(context.getString(R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            LimeLog.warning("Error loading keyboard configuration: " + e.message)
            e.printStackTrace()
            Toast.makeText(context, context.getString(R.string.keyboard_load_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun addCustomShortcutChoices(allItemsList: MutableList<JSONObject>, keyNamesList: MutableList<String>) {
        val preferences = context.getSharedPreferences(GameMenu.PREF_NAME, Context.MODE_PRIVATE)
        val value = preferences.getString(GameMenu.KEY_NAME, "").orEmpty()

        if (TextUtils.isEmpty(value)) return

        try {
            val shortcutFile = KeyConfigHelper.parseShortcutFile(value)
            if (shortcutFile?.data == null || shortcutFile.data.isEmpty()) return

            val shortcutData = shortcutFile.data
            for (idx in shortcutData.indices) {
                val shortcut = shortcutData[idx]
                val shortcutId = shortcut.id
                val id = if (shortcutId.isNullOrEmpty()) idx.toString() else shortcutId

                val customKey = JSONObject()
                customKey.put("type", 4)
                customKey.put("name", shortcut.name)
                customKey.put("elementId", "custom_$id")
                customKey.put("sticky", shortcut.sticky)

                val keyCodesJson = JSONArray()
                for (code in shortcut.keys) {
                    keyCodesJson.put(code)
                }
                customKey.put("keys", keyCodesJson)

                allItemsList.add(customKey)
                keyNamesList.add(context.getString(R.string.keyboard_key_custom, shortcut.name))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error loading custom keys: " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun addSelectedKeys(checkedItems: BooleanArray, allItemsList: List<JSONObject>) {
        val screen = context.resources.displayMetrics
        val height = screen.heightPixels

        var buttonSize = 10
        var w = KeyBoardControllerConfigurationLoader.screenScale(buttonSize, height)
        val maxW = screen.widthPixels / 18
        if (w > maxW) {
            buttonSize = KeyBoardControllerConfigurationLoader.screenScaleSwitch(maxW, height)
            w = KeyBoardControllerConfigurationLoader.screenScale(buttonSize, height)
        }

        val existingElementIds = HashSet<String>()
        val existingPositions = ArrayList<Rect>()
        for (element in elements) {
            if (element.visibility != View.GONE) {
                existingElementIds.add(element.elementId)
                val params = element.layoutParams as? FrameLayout.LayoutParams
                if (params != null) {
                    existingPositions.add(Rect(params.leftMargin, params.topMargin, params.leftMargin + params.width, params.topMargin + params.height))
                } else {
                    existingPositions.add(Rect(element.x.toInt(), element.y.toInt(), element.x.toInt() + element.width, element.y.toInt() + element.height))
                }
            }
        }

        var elementsAdded = 0
        var duplicatesFound = 0
        for (i in checkedItems.indices) {
            if (!checkedItems[i]) continue

            try {
                val obj = allItemsList[i]
                val type = obj.optInt("type", 0)
                val elementId = resolveElementId(type, obj)
                if (existingElementIds.contains(elementId)) {
                    duplicatesFound++
                    continue
                }

                val elementSize = if (type == 2 || type == 3) (w * 2.5).toInt() else w
                val position = findNonOverlappingPosition(existingPositions, elementSize)
                val newElement = createElementForItem(type, elementId, obj, w)

                addElement(newElement, position.x, position.y, elementSize, elementSize)
                existingPositions.add(Rect(position.x, position.y, position.x + elementSize, position.y + elementSize))
                existingElementIds.add(elementId)
                elementsAdded++
                vibrate(KeyEvent.ACTION_DOWN)
            } catch (e: JSONException) {
                LimeLog.warning("Error adding key: " + e.message)
                e.printStackTrace()
            } catch (e: Exception) {
                LimeLog.warning("Unexpected error adding key: " + e.message)
                e.printStackTrace()
            }
        }

        val feedback = StringBuilder()
        if (elementsAdded > 0) {
            KeyBoardControllerConfigurationLoader.saveProfile(this, context)
            feedback.append(context.getString(R.string.keyboard_keys_added, elementsAdded))
        }
        if (duplicatesFound > 0) {
            if (feedback.isNotEmpty()) {
                feedback.append("\n")
            }
            feedback.append(context.getString(R.string.keyboard_duplicates_skipped, duplicatesFound))
        }

        if (feedback.isNotEmpty()) {
            Toast.makeText(context, feedback.toString(), Toast.LENGTH_LONG).show()
        }
    }

    @Throws(JSONException::class)
    private fun resolveElementId(type: Int, obj: JSONObject): String {
        return if (type == 2 || type == 3 || type == 4) {
            obj.getString("elementId")
        } else {
            val code = obj.getInt("code")
            val switchButton = obj.optInt("switchButton", 0)
            if (switchButton == 1) {
                if (type == 0) "key_s_$code" else "m_s_$code"
            } else {
                if (type == 0) "key_$code" else "m_$code"
            }
        }
    }

    @Throws(Exception::class)
    private fun createElementForItem(
        type: Int,
        elementId: String,
        obj: JSONObject,
        w: Int
    ): keyBoardVirtualControllerElement {
        return when (type) {
            4 -> {
                val keysJson = obj.getJSONArray("keys")
                val vkKeyCodes = ShortArray(keysJson.length())
                for (j in 0 until keysJson.length()) {
                    vkKeyCodes[j] = KeyBoardControllerConfigurationLoader.parseVirtualKeyCode(keysJson.getString(j)).toShort()
                }
                KeyBoardControllerConfigurationLoader.createCustomButton(
                    elementId,
                    vkKeyCodes,
                    1,
                    obj.getString("name"),
                    -1,
                    obj.getBoolean("sticky"),
                    this,
                    conn,
                    context
                )
            }
            2 -> {
                val keys = intArrayOf(
                    obj.getInt("upCode"),
                    obj.getInt("downCode"),
                    obj.getInt("leftCode"),
                    obj.getInt("rightCode"),
                    obj.getInt("middleCode")
                )
                KeyBoardControllerConfigurationLoader.createKeyBoardAnalogStickButton(this, elementId, context, keys)
            }
            3 -> KeyBoardControllerConfigurationLoader.createDiaitalPadButton(
                elementId,
                obj.getInt("leftCode"),
                obj.getInt("rightCode"),
                obj.getInt("upCode"),
                obj.getInt("downCode"),
                this,
                context
            )
            else -> {
                val name = obj.getString("name")
                val code = obj.getInt("code")
                if (elementId == "m_9" || elementId == "m_10" || elementId == "m_11") {
                    KeyBoardControllerConfigurationLoader.createDigitalTouchButton(elementId, code, type, 1, name, -1, this, context)
                } else {
                    KeyBoardControllerConfigurationLoader.createDigitalButton(
                        elementId,
                        code,
                        type,
                        1,
                        name,
                        -1,
                        PreferenceConfiguration.readPreferences(context).stickyModifierKey &&
                            KeyBoardControllerConfigurationLoader.isModifierKey(code),
                        this,
                        context
                    )
                }
            }
        }
    }

    private fun findNonOverlappingPosition(existingPositions: List<Rect>, elementSize: Int): Point {
        val adjacent = findPositionNextToExisting(existingPositions, elementSize)
        return adjacent ?: findNonOverlappingPositionFromTopLeft(existingPositions, elementSize)
    }

    private fun findPositionNextToExisting(existingPositions: List<Rect>, elementSize: Int): Point? {
        val spacing = 10
        for (existingRect in existingPositions) {
            val potentialPositions = arrayOf(
                Point(existingRect.right + spacing, existingRect.top),
                Point(existingRect.left - elementSize - spacing, existingRect.top),
                Point(existingRect.left, existingRect.bottom + spacing),
                Point(existingRect.left, existingRect.top - elementSize - spacing)
            )

            for (position in potentialPositions) {
                if (isPositionFree(position, elementSize, existingPositions)) {
                    return position
                }
            }
        }
        return null
    }

    private fun isPositionFree(pos: Point, elementSize: Int, existingPositions: List<Rect>): Boolean {
        val screen: DisplayMetrics = context.resources.displayMetrics
        val spacing = 10
        val newRect = Rect(pos.x, pos.y, pos.x + elementSize, pos.y + elementSize)

        if (newRect.left < spacing ||
            newRect.right > screen.widthPixels - spacing ||
            newRect.top < 100 ||
            newRect.bottom > screen.heightPixels - 50
        ) {
            return false
        }

        for (existing in existingPositions) {
            if (Rect.intersects(existing, newRect)) {
                return false
            }
        }

        val configButtonArea = Rect(0, 0, 150, 100)
        return !Rect.intersects(configButtonArea, newRect)
    }

    private fun findNonOverlappingPositionFromTopLeft(existingPositions: List<Rect>, elementSize: Int): Point {
        val screen: DisplayMetrics = context.resources.displayMetrics
        val spacing = 10
        val startY = 100
        var x = spacing
        var y = startY

        while (y + elementSize < screen.heightPixels - 50) {
            if (isPositionFree(Point(x, y), elementSize, existingPositions)) {
                return Point(x, y)
            }
            x += elementSize + spacing
            if (x + elementSize > screen.widthPixels - spacing) {
                x = spacing
                y += elementSize + spacing
            }
        }

        return Point(spacing, startY)
    }
}
