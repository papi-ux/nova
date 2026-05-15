package com.papi.nova.binding.input.virtual_controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

open class AnalogStick(controller: VirtualController, context: Context, elementId: Int) :
    VirtualControllerElement(controller, context, elementId) {
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
    private var movement_radius = 0.0
    private var movement_angle = 0.0
    private var position_stick_x = 0f
    private var position_stick_y = 0f
    private val paint = Paint()
    private var stick_state = STICK_STATE.NO_MOVEMENT
    private var click_state = CLICK_STATE.SINGLE
    private val listeners = ArrayList<AnalogStickListener>()
    private var timeLastClick = 0L

    init {
        position_stick_x = width / 2f
        position_stick_y = height / 2f
    }

    fun addAnalogStickListener(listener: AnalogStickListener) {
        listeners.add(listener)
    }

    private fun notifyOnMovement(x: Float, y: Float) {
        _DBG("movement x: $x movement y: $y")
        for (listener in listeners) {
            listener.onMovement(x, y)
        }
    }

    private fun notifyOnClick() {
        _DBG("click")
        for (listener in listeners) {
            listener.onClick()
        }
    }

    private fun notifyOnDoubleClick() {
        _DBG("double click")
        for (listener in listeners) {
            listener.onDoubleClick()
        }
    }

    private fun notifyOnRevoke() {
        _DBG("revoke")
        for (listener in listeners) {
            listener.onRevoke()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radius_complete = getPercent(getCorrectWidth() / 2f, 100f) - 2 * getDefaultStrokeWidth()
        radius_dead_zone = getPercent(getCorrectWidth() / 2f, 30f)
        radius_analog_stick = getPercent(getCorrectWidth() / 2f, 20f)
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onElementDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = getDefaultStrokeWidth().toFloat()

        paint.color = if (!isPressed || click_state == CLICK_STATE.SINGLE) getDefaultColor() else pressedColor
        canvas.drawCircle(width / 2f, height / 2f, radius_complete, paint)

        paint.color = getDefaultColor()
        canvas.drawCircle(width / 2f, height / 2f, radius_dead_zone, paint)

        when (stick_state) {
            STICK_STATE.NO_MOVEMENT -> {
                paint.color = getDefaultColor()
                canvas.drawCircle(width / 2f, height / 2f, radius_analog_stick, paint)
            }
            STICK_STATE.MOVED_IN_DEAD_ZONE,
            STICK_STATE.MOVED_ACTIVE -> {
                paint.color = pressedColor
                canvas.drawCircle(position_stick_x, position_stick_y, radius_analog_stick, paint)
            }
        }
    }

    private fun updatePosition(eventTime: Long) {
        val complete = radius_complete - radius_analog_stick
        val correlatedY = (sin(Math.PI / 2 - movement_angle) * movement_radius).toFloat()
        val correlatedX = (cos(Math.PI / 2 - movement_angle) * movement_radius).toFloat()

        position_stick_x = width / 2f - correlatedX
        position_stick_y = height / 2f - correlatedY

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
        relative_x = -(width / 2f - event.x)
        relative_y = -(height / 2f - event.y)
        movement_radius = getMovementRadius(relative_x, relative_y)
        movement_angle = getAngle(relative_x, relative_y)

        if (movement_radius > radius_complete && !isPressed) {
            return false
        }

        if (movement_radius > radius_complete - radius_analog_stick) {
            movement_radius = (radius_complete - radius_analog_stick).toDouble()
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stick_state = STICK_STATE.MOVED_IN_DEAD_ZONE
                if (lastClickState == CLICK_STATE.SINGLE && event.eventTime - timeLastClick <= timeoutDoubleClick) {
                    click_state = CLICK_STATE.DOUBLE
                    notifyOnDoubleClick()
                } else {
                    click_state = CLICK_STATE.SINGLE
                    notifyOnClick()
                }
                timeLastClick = event.eventTime
                isPressed = true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> isPressed = false
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
            if (way_x == 0f) {
                return if (way_y < 0) Math.PI else 0.0
            } else if (way_y == 0f) {
                if (way_x > 0) return Math.PI * 3 / 2
                if (way_x < 0) return Math.PI / 2
            }
            return if (way_x > 0) {
                if (way_y < 0) {
                    3 * Math.PI / 2 + atan((-way_y / way_x).toDouble())
                } else {
                    Math.PI + atan((way_x / way_y).toDouble())
                }
            } else {
                if (way_y > 0) {
                    Math.PI / 2 + atan((way_y / -way_x).toDouble())
                } else {
                    atan((-way_x / -way_y).toDouble())
                }
            }
        }
    }
}
