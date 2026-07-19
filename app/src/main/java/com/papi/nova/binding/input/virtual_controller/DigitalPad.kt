package com.papi.nova.binding.input.virtual_controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import com.papi.nova.R
import com.papi.nova.preferences.PreferenceConfiguration
import kotlin.math.roundToInt

class DigitalPad(controller: VirtualController, context: Context) :
    VirtualControllerElement(controller, context, EID_DPAD) {
    var direction = DIGITAL_PAD_DIRECTION_NO_DIRECTION
    private val listeners = ArrayList<DigitalPadListener>()
    private val rect = RectF()
    private val paint = Paint()

    fun addDigitalPadListener(listener: DigitalPadListener) {
        listeners.add(listener)
    }

    override fun onElementDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT)

        paint.textSize = getPercent(getCorrectWidth(), 20).toFloat()
        paint.textAlign = Paint.Align.CENTER
        paint.strokeWidth = getDefaultStrokeWidth().toFloat()

        val config = PreferenceConfiguration.readPreferences(context)
        if (!config.enableOnScreenStyleOfficial) {
            val oscOpacity = config.oscOpacity
            paint.color = if (isPressed) pressedColor else getDefaultColor()
            rect.left = paint.strokeWidth
            rect.top = paint.strokeWidth
            rect.right = width - rect.left
            rect.bottom = height - rect.top

            val controllerMode = virtualController?.getControllerMode()
            val bIsMoving = controllerMode == VirtualController.ControllerMode.MoveButtons
            val bIsResizing = controllerMode == VirtualController.ControllerMode.ResizeButtons
            val bIsEnable = controllerMode == VirtualController.ControllerMode.DisableEnableButtons

            if (bIsMoving || bIsResizing || bIsEnable) {
                paint.style = Paint.Style.STROKE
                canvas.drawRect(rect, paint)
            }

            drawSkinnedDpad(canvas, oscOpacity)
            return
        }

        if (direction == DIGITAL_PAD_DIRECTION_NO_DIRECTION) {
            paint.style = Paint.Style.STROKE
            paint.color = getDefaultColor()
            canvas.drawRect(
                getPercent(width, 36),
                getPercent(height, 36),
                getPercent(width, 63),
                getPercent(height, 63),
                paint
            )
        }

        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_LEFT) > 0) pressedColor else getDefaultColor()
        paint.style = Paint.Style.STROKE
        canvas.drawRect(
            paint.strokeWidth + DPAD_MARGIN,
            getPercent(height, 33),
            getPercent(width, 33),
            getPercent(height, 66),
            paint
        )

        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_UP) > 0) pressedColor else getDefaultColor()
        canvas.drawRect(
            getPercent(width, 33),
            paint.strokeWidth + DPAD_MARGIN,
            getPercent(width, 66),
            getPercent(height, 33),
            paint
        )

        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_RIGHT) > 0) pressedColor else getDefaultColor()
        canvas.drawRect(
            getPercent(width, 66),
            getPercent(height, 33),
            width - (paint.strokeWidth + DPAD_MARGIN),
            getPercent(height, 66),
            paint
        )

        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_DOWN) > 0) pressedColor else getDefaultColor()
        canvas.drawRect(
            getPercent(width, 33),
            getPercent(height, 66),
            getPercent(width, 66),
            height - (paint.strokeWidth + DPAD_MARGIN),
            paint
        )

        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_LEFT) > 0 && (direction and DIGITAL_PAD_DIRECTION_UP) > 0) pressedColor else getDefaultColor()
        canvas.drawLine(
            paint.strokeWidth + DPAD_MARGIN,
            getPercent(height, 33),
            getPercent(width, 33),
            paint.strokeWidth + DPAD_MARGIN,
            paint
        )

        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_UP) > 0 && (direction and DIGITAL_PAD_DIRECTION_RIGHT) > 0) pressedColor else getDefaultColor()
        canvas.drawLine(
            getPercent(width, 66),
            paint.strokeWidth + DPAD_MARGIN,
            width - (paint.strokeWidth + DPAD_MARGIN),
            getPercent(height, 33),
            paint
        )

        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_RIGHT) > 0 && (direction and DIGITAL_PAD_DIRECTION_DOWN) > 0) pressedColor else getDefaultColor()
        canvas.drawLine(
            width - paint.strokeWidth,
            getPercent(height, 66),
            getPercent(width, 66),
            height - (paint.strokeWidth + DPAD_MARGIN),
            paint
        )

        paint.color = if ((direction and DIGITAL_PAD_DIRECTION_DOWN) > 0 && (direction and DIGITAL_PAD_DIRECTION_LEFT) > 0) pressedColor else getDefaultColor()
        canvas.drawLine(
            getPercent(width, 33),
            height - (paint.strokeWidth + DPAD_MARGIN),
            paint.strokeWidth + DPAD_MARGIN,
            getPercent(height, 66),
            paint
        )
    }

    private fun drawSkinnedDpad(canvas: Canvas, oscOpacity: Int) {
        fun draw(resId: Int, angle: Float? = null) {
            val original = resources.getDrawable(resId)
            val drawable = if (angle != null) rotateDrawable(original, angle) else original
            drawable.setBounds(5, 5, width - 5, height - 5)
            drawable.alpha = skinnedDpadAlpha(oscOpacity)
            drawable.draw(canvas)
        }

        when (direction) {
            DIGITAL_PAD_DIRECTION_NO_DIRECTION -> draw(R.drawable.facebutton_dpad)
            DIGITAL_PAD_DIRECTION_UP -> draw(R.drawable.facebutton_dpad_up)
            DIGITAL_PAD_DIRECTION_DOWN -> draw(R.drawable.facebutton_dpad_up, 180f)
            DIGITAL_PAD_DIRECTION_LEFT -> draw(R.drawable.facebutton_dpad_up, 270f)
            DIGITAL_PAD_DIRECTION_RIGHT -> draw(R.drawable.facebutton_dpad_up, 90f)
        }

        if ((direction and DIGITAL_PAD_DIRECTION_RIGHT) > 0 && (direction and DIGITAL_PAD_DIRECTION_UP) > 0) draw(R.drawable.facebutton_dpad_up_right, 90f)
        if ((direction and DIGITAL_PAD_DIRECTION_LEFT) > 0 && (direction and DIGITAL_PAD_DIRECTION_UP) > 0) draw(R.drawable.facebutton_dpad_up_right)
        if ((direction and DIGITAL_PAD_DIRECTION_RIGHT) > 0 && (direction and DIGITAL_PAD_DIRECTION_DOWN) > 0) draw(R.drawable.facebutton_dpad_up_right, 180f)
        if ((direction and DIGITAL_PAD_DIRECTION_LEFT) > 0 && (direction and DIGITAL_PAD_DIRECTION_DOWN) > 0) draw(R.drawable.facebutton_dpad_up_right, 270f)
    }

    fun rotateDrawable(vectorDrawable: Drawable, angle: Float): Drawable {
        val width = vectorDrawable.intrinsicWidth
        val height = vectorDrawable.intrinsicHeight
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        vectorDrawable.setBounds(0, 0, canvas.width, canvas.height)
        vectorDrawable.draw(canvas)

        val matrix = Matrix()
        matrix.postRotate(angle)
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return BitmapDrawable(resources, rotatedBitmap)
    }

    private fun newDirectionCallback(direction: Int) {
        _DBG("direction: $direction")
        for (listener in listeners) {
            listener.onDirectionChange(direction)
        }
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                direction = 0

                if (event.x < getPercent(width, 33)) direction = direction or DIGITAL_PAD_DIRECTION_LEFT
                if (event.x > getPercent(width, 66)) direction = direction or DIGITAL_PAD_DIRECTION_RIGHT
                if (event.y > getPercent(height, 66)) direction = direction or DIGITAL_PAD_DIRECTION_DOWN
                if (event.y < getPercent(height, 33)) direction = direction or DIGITAL_PAD_DIRECTION_UP

                newDirectionCallback(direction)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                direction = 0
                newDirectionCallback(direction)
                invalidate()
                return true
            }
        }

        return true
    }

    interface DigitalPadListener {
        fun onDirectionChange(direction: Int)
    }

    companion object {
        const val DIGITAL_PAD_DIRECTION_NO_DIRECTION = 0
        const val DIGITAL_PAD_DIRECTION_LEFT = 1
        const val DIGITAL_PAD_DIRECTION_UP = 2
        const val DIGITAL_PAD_DIRECTION_RIGHT = 4
        const val DIGITAL_PAD_DIRECTION_DOWN = 8
        private const val DPAD_MARGIN = 5

        internal fun skinnedDpadAlpha(oscOpacity: Int): Int =
            (oscOpacity.coerceIn(0, 100) * 2.55f).roundToInt().coerceIn(0, 255)
    }
}
