package com.papi.nova.ui

enum class NovaHudCorner(val label: String, internal val x: Float, internal val y: Float) {
    TOP_LEFT("top left", 0f, 0f), TOP_RIGHT("top right", 1f, 0f),
    BOTTOM_LEFT("bottom left", 0f, 1f), BOTTOM_RIGHT("bottom right", 1f, 1f)
}

/** Coordinates of the HUD's top-left corner, inside the usable content surface. */
internal data class NovaHudPositionBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun clamp(x: Float, y: Float) =
        (if (x.isFinite()) x.coerceIn(left, right) else left) to
            (if (y.isFinite()) y.coerceIn(top, bottom) else top)

    fun fromFractions(x: Float, y: Float) = clamp(left + x * (right - left), top + y * (bottom - top))

    fun toFractions(x: Float, y: Float): Pair<Float, Float> {
        val clamped = clamp(x, y)
        return (if (right > left) (clamped.first - left) / (right - left) else 0f) to
            (if (bottom > top) (clamped.second - top) / (bottom - top) else 0f)
    }

    companion object {
        fun forSurface(width: Int, height: Int, hudWidth: Int, hudHeight: Int,
                       marginX: Float, marginY: Float, insetLeft: Int = 0, insetTop: Int = 0,
                       insetRight: Int = 0, insetBottom: Int = 0): NovaHudPositionBounds {
            val left = insetLeft + marginX
            val top = insetTop + marginY
            return NovaHudPositionBounds(left, top,
                (width - hudWidth - insetRight - marginX).coerceAtLeast(left),
                (height - hudHeight - insetBottom - marginY).coerceAtLeast(top))
        }
    }
}
