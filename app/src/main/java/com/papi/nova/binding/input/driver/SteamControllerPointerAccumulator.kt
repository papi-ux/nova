package com.papi.nova.binding.input.driver

/**
 * Turns normalized Steam Controller trackpad deltas into whole host mouse and scroll units.
 *
 * One report's delta is often less than one unit, so each axis keeps its signed fraction and
 * adds it to the next report instead of truncating it away. An emitted value saturates at
 * +/-32767 and the excess is dropped, never released later. A non-finite delta clears that
 * axis's fraction and emits 0. Not thread-safe: the controller serializes calls.
 */
internal class SteamControllerPointerAccumulator {
    private class Axis(private val scale: Double) {
        private var remainder = 0.0

        fun add(delta: Float): Short {
            if (!delta.isFinite()) {
                remainder = 0.0
                return 0
            }

            val total = remainder + delta * scale
            if (total >= LIMIT || total <= -LIMIT) {
                remainder = 0.0
                return (if (total > 0) LIMIT else -LIMIT).toInt().toShort()
            }

            // Truncating toward zero keeps the fraction's sign, so a reversal cancels it
            val whole = total.toInt()
            remainder = total - whole
            return whole.toShort()
        }

        fun reset() {
            remainder = 0.0
        }
    }

    private val x = Axis(POINTER_SCALE)
    private val y = Axis(POINTER_SCALE)
    private val wheel = Axis(SCROLL_SCALE)

    /** Each delta is a fraction of the pad's width or height. */
    fun move(x: Float, y: Float): Pair<Short, Short> = Pair(this.x.add(x), this.y.add(y))

    fun scroll(delta: Float): Short = wheel.add(delta)

    /** On lift or cancellation, so the next touch starts from nothing. */
    fun resetPointer() {
        x.reset()
        y.reset()
    }

    fun resetScroll() {
        wheel.reset()
    }

    fun reset() {
        resetPointer()
        resetScroll()
    }

    private companion object {
        const val POINTER_SCALE = 1200.0
        const val SCROLL_SCALE = 960.0
        const val LIMIT = 32767.0
    }
}
