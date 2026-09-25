package com.papi.nova.binding.audio

/**
 * Bound the combined decoder/output queue. After a delivery gap, a decoder
 * backlog can refill an empty AudioTrack without adding the same delay twice.
 */
internal object AudioQueueBudget {
    fun shouldSkip(
        pendingMs: Int,
        writtenFrames: Long,
        playbackHead: Int,
        bufferFrames: Int,
        sampleRate: Int,
    ): Boolean {
        if (pendingMs < 40) return false
        if (bufferFrames <= 0 || sampleRate <= 0) return true
        // AudioTrack exposes an unsigned 32-bit playback cursor. Subtraction
        // modulo 2^32 also works after the cursor wraps on a long stream.
        val queuedFrames = ((writtenFrames - (playbackHead.toLong() and 0xffff_ffffL)) and 0xffff_ffffL)
            .coerceAtMost(bufferFrames.toLong())
        val queuedMs = queuedFrames * 1000L / sampleRate
        val outputBudgetMs = bufferFrames.toLong() * 1000L / sampleRate
        return pendingMs.toLong() + queuedMs >= 40L + outputBudgetMs
    }
}
