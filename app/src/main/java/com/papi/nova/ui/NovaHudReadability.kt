package com.papi.nova.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Preserve theme hue but guarantee small-text contrast even over an all-white game frame. */
internal object NovaHudReadability {
    val backing = Color.Black.copy(alpha = 0.86f)
    private val brightestBackground = backing.compositeOver(Color.White)

    fun foreground(themeColor: Color): Color {
        val opaque = themeColor.copy(alpha = 1f)
        if (contrast(opaque, brightestBackground) >= 4.5f) return opaque
        var result = opaque
        for (step in 1..20) {
            result = lerp(opaque, Color.White, step / 20f)
            if (contrast(result, brightestBackground) >= 4.5f) break
        }
        return result
    }

    fun contrast(a: Color, b: Color): Float {
        val light = maxOf(a.luminance(), b.luminance())
        val dark = minOf(a.luminance(), b.luminance())
        return (light + 0.05f) / (dark + 0.05f)
    }
}
