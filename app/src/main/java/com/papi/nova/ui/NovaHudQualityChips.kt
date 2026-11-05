package com.papi.nova.ui

import androidx.compose.runtime.Immutable
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.binding.video.sourcetime.PresentationTruth
import kotlin.math.roundToInt

/** A short fact the stream is running with, and the sentence a screen reader says for it. */
@Immutable
data class NovaHudQualityChip(val label: String, val description: String)

/**
 * The HUD's quality chips, beside the codec it already shows.
 *
 * A chip is made only from a fact that is current, about this device's own stream, and
 * true. Anything unknown, stale or about another session makes no chip, so an older host
 * that does not report a fact simply shows none.
 */
object NovaHudQualityChips {
    /**
     * @param status the host status the HUD holds as current: answered within its freshness
     *   window, for an active stream. Null otherwise.
     * @param displayModeHz the refresh rate of the mode the streaming display is in now, read
     *   on this device while video is arriving. Null otherwise.
     */
    fun from(
        status: PolarisSessionStatus?,
        displayModeHz: Float? = null,
        presentation: PresentationTruth? = null,
    ): List<NovaHudQualityChip> =
        listOfNotNull(hdrChip(status), displayChip(displayModeHz)) + presentationChips(presentation)

    // Host frame timing (#242), from the shared reducer, only while video is arriving. Source-paced
    // is this device's own scheduling; it is never called VRR. Variable refresh is requested only
    // when this device asked, and active only with every piece of evidence at once. A fallback is
    // shown only to a player who turned the setting on explicitly; an automatic one stays in the
    // Command Center.
    private fun presentationChips(truth: PresentationTruth?): List<NovaHudQualityChip> {
        if (truth == null) return emptyList()
        return listOfNotNull(
            NovaHudQualityChip("Source-paced", "Frames shown on the host's timing").takeIf { truth.sourcePaced },
            truth.targetDelayMs?.takeIf { truth.sourcePaced }?.let {
                val ms = it.roundToInt()
                NovaHudQualityChip("Delay $ms ms", "Frames shown $ms milliseconds after their mapped host time")
            },
            NovaHudQualityChip("VRR requested", "Variable refresh requested").takeIf { truth.vrrRequested },
            NovaHudQualityChip("VRR active", "Variable refresh active").takeIf { truth.vrrActive },
            truth.fallback?.takeIf { truth.prominent && !truth.sourcePaced }?.let {
                val words = PresentationTruth.plain(it)
                NovaHudQualityChip("Source pacing: $words", "Host frame timing not in use: $words")
            },
        )
    }

    // Polaris's effective mode for the stream it encodes is hdr10, sdr_10bit or sdr_8bit.
    // Only hdr10 is HDR, and it describes the host's stream, not this panel. A downgrade
    // reported beside it contradicts it, and a contradiction makes no chip.
    private fun hdrChip(status: PolarisSessionStatus?): NovaHudQualityChip? {
        // The host describes the stream it serves its owner. A session this device only
        // watches, or one the host does not say is this device's, is not the stream here.
        if (status == null || !status.authorityContractValid || !status.ownedByClient ||
            !status.clientRole.equals("owner", ignoreCase = true) || status.isShuttingDown) {
            return null
        }
        return NovaHudQualityChip("HDR10", "Host stream: HDR10").takeIf {
            status.health.hdrEffectiveMode.trim().equals("hdr10", ignoreCase = true) && !status.isHdrDowngraded
        }
    }

    // The mode the display is set to, not frames shown, VRR or measured scanout, and the chip
    // says so: a bare "120 Hz" reads as the stream's own rate. The host's copy of the client
    // presentation report is never used: Polaris keeps it per device across sessions, so the
    // first status of a stream can carry the last stream's rate.
    private fun displayChip(displayModeHz: Float?): NovaHudQualityChip? {
        val hz = displayModeHz?.takeIf { it.isFinite() && it > 0f }?.roundToInt()?.takeIf { it > 0 } ?: return null
        return NovaHudQualityChip("Display mode $hz Hz", "Display mode: $hz hertz")
    }
}
