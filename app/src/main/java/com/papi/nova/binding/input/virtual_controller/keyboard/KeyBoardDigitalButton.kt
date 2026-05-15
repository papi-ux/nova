package com.papi.nova.binding.input.virtual_controller.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.papi.nova.preferences.PreferenceConfiguration

class KeyBoardDigitalButton(
    controller: KeyBoardController,
    elementId: String,
    private val layer: Int,
    context: Context
) : keyBoardVirtualControllerElement(controller, context, elementId) {
    interface DigitalButtonListener {
        fun onClick()
        fun onLongClick()
        fun onRelease()
    }

    private val listeners = ArrayList<DigitalButtonListener>()
    private var text = ""
    private var icon = -1
    private val timerLongClickTimeout = 300L
    private val longClickRunnable = Runnable { onLongClickCallback() }
    private val paint = Paint()
    private val rect = RectF()
    private var movingButton: KeyBoardDigitalButton? = null
    private var sticky = false
    private var switchDown = false
    private var enableSwitchDown = false

    fun inRange(x: Float, y: Float): Boolean =
        getX() < x && getX() + width > x && getY() < y && getY() + height > y

    fun checkMovement(x: Float, y: Float, movingButton: KeyBoardDigitalButton): Boolean {
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
            if (element !== this && element is KeyBoardDigitalButton) {
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

    fun setSticky(sticky: Boolean) {
        this.sticky = sticky
    }

    fun isSticky(): Boolean = sticky

    override fun onElementDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT)

        paint.textSize = getPercent(width, 25)
        paint.textAlign = Paint.Align.CENTER
        paint.strokeWidth = getDefaultStrokeWidth().toFloat()

        val shouldSetPressed = isPressed || isSticky()
        paint.color = if (shouldSetPressed) pressedColor else getDefaultColor()
        paint.style = if (shouldSetPressed) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE

        rect.left = paint.strokeWidth
        rect.top = paint.strokeWidth
        rect.right = width - rect.left
        rect.bottom = height - rect.top

        if (PreferenceConfiguration.readPreferences(context).enableKeyboardSquare) {
            canvas.drawRect(rect, paint)
        } else {
            canvas.drawOval(rect, paint)
        }

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

    private fun onReleaseCallback() {
        _DBG("released")
        for (listener in listeners) listener.onRelease()
        virtualController.getHandler().removeCallbacks(longClickRunnable)
    }

    fun setEnableSwitchDown(enableSwitchDown: Boolean) {
        this.enableSwitchDown = enableSwitchDown
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
                if (enableSwitchDown) switchDown = !switchDown
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                checkMovementForAllButtons(x, y)
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                if (enableSwitchDown && switchDown) return true
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
