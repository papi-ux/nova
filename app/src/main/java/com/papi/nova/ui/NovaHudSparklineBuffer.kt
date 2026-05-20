package com.papi.nova.ui

internal class NovaHudSparklineBuffer(private val capacity: Int = 60) {
    private val values = FloatArray(capacity)
    private val scratch = FloatArray(capacity)
    private var nextIndex = 0
    private var sampleCount = 0

    fun add(value: Float) {
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

    fun lowOnePercent(): Double {
        if (sampleCount < 3) {
            return 0.0
        }
        for (i in 0 until sampleCount) {
            scratch[i] = valueAt(i)
        }
        java.util.Arrays.sort(scratch, 0, sampleCount)
        val index = (sampleCount * 0.01f).toInt().coerceIn(0, sampleCount - 1)
        return scratch[index].toDouble()
    }

    private fun valueAt(offset: Int): Float {
        val start = if (sampleCount == capacity) nextIndex else 0
        return values[(start + offset) % capacity]
    }
}
