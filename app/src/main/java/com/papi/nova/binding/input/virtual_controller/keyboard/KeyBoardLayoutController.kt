package com.papi.nova.binding.input.virtual_controller.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.papi.nova.Game
import com.papi.nova.R
import com.papi.nova.preferences.PreferenceConfiguration
import java.util.BitSet

class KeyBoardLayoutController(
    private var frame_layout: FrameLayout,
    private val context: Context,
    private val prefConfig: PreferenceConfiguration
) {
    private val timerLongClickTimeout = 300L
    private var viewCallbacks: ViewCallbacks? = null
    private val handler = Handler(Looper.getMainLooper())
    @JvmField var shown = false
    private val keyboardView = LayoutInflater.from(context).inflate(R.layout.layout_axixi_keyboard, null) as LinearLayout
    private lateinit var keyPopup: PopupWindow
    private lateinit var keyPopupText: TextView
    private lateinit var hidePopupRunnable: Runnable
    private val modifierKeyStates = BitSet()

    init {
        initKeyPopup()
        initKeyboard()
    }

    fun setViewCallbacks(viewCallbacks: ViewCallbacks?) {
        this.viewCallbacks = viewCallbacks
    }

    fun getHandler(): Handler = handler

    fun isModifierKeyPressed(keyCode: Int): Boolean = modifierKeyStates.get(keyCode)

    private fun isModifierKey(keyCode: Int): Boolean {
        return prefConfig.stickyModifierKey && MODIFIER_KEY_CODES.contains(keyCode)
    }

    private fun isSpecialKey(keyCode: Int): Boolean =
        SPECIAL_KEY_CODES.contains(keyCode) || MODIFIER_KEY_CODES.contains(keyCode)

    private fun initKeyboard() {
        @SuppressLint("ClickableViewAccessibility")
        val touchListener = View.OnTouchListener { v, event ->
            val eventAction = event.action
            val tag = v.tag as String
            if (TextUtils.equals("hide", tag)) {
                if (eventAction == MotionEvent.ACTION_UP || eventAction == MotionEvent.ACTION_CANCEL) {
                    hide()
                }
                return@OnTouchListener true
            }

            val keyCode = tag.toInt()
            val keyAction: Int
            val modifierKey = isModifierKey(keyCode)
            val specialKey = isSpecialKey(keyCode)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (modifierKey && isModifierKeyPressed(keyCode)) {
                        modifierKeyStates.clear(keyCode)
                        return@OnTouchListener true
                    }

                    if (!TextUtils.equals("hide", tag) && !specialKey) {
                        val tempEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                        val unicodeChar = tempEvent.getUnicodeChar(0)
                        val popupText = if (unicodeChar != 0) {
                            unicodeChar.toChar().toString()
                        } else {
                            KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "")
                        }
                        keyPopupText.text = popupText
                        keyPopupText.measure(
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        )

                        val popupWidth = keyPopupText.measuredWidth
                        val location = IntArray(2)
                        v.getLocationInWindow(location)
                        val x = location[0] + (v.width - popupWidth) / 2
                        val y = (location[1] - v.height * 1.5).toInt()

                        keyPopup.update(x, y, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                        if (keyPopup.isShowing) {
                            keyPopup.update(x, y, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                        } else {
                            keyPopup.showAtLocation(v, Gravity.NO_GRAVITY, x, y)
                        }
                    }

                    keyAction = KeyEvent.ACTION_DOWN
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (modifierKey && isModifierKeyPressed(keyCode)) {
                        return@OnTouchListener true
                    }

                    handler.removeCallbacks(hidePopupRunnable)
                    handler.postDelayed(hidePopupRunnable, POPUP_DURATION_MS)
                    keyAction = KeyEvent.ACTION_UP
                }
                else -> return@OnTouchListener false
            }

            val keyEvent = KeyEvent(keyAction, keyCode)
            keyEvent.source = 0
            sendKeyEvent(keyEvent)

            if (modifierKey) {
                val longClickRunnable = longClickRunnables[keyCode]
                if (longClickRunnable != null) {
                    getHandler().removeCallbacks(longClickRunnable)
                    if (keyAction == KeyEvent.ACTION_DOWN) {
                        getHandler().postDelayed(longClickRunnable, timerLongClickTimeout)
                    }
                }
            }

            if (keyAction == KeyEvent.ACTION_DOWN) {
                if (prefConfig.enableKeyboardVibrate) {
                    keyboardView.performHapticFeedback(
                        HapticFeedbackConstants.VIRTUAL_KEY,
                        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                    )
                }
                v.setBackgroundResource(R.drawable.bg_ax_keyboard_button_confirm)
            } else {
                if (prefConfig.enableKeyboardVibrate) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        keyboardView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
                    } else {
                        keyboardView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
                v.setBackgroundResource(R.drawable.bg_ax_keyboard_button)
            }
            true
        }

        for (i in 0 until keyboardView.childCount) {
            val keyboardRow = keyboardView.getChildAt(i) as LinearLayout
            for (j in 0 until keyboardRow.childCount) {
                val child = keyboardRow.getChildAt(j)
                child.setOnTouchListener(touchListener)
                val keyTag = child.tag as String
                if (keyTag == "hide") continue
                val keycode = keyTag.toInt()
                if (isModifierKey(keycode)) {
                    longClickRunnables[keycode] = Runnable {
                        modifierKeyStates.set(keycode)
                        if (prefConfig.enableKeyboardVibrate) {
                            child.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                }
            }
        }
    }

    private fun initKeyPopup() {
        keyPopupText = TextView(context)
        keyPopupText.setBackgroundResource(R.drawable.key_popup_background)
        keyPopupText.setTextColor(Color.WHITE)
        keyPopupText.textSize = 32f
        keyPopupText.gravity = Gravity.CENTER
        keyPopupText.setPadding(24, 16, 24, 16)

        keyPopup = PopupWindow(
            keyPopupText,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        hidePopupRunnable = Runnable { keyPopup.dismiss() }
    }

    fun isKeyboardVisible(): Boolean = keyboardView.visibility == View.VISIBLE

    fun hide(temporary: Boolean) {
        if (prefConfig.enableKeyboardVibrate) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                keyboardView.performHapticFeedback(HapticFeedbackConstants.REJECT)
            } else {
                keyboardView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
        keyboardView.visibility = View.GONE
        if (!temporary) {
            shown = false
        }

        viewCallbacks?.onKeyboardControllerVisibilityChange(false)
    }

    fun hide() {
        hide(false)
    }

    fun show() {
        keyboardView.visibility = View.VISIBLE
        shown = true
        viewCallbacks?.onKeyboardControllerVisibilityChange(true)
    }

    fun toggleVisibility() {
        if (keyboardView.visibility == View.VISIBLE) {
            hide()
        } else {
            show()
        }
    }

    fun refreshLayout() {
        frame_layout.removeView(keyboardView)

        val height: Int
        val width: Int
        if (prefConfig.onscreenKeyboardAutoFitDisabled) {
            height = dip2px(context, prefConfig.onscreenKeyboardHeight.toFloat())
            val widthPreference = prefConfig.onscreenKeyboardWidth
            width = if (widthPreference == 1000) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                dip2px(context, widthPreference.toFloat())
            }
        } else {
            val screen: DisplayMetrics = context.resources.displayMetrics
            width = screen.widthPixels
            height = (screen.heightPixels * 0.5).toInt()
        }

        val params = FrameLayout.LayoutParams(width, height)
        params.gravity = Gravity.BOTTOM
        when (prefConfig.onscreenKeyboardAlignMode) {
            "left" -> params.gravity = params.gravity or Gravity.START
            "right" -> params.gravity = params.gravity or Gravity.END
            "center" -> params.gravity = params.gravity or Gravity.CENTER_HORIZONTAL
            else -> params.gravity = params.gravity or Gravity.CENTER_HORIZONTAL
        }

        keyboardView.alpha = prefConfig.oscKeyboardOpacity / 100f
        frame_layout.addView(keyboardView, params)
    }

    fun dip2px(context: Context, dpValue: Float): Int {
        val scale = context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    fun sendKeyEvent(keyEvent: KeyEvent) {
        val game = Game.instance
        if (game == null || !game.connected) {
            return
        }

        if (keyEvent.source == 1) {
            game.mouseButtonEvent(keyEvent.keyCode, KeyEvent.ACTION_DOWN == keyEvent.action)
        } else {
            game.onKey(null, keyEvent.keyCode, keyEvent)
        }
    }

    interface ViewCallbacks {
        fun onKeyboardControllerVisibilityChange(visible: Boolean)
    }

    companion object {
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

        private val SPECIAL_KEY_CODES = hashSetOf(
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_CAPS_LOCK,
            KeyEvent.KEYCODE_INSERT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END
        )

        private const val POPUP_DURATION_MS = 75L
        private val longClickRunnables = HashMap<Int, Runnable>()
    }
}
