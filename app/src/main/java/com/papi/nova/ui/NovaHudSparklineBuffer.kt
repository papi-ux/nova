package com.papi.nova.ui

internal class NovaHudSparklineBuffer(private val capacity: Int = 60) {
    private val values = FloatArray(capacity)
    private var nextIndex = 0
    private var sampleCount = 0

    fun add(value: Float) {
        if (!value.isFinite() || value < 0f) return
        values[nextIndex] = value
        nextIndex = (nextIndex + 1) % capacity
        if (sampleCount < capacity) {
            sampleCount++
        }
    }

    fun clear() {
        nextIndex = 0
        sampleCount = 0
    }

    fun snapshot(): List<Float> {
        val output = ArrayList<Float>(sampleCount)
        for (i in 0 until sampleCount) {
            output.add(valueAt(i))
        }
        return output
    }

    // Source-compatible accessor: a minimum of periodic FPS samples, not frame-time 1% low.
    fun lowOnePercent(): Double {
        if (sampleCount == 0) return Double.NaN
        var minimum = Float.POSITIVE_INFINITY
        for (i in 0 until sampleCount) minimum = minOf(minimum, valueAt(i))
        return minimum.toDouble()
    }

    private fun valueAt(offset: Int): Float {
        val start = if (sampleCount == capacity) nextIndex else 0
        return values[(start + offset) % capacity]
    }
}
