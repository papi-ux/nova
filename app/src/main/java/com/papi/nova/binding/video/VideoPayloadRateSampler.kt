package com.papi.nova.binding.video

import com.papi.nova.nvstream.jni.MoonBridge

/** Complete encoded payload at the receiver bridge, not wire traffic or capacity.
 * A new native connection or an unavailable/invalid interval starts a new baseline. */
internal class VideoPayloadRateSampler {
    private var previous: LongArray? = null

    fun sample(snapshot: LongArray?): Double? {
        if (snapshot == null || snapshot.size != 3 ||
            snapshot[0] <= 0 || snapshot[1] < 0 || snapshot[2] < 0) {
            previous = null
            return null
        }
        val before = previous
        if (before == null || snapshot[0] != before[0] ||
            snapshot[1] < before[1] || snapshot[2] < before[2]) {
            previous = snapshot.copyOf()
            return null
        }
        val elapsedMs = snapshot[2] - before[2]
        // Renderers sample about once per second. Reject duplicate/short polling
        // without turning ordinary millisecond scheduling jitter into unavailable.
        if (elapsedMs < 500) return null
        previous = snapshot.copyOf()
        if (elapsedMs > 5000) return null
        // Bits per millisecond are kbps. Subtract integer counters before conversion.
        return ((snapshot[1] - before[1]).toDouble() * 8.0 / elapsedMs.toDouble())
            .takeIf { it.isFinite() && it >= 0.0 }
    }
}

/** Optional observation only; native-free unit environments have no measurement. */
internal fun readVideoPayloadSnapshot(): LongArray? = try {
    MoonBridge.getVideoPayloadStats()
} catch (_: LinkageError) {
    null
}
