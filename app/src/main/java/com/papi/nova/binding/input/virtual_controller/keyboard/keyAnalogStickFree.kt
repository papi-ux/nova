package com.papi.nova.binding.input.virtual_controller.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

open class keyAnalogStickFree(controller: KeyBoardController, context: Context, elementId: String) :
    keyBoardVirtualControllerElement(controller, context, elementId) {
    interface AnalogStickListener {
        fun onMovement(x: Float, y: Float)
        fun onClick()
        fun onDoubleClick()
        fun onRevoke()
    }

    private enum class STICK_STATE {
        NO_MOVEMENT,
        MOVED_IN_DEAD_ZONE,
        MOVED_ACTIVE
    }

    private enum class CLICK_STATE {
        SINGLE,
        DOUBLE
    }

    private var radius_complete = 0f
    private var radius_analog_stick = 0f
    private var radius_dead_zone = 0f
    private var relative_x = 0f
    private var relative_y = 0f
    private var bIsFingerOnScreen = false
    private var movement_radius = 0.0
    private var movement_angle = 0.0
    private var position_stick_x = 0f
    private var position_stick_y = 0f
    private val paint = Paint()
    private var stick_state = STICK_STATE.NO_MOVEMENT
    private var click_state = CLICK_STATE.SINGLE
    private val listeners = ArrayList<AnalogStickListener>()
    private var timeLastClick = 0L
    private var touchID = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchX = 0f
    private var touchY = 0f
    protected var strStickSide = "L"

    init {
        position_stick_x = width / 2f
        position_stick_y = height / 2f
    }

    fun addAnalogStickListener(listener: AnalogStickListener) {
        listeners.add(listener)
    }

    private fun notifyOnMovement(x: Float, y: Float) {
        _DBG("movement x: $x movement y: $y")
        for (listener in listeners) listener.onMovement(x, y)
    }

    private fun notifyOnClick() {
        _DBG("click")
        for (listener in listeners) listener.onClick()
    }

    private fun notifyOnDoubleClick() {
        _DBG("double click")
        for (listener in listeners) listener.onDoubleClick()
    }

    private fun notifyOnRevoke() {
        _DBG("revoke")
        for (listener in listeners) listener.onRevoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radius_complete = getPercent(getCorrectWidth() / 2f, 100f) - 2 * getDefaultStrokeWidth()
        radius_dead_zone = getPercent(getCorrectWidth() / 2f, 30f)
        radius_analog_stick = getPercent(getCorrectWidth() / 2f, 20f)
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onElementDraw(canvas: Canvas) {
        val bIsMoving = virtualController.getControllerMode() == KeyBoardController.ControllerMode.MoveButtons
        val bIsResizing = virtualController.getControllerMode() == KeyBoardController.ControllerMode.ResizeButtons
        val bIsEnable = virtualController.getControllerMode() == KeyBoardController.ControllerMode.DisableEnableButtons

        if (bIsMoving || bIsResizing || bIsEnable) {
            canvas.drawColor(getDefaultColor())
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            paint.textSize = minOf(width, height) / 2f
            canvas.drawText(strStickSide, width / 2f, height / 2f, paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = getDefaultStrokeWidth().toFloat()

        if (bIsFingerOnScreen) {
            canvas.drawColor(Color.TRANSPARENT)
            when (stick_state) {
                STICK_STATE.NO_MOVEMENT -> {
                    paint.color = Color.MAGENTA
                    canvas.drawCircle(width / 2f, height / 2f, radius_analog_stick, paint)
                }
                STICK_STATE.MOVED_IN_DEAD_ZONE,
                STICK_STATE.MOVED_ACTIVE -> {
                    paint.color = pressedColor
                    canvas.drawCircle(touchStartX, touchStartY, radius_analog_stick / 2.0f, paint)
                    canvas.drawCircle(position_stick_x, position_stick_y, radius_analog_stick, paint)
                }
            }
        }
    }

    private fun updatePosition(eventTime: Long) {
        val complete = radius_complete - radius_analog_stick
        val correlatedY = (sin(Math.PI / 2 - movement_angle) * movement_radius).toFloat()
        val correlatedX = (cos(Math.PI / 2 - movement_angle) * movement_radius).toFloat()

        position_stick_x = touchStartX - correlatedX
        position_stick_y = touchStartY - correlatedY

        stick_state =
            if (stick_state == STICK_STATE.MOVED_ACTIVE ||
                eventTime - timeLastClick > timeoutDeadzone ||
                movement_radius > radius_dead_zone
            ) {
                STICK_STATE.MOVED_ACTIVE
            } else {
                STICK_STATE.MOVED_IN_DEAD_ZONE
            }

        if (stick_state == STICK_STATE.MOVED_ACTIVE && complete != 0f) {
            notifyOnMovement(-correlatedX / complete, correlatedY / complete)
        }
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        val lastClickState = click_state
        relative_x = -(touchStartX - event.x)
        relative_y = -(touchStartY - event.y)
        movement_radius = getMovementRadius(relative_x, relative_y)
        movement_angle = getAngle(relative_x, relative_y)

        if (movement_radius > radius_complete - radius_analog_stick) {
            movement_radius = (radius_complete - radius_analog_stick).toDouble()
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!bIsFingerOnScreen) {
                    touchID = event.getPointerId(event.actionIndex)
                    touchStartX = event.x
                    touchStartY = event.y
                    bIsFingerOnScreen = true
                }

                if (touchID == event.getPointerId(event.actionIndex)) {
                    touchX = event.x
                    touchY = event.y
                    stick_state = STICK_STATE.MOVED_IN_DEAD_ZONE
                    if (lastClickState == CLICK_STATE.SINGLE &&
                        timeLastClick + timeoutDoubleClick > System.currentTimeMillis()
                    ) {
                        click_state = CLICK_STATE.DOUBLE
                        notifyOnDoubleClick()
                    } else {
                        click_state = CLICK_STATE.SINGLE
                        notifyOnClick()
                    }
                    timeLastClick = System.currentTimeMillis()
                    isPressed = true
                    updatePosition(event.eventTime)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    if (touchID == event.getPointerId(i)) {
                        touchX = event.getX(i)
                        touchY = event.getY(i)
                        updatePosition(event.eventTime)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (touchID == event.getPointerId(event.actionIndex)) {
                    isPressed = false
                    bIsFingerOnScreen = false
                }
            }
        }

        if (isPressed) {
            updatePosition(event.eventTime)
        } else {
            stick_state = STICK_STATE.NO_MOVEMENT
            notifyOnRevoke()
            notifyOnMovement(0f, 0f)
        }

        invalidate()
        return true
    }

    companion object {
        const val SIZE_RADIUS_COMPLETE = 90
        const val SIZE_RADIUS_ANALOG_STICK = 90
        const val SIZE_RADIUS_DEADZONE = 90
        const val timeoutDoubleClick = 350L
        const val timeoutDeadzone = 150L

        private fun getMovementRadius(x: Float, y: Float): Double = sqrt((x * x + y * y).toDouble())

        private fun getAngle(way_x: Float, way_y: Float): Double {
            if (way_x == 0f) return if (way_y < 0) Math.PI else 0.0
            if (way_y == 0f) {
                if (way_x > 0) return Math.PI * 3 / 2
                if (way_x < 0) return Math.PI / 2
            }
            return if (way_x > 0) {
                if (way_y < 0) 3 * Math.PI / 2 + atan((-way_y / way_x).toDouble())
                else Math.PI + atan((way_x / way_y).toDouble())
            } else {
                if (way_y > 0) Math.PI / 2 + atan((way_y / -way_x).toDouble())
                else atan((-way_x / -way_y).toDouble())
            }
        }
    }
}
