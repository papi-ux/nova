package com.papi.nova.binding.input.virtual_controller.keyboard

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONException
import org.json.JSONObject

abstract class keyBoardVirtualControllerElement protected constructor(
    protected var virtualController: KeyBoardController,
    context: Context,
    @JvmField val elementId: String
) : View(context) {
    private val paint = Paint()

    private var normalColor = 0xF0888888.toInt()
    protected var pressedColor = 0xA3DCDCDE.toInt()
    private var configMoveColor = 0xF0FF0000.toInt()
    private var configResizeColor = 0xF0FF00FF.toInt()
    private var configSelectedColor = 0xF000FF00.toInt()
    private var configDisabledColor = 0xF0AAAAAA.toInt()

    protected var startSize_x = 0
    protected var startSize_y = 0

    var position_pressed_x = 0f
    var position_pressed_y = 0f

    @JvmField var enabled = true
    @JvmField var hidden = false

    private enum class Mode {
        Normal,
        Resize,
        Move
    }

    private var currentMode = Mode.Normal
    private var lastMoveX = 0
    private var lastMoveY = 0

    protected fun moveElement(pressed_x: Int, pressed_y: Int, x: Int, y: Int) {
        var newPosX = getX().toInt() + x - pressed_x
        var newPosY = getY().toInt() + y - pressed_y

        lastMoveX = newPosX
        lastMoveY = newPosY

        if (virtualController.getControllerMode() == KeyBoardController.ControllerMode.MoveButtons) {
            val otherViews = virtualController.getElements()
                .filter { it !== this }
                .map { it as View }
                .toTypedArray()
            val snapResult = LayoutSnappingHelper.calculateSnappedPosition(this, otherViews, newPosX, newPosY)
            newPosX = snapResult.newX
            newPosY = snapResult.newY

            if (snapResult.didSnap || snapResult.didAdjustSpacing) {
                virtualController.vibrate(KeyEvent.ACTION_DOWN)
            }
        }

        val layoutParams = layoutParams as FrameLayout.LayoutParams
        layoutParams.leftMargin = if (newPosX > 0) newPosX else 0
        layoutParams.topMargin = if (newPosY > 0) newPosY else 0
        layoutParams.rightMargin = 0
        layoutParams.bottomMargin = 0
        requestLayout()
    }

    protected fun resizeElement(pressed_x: Int, pressed_y: Int, width: Int, height: Int) {
        val layoutParams = layoutParams as FrameLayout.LayoutParams
        val newHeight = height + (startSize_y - pressed_y)
        val newWidth = width + (startSize_x - pressed_x)

        layoutParams.height = if (newHeight > 20) newHeight else 20
        layoutParams.width = if (newWidth > 20) newWidth else 20
        requestLayout()
    }

    protected fun checkAndApplyResize() {
        if (virtualController.getControllerMode() == KeyBoardController.ControllerMode.MoveButtons) {
            val otherViews = virtualController.getElements()
                .filter { it !== this }
                .map { it as View }
                .toTypedArray()
            val snapResult = LayoutSnappingHelper.calculateSnappedPosition(this, otherViews, lastMoveX, lastMoveY)

            if (snapResult.didResize) {
                val layoutParams = layoutParams as FrameLayout.LayoutParams
                layoutParams.width = snapResult.newWidth
                layoutParams.height = snapResult.newHeight
                virtualController.vibrate(KeyEvent.ACTION_DOWN)
                requestLayout()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        onElementDraw(canvas)

        if (currentMode != Mode.Normal) {
            paint.color = configSelectedColor
            paint.strokeWidth = getDefaultStrokeWidth().toFloat()
            paint.style = Paint.Style.STROKE
            canvas.drawRect(
                paint.strokeWidth,
                paint.strokeWidth,
                width - paint.strokeWidth,
                height - paint.strokeWidth,
                paint
            )
        }

        super.onDraw(canvas)
    }

    protected fun actionEnableMove() {
        currentMode = Mode.Move
    }

    protected fun actionEnableResize() {
        currentMode = Mode.Resize
    }

    protected fun actionCancel() {
        currentMode = Mode.Normal
        invalidate()
    }

    protected fun getDefaultColor(): Int {
        return when (virtualController.getControllerMode()) {
            KeyBoardController.ControllerMode.MoveButtons -> configMoveColor
            KeyBoardController.ControllerMode.ResizeButtons -> configResizeColor
            KeyBoardController.ControllerMode.DisableEnableButtons -> if (enabled) configSelectedColor else configDisabledColor
            else -> normalColor
        }
    }

    protected fun getDefaultStrokeWidth(): Int {
        val screen: DisplayMetrics = resources.displayMetrics
        return (screen.heightPixels * 0.004f).toInt()
    }

    protected fun showConfigurationDialog() {
        val alertBuilder = AlertDialog.Builder(context)
        alertBuilder.setTitle("Configuration")
        alertBuilder.setItems(arrayOf<CharSequence>("Move", "Resize", "Cancel")) { _, which ->
            when (which) {
                0 -> actionEnableMove()
                1 -> actionEnableResize()
                else -> actionCancel()
            }
        }
        alertBuilder.create().show()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionIndex != 0) {
            return true
        }

        if (virtualController.getControllerMode() == KeyBoardController.ControllerMode.Active) {
            return onElementTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                position_pressed_x = event.x
                position_pressed_y = event.y
                startSize_x = width
                startSize_y = height

                when (virtualController.getControllerMode()) {
                    KeyBoardController.ControllerMode.MoveButtons -> actionEnableMove()
                    KeyBoardController.ControllerMode.ResizeButtons -> actionEnableResize()
                    KeyBoardController.ControllerMode.DisableEnableButtons -> actionDisableEnableButton()
                    else -> Unit
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (currentMode) {
                    Mode.Move -> moveElement(position_pressed_x.toInt(), position_pressed_y.toInt(), event.x.toInt(), event.y.toInt())
                    Mode.Resize -> resizeElement(position_pressed_x.toInt(), position_pressed_y.toInt(), event.x.toInt(), event.y.toInt())
                    Mode.Normal -> Unit
                }
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                if (currentMode == Mode.Move) {
                    checkAndApplyResize()
                }
                actionCancel()
                return true
            }
        }
        return true
    }

    protected abstract fun onElementDraw(canvas: Canvas)

    abstract fun onElementTouchEvent(event: MotionEvent): Boolean

    fun setColors(normalColor: Int, pressedColor: Int) {
        this.normalColor = normalColor
        this.pressedColor = pressedColor
        invalidate()
    }

    fun setOpacity(opacity: Int) {
        val hexOpacity = opacity * 255 / 100
        normalColor = (hexOpacity shl 24) or (normalColor and 0x00FFFFFF)
        pressedColor = (hexOpacity shl 24) or (pressedColor and 0x00FFFFFF)
        invalidate()
    }

    protected fun getPercent(value: Float, percent: Float): Float = value / 100 * percent

    protected fun getPercent(value: Int, percent: Int): Float = value.toFloat() / 100 * percent

    protected fun getCorrectWidth(): Int = if (width > height) height else width

    @Throws(JSONException::class)
    fun getConfiguration(): JSONObject {
        val configuration = JSONObject()
        val layoutParams = layoutParams as FrameLayout.LayoutParams

        configuration.put("LEFT", layoutParams.leftMargin)
        configuration.put("TOP", layoutParams.topMargin)
        configuration.put("WIDTH", layoutParams.width)
        configuration.put("HEIGHT", layoutParams.height)
        configuration.put("ENABLED", enabled)
        configuration.put("HIDDEN", hidden)
        return configuration
    }

    @Throws(JSONException::class)
    fun loadConfiguration(configuration: JSONObject) {
        val layoutParams = layoutParams as FrameLayout.LayoutParams

        layoutParams.leftMargin = configuration.getInt("LEFT")
        layoutParams.topMargin = configuration.getInt("TOP")
        layoutParams.width = configuration.getInt("WIDTH")
        layoutParams.height = configuration.getInt("HEIGHT")
        enabled = configuration.getBoolean("ENABLED")
        hidden = configuration.optBoolean("HIDDEN", false)

        visibility = if (virtualController.getControllerMode() != KeyBoardController.ControllerMode.DisableEnableButtons) {
            if (!hidden && enabled) VISIBLE else GONE
        } else {
            if (!hidden) VISIBLE else GONE
        }
        requestLayout()
    }

    protected fun actionDisableEnableButton() {
        enabled = !enabled
        if (!hidden && virtualController.getControllerMode() != KeyBoardController.ControllerMode.DisableEnableButtons) {
            visibility = if (enabled) VISIBLE else GONE
        }
        invalidate()
    }

    companion object {
        @JvmField var _PRINT_DEBUG_INFORMATION = false

        const val EID_DPAD = 1
        const val EID_LT = 2
        const val EID_RT = 3
        const val EID_LB = 4
        const val EID_RB = 5
        const val EID_A = 6
        const val EID_B = 7
        const val EID_X = 8
        const val EID_Y = 9
        const val EID_BACK = 10
        const val EID_START = 11
        const val EID_LS = 12
        const val EID_RS = 13
        const val EID_LSB = 14
        const val EID_RSB = 15

        @JvmStatic
        fun _DBG(text: String) {
            if (_PRINT_DEBUG_INFORMATION) {
                // Debug logging intentionally disabled to match the Java implementation.
            }
        }
    }
}
