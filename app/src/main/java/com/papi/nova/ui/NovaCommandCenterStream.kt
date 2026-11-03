package com.papi.nova.ui

import android.content.Context
import com.papi.nova.R
import com.papi.nova.api.LiveTuningStatus
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.binding.video.PerfOverlaySample
import com.papi.nova.manager.NovaBitrateUnits
import com.papi.nova.manager.NovaLiveBitratePresentation

/** Presentation only. No new measurements, writes, timers or persisted history. */
data class NovaCommandCenterStream(
    val overview: String = "",
    val rates: List<Pair<String, String>> = emptyList(),
    val recent: List<NovaConfirmedStreamChange> = emptyList(),
    val historyAvailable: Boolean = false,
)

data class NovaConfirmedStreamChange(val beforeKbps: Int, val appliedKbps: Int)

/** One observation from Game's existing renderer callback, bound to its connection and host scope. */
internal data class NovaCommandCenterMediaSample(
    val sample: PerfOverlaySample,
    val api: Any,
    val connection: Any,
    val scope: Triple<String, Long, String>,
)

private fun commandCenterMediaScope(status: PolarisSessionStatus?): Triple<String, Long, String>? {
    if (status == null || !status.authorityContractValid || !status.streamingActive || status.shutdownRequested ||
        status.appSessionId.isBlank() || status.sessionGeneration <= 0 ||
        (status.liveTuningPresent && status.liveTuning == null && !status.liveTuningUnavailable)) return null
    val live = status.liveTuning
    if (live != null && (live.appSessionId != status.appSessionId || live.sessionGeneration != status.sessionGeneration)) return null
    return Triple(status.appSessionId, status.sessionGeneration, live?.hostInstance.orEmpty())
}

internal fun bindCommandCenterMedia(api: Any?, connection: Any?, status: PolarisSessionStatus?, sample: PerfOverlaySample): NovaCommandCenterMediaSample? {
    val scope = commandCenterMediaScope(status) ?: return null
    return if (api != null && connection != null) NovaCommandCenterMediaSample(sample, api, connection, scope) else null
}

internal fun currentCommandCenterMedia(bound: NovaCommandCenterMediaSample?, api: Any?, connection: Any?, status: PolarisSessionStatus?): PerfOverlaySample? =
    bound?.takeIf { it.api === api && it.connection === connection && it.scope == commandCenterMediaScope(status) }?.sample

/**
 * Owned by Game, fed by its existing status attachment. Only settled encoder observations count;
 * a request/POST acknowledgement is not an encoder application. Unknown actors remain Host.
 */
internal class NovaCommandCenterChanges {
    private var api: Any? = null
    private var connection: Any? = null
    private var identity: Triple<String, Long, String>? = null
    private var sequence = -1L
    private var baseline: Int? = null
    private var lastAt: Long? = null
    private val entries = ArrayDeque<NovaConfirmedStreamChange>()

    @Synchronized fun clear() {
        api = null; connection = null; identity = null; sequence = -1; baseline = null; lastAt = null
        entries.clear()
    }

    @Synchronized fun observe(observedApi: Any, observedConnection: Any, status: PolarisSessionStatus?, nowMs: Long) {
        if (api !== observedApi || connection !== observedConnection) {
            clear(); api = observedApi; connection = observedConnection
        }
        val live = status?.liveTuning
        val next = status?.takeIf { it.appSessionId.isNotBlank() && it.sessionGeneration > 0 }
            ?.let { Triple(it.appSessionId, it.sessionGeneration, live?.hostInstance.orEmpty()) }
        if (next != null && next != identity) {
            entries.clear(); baseline = null; sequence = -1; identity = next; lastAt = null
        }
        if (lastAt?.let { nowMs - it !in 0..5_000L } == true) baseline = null
        if (status == null || !status.streamingActive || live == null || !live.supported ||
            live.appSessionId != status.appSessionId || live.sessionGeneration != status.sessionGeneration) {
            baseline = null; return
        }
        if (live.sequence <= sequence) return
        sequence = live.sequence; lastAt = nowMs
        if (live.state !in setOf("stable", "adjusting", "off") || live.appliedBitrateKbps <= 0 ||
            (live.requestedBitrateKbps != live.appliedBitrateKbps &&
                !(live.state == "off" && live.requestedBitrateKbps == 0))) return
        baseline?.takeIf { it != live.appliedBitrateKbps }?.let {
            entries.addFirst(NovaConfirmedStreamChange(it, live.appliedBitrateKbps))
            while (entries.size > 4) entries.removeLast()
        }
        baseline = live.appliedBitrateKbps
    }

    @Synchronized fun snapshot(currentApi: Any?, currentConnection: Any?): List<NovaConfirmedStreamChange> {
        if (api !== currentApi || connection !== currentConnection) { clear(); return emptyList() }
        return entries.toList()
    }
}

/** Known protocol causes only; an unknown identifier never becomes player-facing copy. */
internal fun liveTuningReason(context: Context, live: LiveTuningStatus): String? = when (live.reason) {
    "encoder_runtime_update_unsupported" -> context.getString(R.string.nova_cc_reason_encoder)
    "encoder_not_initialized" -> context.getString(R.string.nova_cc_reason_encoder_not_initialized)
    "encoder_session_init_failed" -> context.getString(R.string.nova_cc_reason_encoder_init_failed)
    "encoder_session_ended" -> context.getString(R.string.nova_cc_reason_encoder_ended)
    "capture_session_ended" -> context.getString(R.string.nova_cc_reason_capture_ended)
    "runtime_bitrate_update_failed" -> context.getString(R.string.nova_cc_reason_update_failed)
    "session_scope_unavailable" -> context.getString(R.string.nova_cc_reason_session_scope)
    "packet_loss" -> context.getString(R.string.nova_cc_reason_packet_loss)
    "standing_queue" -> context.getString(R.string.nova_cc_reason_standing_queue)
    "finding_the_link" -> context.getString(R.string.nova_cc_reason_finding_link)
    "holding_under_a_queue" -> context.getString(R.string.nova_cc_reason_holding_queue)
    "holding_under_the_link" -> context.getString(R.string.nova_cc_reason_holding_link)
    "waiting_to_climb" -> context.getString(R.string.nova_cc_reason_waiting_climb)
    "testing_a_step_up" -> context.getString(R.string.nova_cc_reason_testing_step)
    "climbing" -> context.getString(R.string.nova_cc_reason_climbing)
    "loss_independent_of_bitrate" -> context.getString(R.string.nova_cc_reason_loss_independent)
    "frame_pacing" -> context.getString(R.string.nova_cc_reason_frame_pacing)
    "encode_load" -> context.getString(R.string.nova_cc_reason_encode_load)
    "doctor_action" -> context.getString(R.string.nova_cc_reason_doctor_action)
    "paired_client_action" -> context.getString(R.string.nova_cc_reason_client_action)
    "doctor_rollback" -> context.getString(R.string.nova_cc_reason_doctor_rollback)
    "disabled" -> context.getString(R.string.nova_cc_reason_disabled)
    "no_stream" -> context.getString(R.string.nova_cc_live_tuning_waiting_stream)
    // Readiness and the controller's healthy marker add no cause beyond the state sentence.
    // Neither establishes stream quality or fresh media feedback.
    "healthy", "encoder_ready", "supported", "" -> null
    else -> context.getString(R.string.nova_cc_reason_unavailable)
}

internal fun commandCenterRate(kbps: Int): String =
    java.math.BigDecimal.valueOf(kbps.toLong(), 3).stripTrailingZeros().toPlainString() + " Mbps"

internal fun commandCenterStream(
    context: Context,
    status: PolarisSessionStatus?,
    picture: NovaLiveBitratePresentation,
    sample: PerfOverlaySample?,
    nowMs: Long,
    active: Boolean,
    launchRequestKbps: Int,
    changes: List<NovaConfirmedStreamChange>,
): NovaCommandCenterStream {
    val unavailable = context.getString(R.string.nova_cc_value_unavailable)
    fun rate(value: Int?) = value?.takeIf { it > 0 }?.let { commandCenterRate(it) } ?: unavailable
    val fresh = sample?.takeIf { active && it.monotonicTimestampMs > 0 &&
        nowMs - it.monotonicTimestampMs in 0..5_000L &&
        status != null && status.streamingActive && status.appSessionId.isNotBlank() &&
        status.sessionGeneration > 0 }
    val overview = if (fresh == null) context.getString(R.string.nova_cc_media_unavailable) else {
        val resolution = if (fresh.width > 0 && fresh.height > 0) "${fresh.width} × ${fresh.height}" else unavailable
        val fps = fresh.incomingFps.takeIf { it.isFinite() && it >= 0 }?.let {
            context.getString(R.string.nova_cc_received_fps, String.format(java.util.Locale.getDefault(), "%.0f", it))
        } ?: unavailable
        listOf(resolution, fps, fresh.codec.takeIf { it.isNotBlank() } ?: unavailable,
            when {
                status?.health?.hdrEffectiveMode.equals("hdr10", true) && status?.isHdrDowngraded == false -> "HDR10"
                status?.health?.hdrEffectiveMode?.lowercase() in setOf("sdr_8bit", "sdr_10bit") -> "SDR"
                else -> context.getString(R.string.nova_cc_hdr_unavailable)
            }).joinToString(" · ")
    }
    val live = status?.liveTuning?.takeIf { active && status.streamingActive &&
        it.appSessionId == status.appSessionId && it.sessionGeneration == status.sessionGeneration }
    val negotiated = picture.rate.negotiatedUnits.takeIf { status != null && active }
    val rates = buildList {
        add(context.getString(R.string.nova_cc_rate_original) to rate(negotiated?.requestedKbps?.takeIf { it > 0 } ?: launchRequestKbps))
        add(context.getString(R.string.nova_cc_rate_negotiated) to rate(negotiated?.splitKbps))
        add(context.getString(R.string.nova_cc_rate_selected) to
            if (picture.rate.units == NovaBitrateUnits.REQUEST && status != null && active) rate(picture.rate.requestedKbps) else unavailable)
        add(context.getString(R.string.nova_cc_rate_limit) to rate(live?.qualityLimitKbps))
        add(context.getString(R.string.nova_cc_rate_requested) to rate(live?.requestedBitrateKbps?.takeIf { it > 0 } ?: negotiated?.liveEncoderKbps))
        add(context.getString(R.string.nova_cc_rate_applied) to rate(live?.appliedBitrateKbps))
        // Independent receiver byte observation. Never substitute a requested/encoder target.
        val receivedPayload = fresh?.receivedVideoPayloadKbps?.takeIf { it.isFinite() && it >= 0.0 }
        val receivedText = receivedPayload?.let {
            val rounded = java.math.BigDecimal.valueOf(it).movePointLeft(3)
                .setScale(3, java.math.RoundingMode.HALF_UP)
            if (it > 0.0 && rounded.signum() == 0) context.getString(R.string.nova_cc_payload_rate_tiny)
            else rounded.stripTrailingZeros().toPlainString() + " Mbps"
        } ?: context.getString(R.string.nova_cc_receive_not_measured)
        add(context.getString(R.string.nova_cc_rate_received) to receivedText)
        negotiated?.takeIf { it.splitKbps != null }?.let {
            add(context.getString(R.string.nova_cc_rate_audio) to commandCenterRate(it.audioKbps))
            add(context.getString(R.string.nova_cc_rate_repair) to "${it.fecPercent}%")
        }
    }
    return NovaCommandCenterStream(overview, rates, changes, live != null && live.supported)
}
