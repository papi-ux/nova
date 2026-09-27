package com.papi.nova.binding.input

/** Fractional pointer travel belonging to one controller stick. */
internal class ControllerMouseMotion {
    private var remainderX = 0.0
    private var remainderY = 0.0

    fun reset() {
        remainderX = 0.0
        remainderY = 0.0
    }

    fun accumulate(x: Double, y: Double): Pair<Short, Short> {
        // Neutral (including the configured stick deadzone) and a direction
        // change must not carry old travel into a new gesture.
        if (x == 0.0 || x * remainderX < 0.0) remainderX = 0.0
        if (y == 0.0 || y * remainderY < 0.0) remainderY = 0.0
        remainderX += x
        remainderY += y
        val wholeX = remainderX.toInt()
        val wholeY = remainderY.toInt()
        remainderX -= wholeX
        remainderY -= wholeY
        return wholeX.toShort() to wholeY.toShort()
    }
}
