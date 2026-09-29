package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus

/**
 * The legacy Moonlight connection warning over the stream, "Slow connection to PC / Reduce your
 * bitrate". Where the host's Live Tuning owns the bitrate, or Polaris's Doctor reads the stream,
 * it contradicted both: Live Tuning was already lowering the bitrate on its own, and Doctor said
 * at the same moment not to lower quality from that observation alone (in-game #9). There it stays
 * hidden, and the Command Center and the HUD say what the host sees. Everywhere else it shows as
 * it always has.
 */
object NovaLegacyConnectionWarning {
    fun suppressed(status: PolarisSessionStatus?): Boolean {
        if (status == null) return false
        val liveTuningOn = status.liveTuning?.enabled
            ?: (status.tuning.adaptiveBitrateEnabled || status.adaptiveBitrateEnabled)
        return liveTuningOn || status.hasAuthoritativeDoctorResult
    }
}
