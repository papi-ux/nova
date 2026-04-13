package com.papi.nova.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Tiny sparkline chart for the performance HUD.
 * Renders a line + fill of the last N data points.
 */
class SparklineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val data = FloatArray(60)
    private var writeIndex = 0
    private var filled = false

    var lineColor: Int = 0xFF4ade80.toInt()
        set(value) {
            field = value
            linePaint.color = value
            fillPaint.color = (value and 0x00FFFFFF) or 0x20000000
            invalidate()
        }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (lineColor and 0x00FFFFFF) or 0x20000000
        style = Paint.Style.FILL
    }

    private val linePath = Path()
    private val fillPath = Path()

    fun push(value: Float) {
        data[writeIndex] = value
        writeIndex = (writeIndex + 1) % data.size
        if (writeIndex == 0) filled = true
        invalidate()
    }

    /** Get the 1% low value — the lowest 1st percentile of the data window. */
    fun get1PercentLow(): Float {
        val count = if (filled) data.size else writeIndex
        if (count < 3) return 0f
        val sorted = FloatArray(count)
        for (i in 0 until count) {
            sorted[i] = data[if (filled) (writeIndex + i) % data.size else i]
        }
        sorted.sort()
        // 1% index: at least index 0
        val idx = (count * 0.01f).toInt().coerceIn(0, count - 1)
        return sorted[idx]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = if (filled) data.size else writeIndex
        if (count < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Find range
        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE
        for (i in 0 until count) {
            val idx = if (filled) (writeIndex + i) % data.size else i
            val v = data[idx]
            if (v < min) min = v
            if (v > max) max = v
        }
        val range = (max - min).coerceAtLeast(5f)
        val stepX = w / (count - 1).coerceAtLeast(1)

        linePath.reset()
        fillPath.reset()

        for (i in 0 until count) {
            val idx = if (filled) (writeIndex + i) % data.size else i
            val x = i * stepX
            val y = h - ((data[idx] - min) / range) * (h - 2f) - 1f
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo((count - 1) * stepX, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }
}
