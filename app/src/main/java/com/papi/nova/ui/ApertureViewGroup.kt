package com.papi.nova.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.Property
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import com.papi.nova.R
import com.papi.nova.utils.UiHelper

class ApertureViewGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private var color1Value = 0
    private var color2Value = 0
    private var borderWidth = 0f
    private var borderAngle = 0f
    private var duration = 0
    private var middleColor = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rectF: RectF? = null
    private var color1: LinearGradient? = null
    private var color2: LinearGradient? = null
    private var animator: ObjectAnimator? = null

    var currentSpeed: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    init {
        initialize(context, attrs)
    }

    private fun initialize(context: Context, attrs: AttributeSet?) {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, borderAngle)
            }
        }
        clipToOutline = true

        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.ApertureViewGroup)
        try {
            color1Value = typedArray.getColor(R.styleable.ApertureViewGroup_aperture_color1, Color.YELLOW)
            color2Value = typedArray.getColor(R.styleable.ApertureViewGroup_aperture_color2, -1)
            borderWidth = typedArray.getDimension(
                R.styleable.ApertureViewGroup_aperture_border_width,
                UiHelper.dpToPx(context, 20f)
            )
            borderAngle = typedArray.getDimension(
                R.styleable.ApertureViewGroup_aperture_border_angle,
                UiHelper.dpToPx(context, 20f)
            )
            duration = typedArray.getInt(R.styleable.ApertureViewGroup_aperture_duration, 3000)
            middleColor = typedArray.getColor(R.styleable.ApertureViewGroup_aperture_middle_color, Color.BLACK)
        } finally {
            typedArray.recycle()
        }

        animator = ObjectAnimator.ofFloat(this, CURRENT_SPEED_PROPERTY, 0f, 360f).apply {
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = null
            duration = this@ApertureViewGroup.duration.toLong()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (rectF == null) {
            val left = borderWidth / 2f
            val top = borderWidth / 2f
            val right = left + w - borderWidth
            val bottom = top + h - borderWidth
            rectF = RectF(left, top, right, bottom)
        }

        if (color1 == null) {
            color1 = LinearGradient(
                w * 1f,
                h / 2f,
                w * 1f,
                h * 1f,
                intArrayOf(Color.TRANSPARENT, color1Value),
                floatArrayOf(0f, 0.9f),
                Shader.TileMode.CLAMP
            )
        }

        if (color2 == null && color2Value != -1) {
            color2 = LinearGradient(
                w / 2f,
                h / 2f,
                w / 2f,
                0f,
                intArrayOf(Color.TRANSPARENT, color2Value),
                floatArrayOf(0f, 0.9f),
                Shader.TileMode.CLAMP
            )
        }

        animator?.start()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val left1 = width / 2f
        val top1 = height / 2f
        val right1 = left1 + width
        val bottom1 = top1 + height

        canvas.save()
        canvas.rotate(currentSpeed, width / 2f, height / 2f)

        paint.shader = color1
        canvas.drawRect(left1, top1, right1, bottom1, paint)
        paint.shader = null

        if (color2Value != -1) {
            paint.shader = color2
            canvas.drawRect(left1, top1, -right1, -bottom1, paint)
            paint.shader = null
        }

        paint.color = middleColor
        rectF?.let { canvas.drawRoundRect(it, borderAngle, borderAngle, paint) }

        canvas.restore()
        super.dispatchDraw(canvas)
    }

    private companion object {
        val CURRENT_SPEED_PROPERTY = object : Property<ApertureViewGroup, Float>(
            Float::class.java,
            "currentSpeed"
        ) {
            override fun get(view: ApertureViewGroup): Float = view.currentSpeed

            override fun set(view: ApertureViewGroup, value: Float?) {
                view.currentSpeed = value ?: 0f
            }
        }
    }
}
