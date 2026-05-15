package com.papi.nova.binding.input.virtual_controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.papi.nova.preferences.PreferenceConfiguration

open class DigitalButton(
    controller: VirtualController,
    elementId: Int,
    private val layer: Int,
    context: Context
) : VirtualControllerElement(controller, context, elementId) {
    interface DigitalButtonListener {
        fun onClick()
        fun onLongClick()
        fun onRelease()
    }

    private val listeners = ArrayList<DigitalButtonListener>()
    private var text = ""
    private var icon = -1
    private var iconPress = -1
    private val timerLongClickTimeout = 3000L
    private val longClickRunnable = Runnable { onLongClickCallback() }
    private val paint = Paint()
    private val rect = RectF()
    private var movingButton: DigitalButton? = null

    fun inRange(x: Float, y: Float): Boolean =
        getX() < x && getX() + width > x && getY() < y && getY() + height > y

    fun checkMovement(x: Float, y: Float, movingButton: DigitalButton): Boolean {
        if (movingButton.layer != layer) {
            return false
        }

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
        val controller = virtualController ?: return
        for (element in controller.getElements()) {
            if (element !== this && element is DigitalButton) {
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

    fun setIconPress(iconPress: Int) {
        this.iconPress = iconPress
    }

    override fun onElementDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT)

        paint.textSize = getPercent(width, 25)
        paint.textAlign = Paint.Align.CENTER
        paint.strokeWidth = getDefaultStrokeWidth().toFloat()
        paint.color = if (isPressed) pressedColor else getDefaultColor()

        rect.left = paint.strokeWidth
        rect.top = paint.strokeWidth
        rect.right = width - rect.left
        rect.bottom = height - rect.top

        val config = PreferenceConfiguration.readPreferences(context)
        if (config.enableOnScreenStyleOfficial) {
            paint.style = Paint.Style.STROKE
            if (config.enableKeyboardSquare) {
                canvas.drawRect(rect, paint)
            } else {
                canvas.drawOval(rect, paint)
            }
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.strokeWidth = getDefaultStrokeWidth() / 2f
            canvas.drawText(text, getPercent(width, 50), getPercent(height, 63), paint)
            return
        }

        val oscOpacity = config.oscOpacity
        if (icon != -1) {
            val drawable = resources.getDrawable(if (isPressed) iconPress else icon)
            drawable.setBounds(5, 5, width - 5, height - 5)
            drawable.alpha = (oscOpacity * 2.55).toInt()
            drawable.draw(canvas)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = getDefaultStrokeWidth() / 2f
            canvas.drawText(text, getPercent(width, 50), getPercent(height, 63), paint)
        }

        val controllerMode = virtualController?.getControllerMode()
        val bIsMoving = controllerMode == VirtualController.ControllerMode.MoveButtons
        val bIsResizing = controllerMode == VirtualController.ControllerMode.ResizeButtons
        val bIsEnable = controllerMode == VirtualController.ControllerMode.DisableEnableButtons

        if (bIsMoving || bIsResizing || bIsEnable || icon == -1) {
            paint.style = Paint.Style.STROKE
            canvas.drawRect(rect, paint)
        }
    }

    private fun onClickCallback() {
        _DBG("clicked")
        for (listener in listeners) {
            listener.onClick()
        }

        virtualController?.getHandler()?.removeCallbacks(longClickRunnable)
        virtualController?.getHandler()?.postDelayed(longClickRunnable, timerLongClickTimeout)
    }

    private fun onLongClickCallback() {
        _DBG("long click")
        for (listener in listeners) {
            listener.onLongClick()
        }
    }

    private fun onReleaseCallback() {
        _DBG("released")
        for (listener in listeners) {
            listener.onRelease()
        }
        virtualController?.getHandler()?.removeCallbacks(longClickRunnable)
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        val x = getX() + event.x
        val y = getY() + event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                movingButton = null
                isPressed = true
                onClickCallback()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                checkMovementForAllButtons(x, y)
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isPressed = false
                onReleaseCallback()
                checkMovementForAllButtons(x, y)
                invalidate()
                return true
            }
        }
        return true
    }
}
