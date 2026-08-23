package com.papi.nova.binding.input.virtual_controller

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.binding.input.ControllerHandler
import com.papi.nova.preferences.PreferenceConfiguration

class VirtualController(
    private val controllerHandler: ControllerHandler?,
    private var frame_layout: FrameLayout?,
    private val context: Context
) {
    class ControllerInputContext {
        @JvmField var inputMap = 0
        @JvmField var leftTrigger: Byte = 0x00
        @JvmField var rightTrigger: Byte = 0x00
        @JvmField var rightStickX: Short = 0x0000
        @JvmField var rightStickY: Short = 0x0000
        @JvmField var leftStickX: Short = 0x0000
        @JvmField var leftStickY: Short = 0x0000
    }

    enum class ControllerMode {
        Active,
        MoveButtons,
        ResizeButtons,
        DisableEnableButtons
    }

    private val handler = Handler(Looper.getMainLooper())
    private val delayedRetransmitRunnable = Runnable { sendControllerInputContextInternal() }
    private var currentMode = ControllerMode.Active
    private val inputContext = ControllerInputContext()
    private var buttonConfigure: Button = Button(context)
    private val elements = ArrayList<VirtualControllerElement>()
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val defaultVibrationEffect: VibrationEffect? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            null
        }
    private var layoutRefreshPending = false
    private var appliedViewportWidth = 0
    private var appliedViewportHeight = 0
    private val layoutChangeListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        val width = right - left
        val height = bottom - top
        if (width > 0 && height > 0 &&
            (width != appliedViewportWidth || height != appliedViewportHeight)
        ) {
            scheduleLayoutRefresh()
        }
    }

    init {
        frame_layout?.addOnLayoutChangeListener(layoutChangeListener)
        buttonConfigure.alpha = 0.25f
        buttonConfigure.isFocusable = false
        buttonConfigure.setBackgroundResource(R.drawable.ic_settings)
        buttonConfigure.setOnClickListener {
            val message: String

            if (currentMode == ControllerMode.Active) {
                currentMode = ControllerMode.DisableEnableButtons
                showElements()
                message = context.getString(R.string.configuration_mode_disable_enable_buttons)
            } else if (currentMode == ControllerMode.DisableEnableButtons) {
                currentMode = ControllerMode.MoveButtons
                showEnabledElements()
                message = context.getString(R.string.configuration_mode_move_buttons)
            } else if (currentMode == ControllerMode.MoveButtons) {
                currentMode = ControllerMode.ResizeButtons
                message = context.getString(R.string.configuration_mode_resize_buttons)
            } else {
                currentMode = ControllerMode.Active
                VirtualControllerConfigurationLoader.saveProfile(this@VirtualController, context)
                message = context.getString(R.string.configuration_mode_exiting)
            }

            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            buttonConfigure.invalidate()

            for (element in elements) {
                element.invalidate()
            }
        }
    }

    fun getHandler(): Handler = handler

    fun hide() {
        for (element in elements) {
            element.visibility = View.GONE
        }
        buttonConfigure.visibility = View.GONE
    }

    fun show() {
        showEnabledElements()
        buttonConfigure.visibility = View.VISIBLE
    }

    fun switchShowHide(): Int {
        return if (buttonConfigure.visibility == View.VISIBLE) {
            hide()
            0
        } else {
            show()
            1
        }
    }

    fun showElements() {
        for (element in elements) {
            element.visibility = View.VISIBLE
        }
    }

    fun showEnabledElements() {
        for (element in elements) {
            element.visibility = if (element.enabled) View.VISIBLE else View.GONE
        }
    }

    fun removeElements() {
        val layout = frame_layout ?: return
        for (element in elements) {
            layout.removeView(element)
        }
        elements.clear()
        layout.removeView(buttonConfigure)
    }

    fun setOpacity(opacity: Int) {
        for (element in elements) {
            element.setOpacity(opacity)
        }
    }

    fun addElement(element: VirtualControllerElement, x: Int, y: Int, width: Int, height: Int) {
        elements.add(element)
        val layoutParams = FrameLayout.LayoutParams(width, height)
        layoutParams.setMargins(x, y, 0, 0)
        frame_layout?.addView(element, layoutParams)
    }

    fun getElements(): List<VirtualControllerElement> = elements

    fun refreshLayout() {
        val layout = frame_layout ?: return
        val width = layout.width
        val height = layout.height
        if (width <= 0 || height <= 0) {
            scheduleLayoutRefresh()
            return
        }

        refreshLayout(width, height)
    }

    private fun refreshLayout(viewportWidth: Int, viewportHeight: Int) {
        val wasShown = buttonConfigure.visibility == View.VISIBLE
        removeElements()

        val viewport = VirtualControllerConfigurationLoader.resolveLayoutViewport(
            viewportWidth,
            viewportHeight,
        )
        val buttonSize = (viewport.height * 0.06f).toInt().coerceAtLeast(1)
        val params = FrameLayout.LayoutParams(buttonSize, buttonSize)
        params.leftMargin = 15
        params.topMargin = 15
        frame_layout?.addView(buttonConfigure, params)

        VirtualControllerConfigurationLoader.createDefaultLayout(
            this,
            context,
            viewportWidth,
            viewportHeight,
        )
        VirtualControllerConfigurationLoader.loadFromPreferences(
            this,
            context,
            viewportWidth,
            viewportHeight,
        )
        if (!wasShown) {
            hide()
        } else if (currentMode == ControllerMode.DisableEnableButtons) {
            showElements()
        } else {
            showEnabledElements()
        }
        appliedViewportWidth = viewportWidth
        appliedViewportHeight = viewportHeight
    }

    private fun scheduleLayoutRefresh() {
        if (layoutRefreshPending) return
        layoutRefreshPending = true
        handler.post {
            layoutRefreshPending = false
            val layout = frame_layout ?: return@post
            val width = layout.width
            val height = layout.height
            if (width > 0 && height > 0 &&
                (width != appliedViewportWidth || height != appliedViewportHeight)
            ) {
                refreshLayout(width, height)
            }
        }
    }

    fun getControllerMode(): ControllerMode = currentMode

    fun getControllerInputContext(): ControllerInputContext = inputContext

    private fun sendControllerInputContextInternal() {
        _DBG("INPUT_MAP + " + inputContext.inputMap)
        _DBG("LEFT_TRIGGER " + inputContext.leftTrigger)
        _DBG("RIGHT_TRIGGER " + inputContext.rightTrigger)
        _DBG("LEFT STICK X: " + inputContext.leftStickX + " Y: " + inputContext.leftStickY)
        _DBG("RIGHT STICK X: " + inputContext.rightStickX + " Y: " + inputContext.rightStickY)

        controllerHandler?.reportOscState(
            inputContext.inputMap,
            inputContext.leftStickX,
            inputContext.leftStickY,
            inputContext.rightStickX,
            inputContext.rightStickY,
            inputContext.leftTrigger,
            inputContext.rightTrigger
        )
    }

    fun sendControllerInputContext(vibrationDuration: Long, vibrationAmplitude: Int) {
        handler.removeCallbacks(delayedRetransmitRunnable)
        sendControllerInputContextInternal()

        if (frame_layout != null && PreferenceConfiguration.readPreferences(context).enableKeyboardVibrate) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vibrationDuration == 0L) {
                    defaultVibrationEffect
                } else {
                    VibrationEffect.createOneShot(vibrationDuration, vibrationAmplitude)
                }
                if (effect != null) {
                    vibrator.vibrate(effect)
                }
            } else {
                val duration = if (vibrationDuration == 0L) 10L else vibrationDuration
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }

        handler.postDelayed(delayedRetransmitRunnable, 25)
        handler.postDelayed(delayedRetransmitRunnable, 50)
        handler.postDelayed(delayedRetransmitRunnable, 75)
    }

    fun sendControllerInputContext() {
        sendControllerInputContext(0, 0)
    }

    companion object {
        private const val _PRINT_DEBUG_INFORMATION = false

        private fun _DBG(text: String) {
            if (_PRINT_DEBUG_INFORMATION) {
                LimeLog.info("VirtualController: $text")
            }
        }
    }
}
