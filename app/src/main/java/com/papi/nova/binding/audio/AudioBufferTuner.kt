package com.papi.nova.binding.audio

/**
 * Add one decoded packet of headroom only after the device reports a new underrun.
 * The cap bounds the extra playback delay; stable devices keep their initial size.
 */
internal class AudioBufferTuner(
    private val stepFrames: Int,
    private val maxFrames: Int,
) {
    private var previousUnderruns = 0

    init {
        require(stepFrames > 0 && maxFrames > 0)
    }

    fun sizeForUnderruns(underruns: Int, currentFrames: Int): Int {
        if (underruns < 0) return currentFrames
        val starved = underruns > previousUnderruns
        previousUnderruns = underruns
        return if (starved && currentFrames in 1 until maxFrames) {
            (currentFrames.toLong() + stepFrames).coerceAtMost(maxFrames.toLong()).toInt()
        } else {
            currentFrames
        }
    }
}
