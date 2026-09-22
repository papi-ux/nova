package com.papi.nova.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.appcompat.content.res.AppCompatResources
import com.papi.nova.R

/**
 * The poster of a game that has none: the placeholder every such game shares, with its name on it.
 *
 * Box art names its game, so posters carry no text of their own and the poster card draws none
 * over them. A title the host has no artwork for would otherwise be the same blank tile as every
 * other one, and a Heroic or Lutris library was a row of tiles nobody could tell apart. This is
 * that game's poster, so it shows wherever a poster does and the artwork stays free of overlays.
 */
internal class NovaTitleCardDrawable(context: Context, val title: String) : Drawable() {
    private val backdrop = AppCompatResources.getDrawable(context, R.drawable.nova_cover_placeholder)?.mutate()
    private val density = context.resources.displayMetrics.density
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4EEF8.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val scrim = Paint()

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        backdrop?.bounds = area
        backdrop?.draw(canvas)
        if (title.isBlank()) return
        val inset = area.width() * INSET
        text.textSize = (area.width() * TEXT_SIZE).coerceIn(MIN_TEXT_SP * density, MAX_TEXT_SP * density)
        val width = (area.width() - 2 * inset).toInt().coerceAtLeast(1)
        val layout = nameLayout(width)
        val top = area.bottom - inset - layout.height
        // The name sits on the placeholder's own dark end, deepened so it reads on any theme.
        scrim.shader = LinearGradient(
            0f, top - 2 * inset, 0f, area.bottom.toFloat(),
            0x00000000, 0xD90B0814.toInt(), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(area.left.toFloat(), top - 2 * inset, area.right.toFloat(), area.bottom.toFloat(), scrim)
        canvas.save()
        canvas.translate(area.left + inset, top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun nameLayout(width: Int): StaticLayout {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(title, 0, title.length, text, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(MAX_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
        }
        // Android 5 has no builder and no line limit, so the name is cut to what the lines hold first.
        val fitted = TextUtils.ellipsize(title, text, width * MAX_LINES.toFloat(), TextUtils.TruncateAt.END)
        @Suppress("DEPRECATION")
        return StaticLayout(fitted, text, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
    }

    override fun setAlpha(alpha: Int) {
        backdrop?.alpha = alpha
        text.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        backdrop?.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val INSET = 0.08f
        const val TEXT_SIZE = 0.11f
        const val MIN_TEXT_SP = 11f
        const val MAX_TEXT_SP = 22f
        const val MAX_LINES = 3
    }
}
