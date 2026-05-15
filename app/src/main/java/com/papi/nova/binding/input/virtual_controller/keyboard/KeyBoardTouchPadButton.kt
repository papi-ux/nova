package com.papi.nova.binding.input.virtual_controller.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.MotionEvent
import com.papi.nova.preferences.PreferenceConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt

class KeyBoardTouchPadButton(
    controller: KeyBoardController,
    elementId: String,
    private val layer: Int,
    context: Context
) : keyBoardVirtualControllerElement(controller, context, elementId) {
    interface DigitalButtonListener {
        fun onClick()
        fun onLongClick()
        fun onMove(x: Int, y: Int)
        fun onRelease()
    }

    private val listeners = ArrayList<DigitalButtonListener>()
    private var text = ""
    private var icon = -1
    private val timerLongClickTimeout = 3000L
    private val longClickRunnable = Runnable { onLongClickCallback() }
    private val paint = Paint()
    private val rect = RectF()
    private var movingButton: KeyBoardTouchPadButton? = null
    private var originalTouchTime = 0L
    private var lastTouchX = 0
    private var lastTouchY = 0
    private var xFactor = 0.0
    private var yFactor = 0.0
    private val preferenceConfiguration = PreferenceConfiguration.readPreferences(context)

    private var touchPressedColor = 0x2BF5F5F9

    fun inRange(x: Float, y: Float): Boolean =
        getX() < x && getX() + width > x && getY() < y && getY() + height > y

    fun checkMovement(x: Float, y: Float, movingButton: KeyBoardTouchPadButton): Boolean {
        if (movingButton.layer != layer) return false

        val wasPressed = isPressed
        if ((this.movingButton == null || movingButton === this.movingButton) && inRange(x, y)) {
            if (isPressed != movingButton.isPressed) {
                isPressed = movingButton.isPressed
            }
        } else if (movingButton === this.movingButton) {
            isPressed = false
        }

        if (wasPressed != isPressed) {
            if (isPressed) {
                this.movingButton = movingButton
                onClickCallback()
            } else {
                this.movingButton = null
                onReleaseCallback()
            }
            invalidate()
            return true
        }
        return false
    }

    private fun checkMovementForAllButtons(x: Float, y: Float) {
        for (element in virtualController.getElements()) {
            if (element !== this && element is KeyBoardTouchPadButton) {
                element.checkMovement(x, y, this)
            }
        }
    }

    fun addDigitalButtonListener(listener: DigitalButtonListener) {
        listeners.add(listener)
    }

    fun setText(text: String) {
        this.text = text
        invalidate()
    }

    fun setIcon(id: Int) {
        icon = id
        invalidate()
    }

    override fun onElementDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT)

        paint.textSize = getPercent(width, 25)
        paint.textAlign = Paint.Align.CENTER
        paint.strokeWidth = getDefaultStrokeWidth().toFloat()
        paint.color = if (isPressed) touchPressedColor else getDefaultColor()
        paint.style = if (isPressed) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE

        rect.left = paint.strokeWidth
        rect.top = paint.strokeWidth
        rect.right = width - rect.left
        rect.bottom = height - rect.top

        canvas.drawRect(rect, paint)

        if (icon != -1) {
            val drawable = resources.getDrawable(icon)
            drawable.setBounds(5, 5, width - 5, height - 5)
            drawable.draw(canvas)
        } else {
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.strokeWidth = getDefaultStrokeWidth() / 2f
            canvas.drawText(text, getPercent(width, 50), getPercent(height, 63), paint)
        }
    }

    private fun onClickCallback() {
        _DBG("clicked")
        for (listener in listeners) listener.onClick()
        virtualController.getHandler().removeCallbacks(longClickRunnable)
        virtualController.getHandler().postDelayed(longClickRunnable, timerLongClickTimeout)
    }

    private fun onLongClickCallback() {
        _DBG("long click")
        for (listener in listeners) listener.onLongClick()
    }

    private fun onMoveCallback(x: Int, y: Int) {
        _DBG("move")
        for (listener in listeners) listener.onMove(x, y)
    }

    private fun onReleaseCallback() {
        _DBG("released")
        for (listener in listeners) listener.onRelease()
        virtualController.getHandler().removeCallbacks(longClickRunnable)
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                xFactor = 1280 / width.toDouble()
                yFactor = 720 / height.toDouble()
                lastTouchX = event.x.toInt()
                lastTouchY = event.y.toInt()
                movingButton = null
                originalTouchTime = event.eventTime
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                var deltaX = (event.x - lastTouchX).toInt()
                var deltaY = (event.y - lastTouchY).toInt()
                deltaX = (abs(deltaX) * xFactor).roundToInt()
                deltaY = (abs(deltaY) * yFactor).roundToInt()
                if (event.x < lastTouchX) deltaX = -deltaX
                if (event.y < lastTouchY) deltaY = -deltaY

                if (event.eventTime - originalTouchTime > 100 && !isPressed) {
                    isPressed = true
                    if (TextUtils.equals(elementId, "m_9") || TextUtils.equals(elementId, "m_11")) {
                        onClickCallback()
                    }
                }

                onMoveCallback(
                    (deltaX * 0.01f * preferenceConfiguration.touchPadSensitivity).toInt(),
                    (deltaY * 0.01f * preferenceConfiguration.touchPadYSensitity).toInt()
                )
                if (deltaX != 0) lastTouchX = event.x.toInt()
                if (deltaY != 0) lastTouchY = event.y.toInt()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isPressed = false
                if (event.eventTime - originalTouchTime <= 200) {
                    onClickCallback()
                }
                onReleaseCallback()
                invalidate()
                return true
            }
        }
        return true
    }
}
