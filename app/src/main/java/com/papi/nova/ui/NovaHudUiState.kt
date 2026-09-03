package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.binding.video.PerfOverlaySample
import kotlin.math.roundToInt

enum class NovaHudMode(val preferenceValue: String) {
    MINIMAL("minimal"),
    PERFORMANCE("performance"),
    DEBUG("debug");

    fun next(): NovaHudMode = when (this) {
        MINIMAL -> PERFORMANCE
        PERFORMANCE -> DEBUG
        DEBUG -> MINIMAL
    }

    companion object {
        fun fromPreference(value: String?): NovaHudMode {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return when (normalized) {
                "minimal", "fps_only", "nano", "compact" -> MINIMAL
                "performance", "banner", "strip" -> PERFORMANCE
                "debug", "full", "command" -> DEBUG
                else -> MINIMAL
            }
        }
    }
}

enum class NovaHudTone {
    STABLE,
    WARNING,
    DANGER,
    INFO,
    MUTED
}

data class NovaHudLayerHealth(
    val label: String,
    val tone: NovaHudTone
)

data class NovaHudPerfSample(
    val fps: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val latencyMs: Int? = null,
    val codec: String? = null,
    val packetLossPct: Double? = null
) {
    companion object {
        // Compiled once. These used to be built inside fromPerfText, which runs on the
        // main thread once per perf sample for as long as a game is streaming -- seven
        // Regex compilations per interval, for patterns that never change.
        private const val LOCALIZED_NUMBER = """\d+(?:[.,]\d+)?"""

        private val FPS_SUFFIXED = Regex(
            """(?<![\d.,])($LOCALIZED_NUMBER)\s*(?:fps|FPS)\b""", RegexOption.IGNORE_CASE
        )
        private val FPS_LABELLED = Regex(
            """FPS[:：\s]+($LOCALIZED_NUMBER)""", RegexOption.IGNORE_CASE
        )
        private val FPS_TRAILING_ON_FIRST_LINE = Regex(
            """($LOCALIZED_NUMBER)\s*$""", RegexOption.MULTILINE
        )
        private val RESOLUTION = Regex("""(\d{3,4})\s*[x×]\s*(\d{3,4})""")
        private val LATENCY = Regex(
            """(?:RTT|latency)[^0-9]*(\d+)\s*ms""", RegexOption.IGNORE_CASE
        )
        private val CODEC = Regex("""(?:decoder|codec)[:\s]+(\S+)""", RegexOption.IGNORE_CASE)
        private val PACKET_LOSS_LABELLED = Regex(
            """(?:packet loss|frames dropped by your network connection|netdrops)[^0-9]*($LOCALIZED_NUMBER)\s*%""",
            RegexOption.IGNORE_CASE
        )
        private val PACKET_LOSS_SUFFIXED = Regex(
            """($LOCALIZED_NUMBER)\s*%\s*(?:packet loss|netdrops)""", RegexOption.IGNORE_CASE
        )

        fun fromPerfText(text: String): NovaHudPerfSample {
            // Fallback order is behaviour, not formatting: a bare "N fps" wins over a
            // "FPS: N" label, which wins over a trailing number on the first line.
            val fpsMatch = FPS_SUFFIXED.find(text)
                ?: FPS_LABELLED.find(text)
                ?: FPS_TRAILING_ON_FIRST_LINE.find(text.lines().firstOrNull() ?: "")
            val resolutionMatch = RESOLUTION.find(text)
            val latencyMatch = LATENCY.find(text)
            val codecMatch = CODEC.find(text)
            val packetLossMatch = PACKET_LOSS_LABELLED.find(text)
                ?: PACKET_LOSS_SUFFIXED.find(text)

            return NovaHudPerfSample(
                fps = fpsMatch?.localizedDouble(),
                width = resolutionMatch?.groupValues?.getOrNull(1)?.toIntOrNull(),
                height = resolutionMatch?.groupValues?.getOrNull(2)?.toIntOrNull(),
                latencyMs = latencyMatch?.groupValues?.getOrNull(1)?.toIntOrNull(),
                codec = codecMatch?.groupValues?.getOrNull(1)?.let(NovaHudUiState::normalizeCodecLabel),
                packetLossPct = packetLossMatch?.localizedDouble()
            )
        }

        private fun MatchResult.localizedDouble(): Double? =
            groupValues.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
    }
}

data class NovaHudUiState(
    val mode: NovaHudMode,
    val fpsLabel: String,
    val targetFpsLabel: String,
    val latencyLabel: String,
    val bitrateLabel: String,
    val resolutionLabel: String,
    val codecLabel: String,
    val lowOnePercentLabel: String,
    val streamModeLabel: String,
    val autopilotLabel: String,
    val autopilotHudLabel: String,
    val autopilotCompactLabel: String,
    val fpsTone: NovaHudTone,
    val latencyTone: NovaHudTone,
    val statusTone: NovaHudTone,
    val healthReasonLabel: String,
    val healthReasonTone: NovaHudTone,
    val streamTruthLabel: String,
    val layerHealth: List<NovaHudLayerHealth>,
    val eventBreadcrumbLabel: String,
    val sparklineSamples: List<Float>
) {
    companion object {
        fun empty(mode: NovaHudMode = NovaHudMode.MINIMAL): NovaHudUiState = from(
            mode = mode,
            fps = 0.0,
            targetFps = 0.0,
            latencyMs = 0,
            codec = "",
            bitrateKbps = 0,
            width = 0,
            height = 0,
            status = null,
            sparklineSamples = emptyList()
        )

        fun preview(mode: NovaHudMode): NovaHudUiState = from(
            mode = mode,
            fps = 60.0,
            targetFps = 120.0,
            latencyMs = 18,
            codec = "hevc_nvenc",
            bitrateKbps = 30000,
            width = 1920,
            height = 1080,
            status = PolarisSessionStatus(
                state = "streaming",
                streamingActive = true,
                adaptiveBitrateEnabled = true,
                aiOptimizerEnabled = true,
                displayMode = PolarisSessionStatus.DisplayModeStatus(
                    requested = "headless",
                    effectiveHeadless = true
                ),
                tuning = PolarisSessionStatus.TuningStatus(
                    adaptiveBitrateEnabled = true,
                    adaptiveTargetBitrateKbps = 30000,
                    adaptiveBaseBitrateKbps = 30000,
                    aiOptimizerEnabled = true
                ),
                encoder = PolarisSessionStatus.EncoderStatus(
                    codec = "hevc_nvenc",
                    bitrateKbps = 30000,
                    fps = 120.0,
                    requestedClientFps = 120.0,
                    sessionTargetFps = 120.0,
                    encodeTargetFps = 120.0,
                    optimizationSource = "ai_cached",
                    targetResidency = "gpu"
                ),
                health = PolarisSessionStatus.HealthStatus(grade = "good"),
                syncStatus = PolarisSessionStatus.SyncStatus(available = true, state = "synced")
            ),
            sparklineSamples = listOf(55f, 58f, 61f, 57f, 60f, 59f)
        )

        fun from(
            mode: NovaHudMode,
            fps: Double,
            targetFps: Double,
            latencyMs: Int,
            codec: String,
            bitrateKbps: Int,
            width: Int,
            height: Int,
            status: PolarisSessionStatus?,
            sparklineSamples: List<Float>,
            eventBreadcrumbLabel: String = "",
            lowOnePercentFps: Double = calculateLowOnePercent(sparklineSamples)
        ): NovaHudUiState {
            val autoQuality = AutoQualityUiState.from(status, targetFps)
            val healthReason = buildHealthReason(status, latencyMs)
            return NovaHudUiState(
                mode = mode,
                fpsLabel = fps.takeIf { it > 0.0 }?.roundToInt()?.toString() ?: "--",
                targetFpsLabel = formatTargetFps(mode, targetFps),
                latencyLabel = latencyMs.takeIf { it > 0 }?.let { "${it}ms" } ?: "--ms",
                bitrateLabel = formatBitrate(mode, bitrateKbps),
                resolutionLabel = formatResolution(mode, width, height),
                codecLabel = normalizeCodecLabel(codec),
                lowOnePercentLabel = lowOnePercentFps.takeIf { it > 0.0 }?.roundToInt()?.let { "1%: $it" } ?: "--",
                streamModeLabel = status?.let(::buildSessionModeLabel).orEmpty(),
                autopilotLabel = autoQuality.label,
                autopilotHudLabel = autoQuality.hudLabel(),
                autopilotCompactLabel = autoQuality.compactLabel,
                fpsTone = toneForFps(fps, status),
                latencyTone = toneForLatency(latencyMs),
                statusTone = if (healthReason.second == NovaHudTone.WARNING || healthReason.second == NovaHudTone.DANGER) healthReason.second else autoQuality.tone.toHudTone(),
                healthReasonLabel = healthReason.first,
                healthReasonTone = healthReason.second,
                streamTruthLabel = buildStreamTruth(status, targetFps, codec, height),
                layerHealth = buildLayerHealth(status, latencyMs),
                eventBreadcrumbLabel = eventBreadcrumbLabel,
                sparklineSamples = sparklineSamples.takeLast(60)
            )
        }

        fun normalizeCodecLabel(codec: String): String {
            val value = codec.trim()
            if (value.isBlank()) {
                return ""
            }
            val lower = value.lowercase()
            return when {
                lower.contains("av1") -> "AV1"
                lower.contains("hevc") || lower.contains("h265") -> "HEVC"
                lower.contains("avc") || lower.contains("h264") -> "H264"
                lower.contains("vp9") -> "VP9"
                else -> value.uppercase()
            }
        }

        fun calculateLowOnePercent(samples: List<Float>): Double {
            if (samples.size < 3) {
                return 0.0
            }
            val sorted = samples.sorted()
            val index = (samples.size * 0.01f).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[index].toDouble()
        }

        private fun formatTargetFps(mode: NovaHudMode, targetFps: Double): String {
            if (targetFps <= 0.0) {
                return ""
            }
            val rounded = targetFps.roundToInt()
            return when (mode) {
                NovaHudMode.DEBUG -> "TGT $rounded"
                NovaHudMode.PERFORMANCE -> "/$rounded"
                NovaHudMode.MINIMAL -> ""
            }
        }

        private fun formatBitrate(mode: NovaHudMode, bitrateKbps: Int): String {
            if (bitrateKbps <= 0) {
                return "--"
            }
            val full = StreamPolicyUiState.formatMbps(bitrateKbps)
            return when (mode) {
                NovaHudMode.DEBUG,
                NovaHudMode.MINIMAL -> full
                NovaHudMode.PERFORMANCE -> full.replace(" Mbps", "M").replace(" ", "")
            }
        }

        private fun formatResolution(mode: NovaHudMode, width: Int, height: Int): String {
            if (width <= 0 || height <= 0) {
                return "--"
            }
            return when (mode) {
                NovaHudMode.DEBUG -> "$width×$height"
                NovaHudMode.PERFORMANCE,
                NovaHudMode.MINIMAL -> "${height}p"
            }
        }

        private fun toneForFps(fps: Double, status: PolarisSessionStatus?): NovaHudTone {
            if (fps <= 0.0) {
                return NovaHudTone.MUTED
            }
            val primaryIssue = status?.effectivePrimaryIssue.orEmpty()
            val legacyIssues = if (status?.hasAuthoritativeDoctorResult == true) {
                emptyList()
            } else {
                status?.health?.issues.orEmpty()
            }
            val pacingEvidence = status?.doctor?.evidenceItems.orEmpty().any { item ->
                item.id.lowercase() in setOf("frame_pacing", "target_fps_gap", "encode_cadence") &&
                    item.status.lowercase() in setOf("watch", "warning", "fail", "degraded", "needs_action")
            }
            return if (
                primaryIssue.equals("frame_pacing", ignoreCase = true) ||
                primaryIssue.equals("host_render_limited", ignoreCase = true) ||
                primaryIssue.equals("encoder_load", ignoreCase = true) ||
                legacyIssues.any {
                    it.equals("frame_pacing", ignoreCase = true) ||
                        it.equals("host_render_limited", ignoreCase = true)
                } ||
                pacingEvidence
            ) {
                NovaHudTone.WARNING
            } else {
                // Rendered FPS is a raw observation. Without source/capture
                // cadence, low values may represent a static or duplicate-only
                // scene and are not independently graded as a pacing fault.
                NovaHudTone.STABLE
            }
        }

        private fun toneForLatency(ms: Int): NovaHudTone = when {
            ms <= 0 -> NovaHudTone.MUTED
            ms < 45 -> NovaHudTone.STABLE
            ms <= 50 -> NovaHudTone.WARNING
            else -> NovaHudTone.DANGER
        }

        // Polaris serves risk fields unconditionally as "normal" | "elevated", so presence
        // alone means nothing — only the elevated value is a warning.
        private fun riskElevated(risk: String?): Boolean = risk.equals("elevated", ignoreCase = true)

        private fun doctorEvidenceWarns(status: PolarisSessionStatus?, ids: Set<String>? = null): Boolean =
            status?.doctor?.evidenceItems.orEmpty().any { item ->
                val id = item.id.lowercase()
                (ids == null || id in ids) &&
                    status?.doctorEvidenceIsActionable(item) == true
            }

        private fun buildHealthReason(
            status: PolarisSessionStatus?,
            latencyMs: Int
        ): Pair<String, NovaHudTone> {
            val primaryIssue = status?.effectivePrimaryIssue.orEmpty()
            val normalizedPrimaryIssue = primaryIssue.lowercase()
            val issues = if (status?.hasAuthoritativeDoctorResult == true) {
                emptyList()
            } else {
                status?.health?.issues.orEmpty().map { it.lowercase() }
            }
            val doctorWarning = doctorEvidenceWarns(status)
            return when {
                status?.isHdrDowngraded == true -> "HDR downgraded" to NovaHudTone.WARNING
                status?.isHostRenderLimited == true || normalizedPrimaryIssue == "host_render_limited" || issues.contains("host_render_limited") ->
                    "Host capped" to NovaHudTone.WARNING
                normalizedPrimaryIssue == "frame_pacing" || issues.contains("frame_pacing") ->
                    "Frame pacing" to NovaHudTone.WARNING
                status?.hasAuthoritativeDoctorResult != true &&
                    status?.health?.grade.equals("degraded", ignoreCase = true) ->
                    "Stream degraded" to NovaHudTone.WARNING
                doctorWarning -> "Needs attention" to NovaHudTone.WARNING
                status?.authoritativeDoctorVerdictNeedsAttention == true ->
                    "Needs attention" to NovaHudTone.WARNING
                normalizedPrimaryIssue == "network_observation" ->
                    "Network recheck" to NovaHudTone.MUTED
                normalizedPrimaryIssue == "control_channel_observation" ->
                    "Control retries observed" to NovaHudTone.MUTED
                normalizedPrimaryIssue == "network_jitter" ||
                    (status?.hasAuthoritativeDoctorResult != true && riskElevated(status?.health?.networkRisk)) ->
                    "Network jitter" to NovaHudTone.WARNING
                normalizedPrimaryIssue.contains("decoder") ||
                    (status?.hasAuthoritativeDoctorResult != true && riskElevated(status?.health?.decoderRisk)) ->
                    "Decoder late" to NovaHudTone.WARNING
                latencyMs >= 45 -> "High latency" to toneForLatency(latencyMs)
                status?.hasAuthoritativeDoctorResult != true &&
                    status?.health?.grade.equals("watch", ignoreCase = true) ->
                    "Needs attention" to NovaHudTone.WARNING
                normalizedPrimaryIssue.isNotBlank() && normalizedPrimaryIssue != "none" ->
                    "Needs attention" to NovaHudTone.WARNING
                status == null -> "Waiting" to NovaHudTone.MUTED
                else -> "Stable" to NovaHudTone.STABLE
            }
        }

        private fun buildStreamTruth(
            status: PolarisSessionStatus?,
            targetFps: Double,
            codec: String,
            height: Int
        ): String {
            val target = targetFps.takeIf { it > 0.0 }?.roundToInt()
            val streamLabel = when {
                target != null -> "Stream $target"
                height > 0 -> "Stream ${height}p"
                else -> "Stream"
            }
            val safeTarget = status?.health?.safeTargetFps?.takeIf { it > 0.0 }?.roundToInt()
            return when {
                status?.isHdrDowngraded == true -> streamLabel + " • 10-bit SDR"
                status?.isHostRenderLimited == true && safeTarget != null -> "$streamLabel • Game capped $safeTarget"
                status?.isHostRenderLimited == true -> "$streamLabel • Host capped"
                status?.hostCaptureTruthLabel?.isNotBlank() == true ->
                    "$streamLabel • ${status.hostCaptureTruthLabel}"
                status?.profileState?.preferenceLabel?.isNotBlank() == true ->
                    "$streamLabel • ${status.profileState.preferenceLabel} profile"
                codec.isNotBlank() -> "$streamLabel • ${normalizeCodecLabel(codec)}"
                status != null -> "$streamLabel • ${status.sessionModeLabel}"
                else -> streamLabel
            }
        }

        private fun buildLayerHealth(status: PolarisSessionStatus?, latencyMs: Int): List<NovaHudLayerHealth> {
            val primaryIssue = status?.effectivePrimaryIssue.orEmpty()
            val normalizedPrimaryIssue = primaryIssue.lowercase()
            val networkObservation = normalizedPrimaryIssue in
                setOf("network_observation", "control_channel_observation")
            val issues = if (status?.hasAuthoritativeDoctorResult == true) {
                emptyList()
            } else {
                status?.health?.issues.orEmpty().map { it.lowercase() }
            }
            val hostDoctorWarning = doctorEvidenceWarns(
                status,
                setOf("capture_path", "encoder", "encoder_selection", "frame_pacing", "target_fps_gap", "source_capture", "encode_cadence", "effective_quality_ceiling")
            )
            val networkDoctorWarning = doctorEvidenceWarns(
                status,
                setOf("packet_loss", "latency", "transport")
            )
            val clientDoctorWarning = doctorEvidenceWarns(
                status,
                setOf("decoder", "delivery_cadence", "receive_decode_render", "presentation")
            )
            val hostTone = when {
                status?.isHostRenderLimited == true || normalizedPrimaryIssue.contains("host") || issues.any { it.contains("host") } ->
                    NovaHudTone.WARNING
                (status?.hasAuthoritativeDoctorResult != true &&
                    status?.health?.grade.equals("degraded", ignoreCase = true)) ||
                    (status?.hasAuthoritativeDoctorResult != true &&
                        status?.health?.grade.equals("watch", ignoreCase = true) && !networkObservation) ||
                    normalizedPrimaryIssue == "frame_pacing" || issues.contains("frame_pacing") || hostDoctorWarning -> NovaHudTone.WARNING
                else -> NovaHudTone.STABLE
            }
            val networkTone = when {
                networkDoctorWarning -> NovaHudTone.WARNING
                !networkObservation &&
                    (normalizedPrimaryIssue == "network_jitter" || issues.any { it.contains("network") } ||
                        (status?.hasAuthoritativeDoctorResult != true && riskElevated(status?.health?.networkRisk))) -> NovaHudTone.WARNING
                latencyMs > 50 -> NovaHudTone.DANGER
                latencyMs >= 45 -> NovaHudTone.WARNING
                else -> NovaHudTone.STABLE
            }
            val clientTone = when {
                normalizedPrimaryIssue.contains("decoder") || issues.any { it.contains("decoder") } ||
                    (status?.hasAuthoritativeDoctorResult != true && riskElevated(status?.health?.decoderRisk)) ||
                    clientDoctorWarning -> NovaHudTone.WARNING
                status?.encoder?.targetResidency.equals("cpu", ignoreCase = true) -> NovaHudTone.WARNING
                else -> NovaHudTone.STABLE
            }
            val hostCaptureLabel = status?.hostCaptureTruthLabel.orEmpty()
            val hostLabel = hostCaptureLabel.ifBlank { "HOST" }
            val resolvedHostTone = when {
                hostCaptureLabel.contains("SHM", ignoreCase = true) ||
                    hostCaptureLabel.contains("mismatch", ignoreCase = true) -> NovaHudTone.WARNING
                hostCaptureLabel.contains("GPU-native", ignoreCase = true) -> NovaHudTone.STABLE
                else -> hostTone
            }
            return listOf(
                NovaHudLayerHealth(hostLabel, resolvedHostTone),
                NovaHudLayerHealth("NET", networkTone),
                NovaHudLayerHealth("CLIENT", clientTone)
            )
        }

        private fun buildSessionModeLabel(status: PolarisSessionStatus): String {
            val mode = status.sessionModeWithCaptureLabel.ifBlank { status.sessionModeLabel }
            val bitDepth = if (status.isTenBitActive) "10b" else "8b"
            val path = when {
                status.capturePathLabel.isNotBlank() -> ""
                status.isGpuPath -> "GPU"
                status.encoder.targetResidency.equals("cpu", ignoreCase = true) -> "CPU"
                else -> ""
            }
            val modeSource = when (status.displayMode.requested) {
                "auto" -> "AUTO"
                "headless", "headless_stream", "virtual_display", "host_virtual_display", "windowed_stream", "desktop_display" -> "EXP"
                else -> ""
            }
            val lifecycle = when {
                status.isViewer -> "WATCH"
                status.isShuttingDown -> "ENDING"
                else -> ""
            }
            val optimization = when (status.encoder.optimizationSource.lowercase()) {
                "ai_live" -> "AI"
                "ai_cached" -> "AI-C"
                "device_db" -> "BASE"
                else -> ""
            }
            val normalized = if (status.hasOptimizationNormalization) "ADJ" else ""

            return listOf(mode, status.encoderSelectionLabel, bitDepth, path, modeSource, lifecycle, optimization, normalized)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }

        private fun AutoQualityUiState.hudLabel(): String = when (state) {
            AutoQualityUiState.State.OFF -> "Live Tune Off"
            AutoQualityUiState.State.WATCHING -> "Doctor Check"
            AutoQualityUiState.State.OPTIMIZING -> "Launch Setup"
            AutoQualityUiState.State.STABLE -> when {
                manualOverride -> "Quality Preset"
                label.contains("cap", ignoreCase = true) -> "At Quality Cap"
                else -> "Stream Ready"
            }
            AutoQualityUiState.State.RECOVERING -> when {
                compactLabel == "HOST" -> "Host Recovery"
                label.contains("safe", ignoreCase = true) -> "Auto Safe"
                label.contains("cap", ignoreCase = true) -> "Auto Safe"
                label.contains("bitrate", ignoreCase = true) -> "Bitrate Recovery"
                label.contains("FPS", ignoreCase = true) -> "FPS Recovery"
                else -> "Recovering"
            }
            AutoQualityUiState.State.BLOCKED -> when {
                label.contains("host", ignoreCase = true) -> "Host Limited"
                label.contains("network", ignoreCase = true) -> "Network Limited"
                label.contains("encoder", ignoreCase = true) -> "Encoder Limited"
                label.contains("decoder", ignoreCase = true) -> "Decoder Limited"
                else -> "Holding"
            }
            AutoQualityUiState.State.UPGRADE_AVAILABLE -> "Quality Ready"
            AutoQualityUiState.State.MANUAL_OVERRIDE -> "Manual"
            AutoQualityUiState.State.NEEDS_ATTENTION -> when {
                label.contains("sync", ignoreCase = true) -> "Sync Attention"
                label.contains("decoder", ignoreCase = true) -> "Decoder Pressure"
                label.contains("pacing", ignoreCase = true) -> "Pacing Watch"
                label.contains("bitrate", ignoreCase = true) -> "Bitrate Adjusted"
                else -> "Attention"
            }
        }

        private fun AutoQualityUiState.Tone.toHudTone(): NovaHudTone = when (this) {
            AutoQualityUiState.Tone.MUTED -> NovaHudTone.MUTED
            AutoQualityUiState.Tone.INFO -> NovaHudTone.INFO
            AutoQualityUiState.Tone.STABLE -> NovaHudTone.STABLE
            AutoQualityUiState.Tone.WARNING -> NovaHudTone.WARNING
            AutoQualityUiState.Tone.DANGER -> NovaHudTone.DANGER
        }
    }
}

class NovaHudEventTrail(private val capacity: Int = 4) {
    private val labels = ArrayDeque<String>()

    val latestLabel: String
        get() = labels.lastOrNull().orEmpty()

    fun clear() {
        labels.clear()
    }

    fun record(label: String) {
        val clean = label.trim()
        if (clean.isBlank() || clean == latestLabel) {
            return
        }
        labels.addLast(clean)
        while (labels.size > capacity.coerceAtLeast(1)) {
            labels.removeFirst()
        }
    }

    fun recordBitrateChange(fromKbps: Int, toKbps: Int) {
        if (fromKbps <= 0 || toKbps <= 0 || fromKbps == toKbps) {
            return
        }
        val direction = if (toKbps < fromKbps) "lowered" else "recovered"
        record("Bitrate $direction: ${formatHudMbps(fromKbps)} → ${formatHudMbps(toKbps)}")
    }

    fun retireRecoveryProfile() {
        labels.removeAll { label ->
            label.startsWith("Next launch recovery:") || label.startsWith("Fallback ready:")
        }
    }

}

private fun formatHudMbps(kbps: Int): String {
    if (kbps <= 0) return "--"
    val mbps = kbps / 1000.0
    val rounded = mbps.roundToInt()
    return if (kotlin.math.abs(mbps - rounded) < 0.05) {
        "${rounded}M"
    } else {
        "${String.format(java.util.Locale.US, "%.1f", mbps)}M"
    }
}

class NovaHudSessionStats {
    private var sessionFpsSum = 0.0
    private var sessionLatencySum = 0.0
    private var sessionLatencySamples = 0
    private var sessionPacketLossSum = 0.0
    private var sessionPacketLossSamples = 0
    private var sessionSamples = 0
    private var sessionStartTime = 0L
    private var sessionMinFps = 0.0
    private var sessionLowOnePercentFps = 0.0
    private var targetFps = 0.0
    private var lastCodec = ""
    private var lastBitrateKbps = 0
    private var sessionBitrateSum = 0L
    private var sessionBitrateSamples = 0
    private var lastMonotonicTimestampMs = 0L
    private var framesExpected = 0L
    private var framesReceived = 0L
    private var framesRendered = 0L
    private var framesLost = 0L
    private var incomingFps = 0.0
    private var renderedFps = 0.0
    private var decodeTimeMs = 0.0
    private var hostProcessingLatencyMs: Double? = null
    private var sessionGeneration = 0L

    fun reset() {
        sessionFpsSum = 0.0
        sessionLatencySum = 0.0
        sessionLatencySamples = 0
        sessionPacketLossSum = 0.0
        sessionPacketLossSamples = 0
        sessionSamples = 0
        sessionStartTime = 0L
        sessionMinFps = 0.0
        sessionLowOnePercentFps = 0.0
        sessionBitrateSum = 0L
        sessionBitrateSamples = 0
        lastMonotonicTimestampMs = 0L
        framesExpected = 0L
        framesReceived = 0L
        framesRendered = 0L
        framesLost = 0L
        incomingFps = 0.0
        renderedFps = 0.0
        decodeTimeMs = 0.0
        hostProcessingLatencyMs = null
        sessionGeneration = 0L
    }

    fun setTargetFps(fps: Double) {
        if (fps > 0.0) {
            targetFps = fps
        }
    }

    fun setLastCodec(codec: String) {
        lastCodec = codec
    }

    fun setLastBitrateKbps(bitrateKbps: Int) {
        if (bitrateKbps > 0) {
            lastBitrateKbps = bitrateKbps
        }
    }

    fun recordFps(fps: Double, nowMs: Long = System.currentTimeMillis(), lowOnePercentFps: Double = 0.0) {
        if (fps <= 0.0) {
            return
        }
        sessionFpsSum += fps
        sessionSamples++
        if (sessionMinFps <= 0.0 || fps < sessionMinFps) {
            sessionMinFps = fps
        }
        if (lowOnePercentFps > 0.0) {
            sessionLowOnePercentFps = lowOnePercentFps
        }
        if (sessionStartTime == 0L) {
            sessionStartTime = nowMs
        }
    }

    fun recordLatency(ms: Int) {
        if (ms > 0) {
            sessionLatencySum += ms.toDouble()
            sessionLatencySamples++
        }
    }

    fun recordBitrate(bitrateKbps: Int) {
        if (bitrateKbps > 0) {
            sessionBitrateSum += bitrateKbps.toLong()
            sessionBitrateSamples++
            lastBitrateKbps = bitrateKbps
        }
    }

    fun recordPacketLoss(packetLossPct: Double) {
        if (packetLossPct >= 0.0) {
            sessionPacketLossSum += packetLossPct
            sessionPacketLossSamples++
        }
    }

    fun recordPerfSample(sample: PerfOverlaySample) {
        recordFps(if (sample.renderedFps > 0.0) sample.renderedFps else sample.fps)
        recordLatency(sample.rttMs)
        recordPacketLoss(sample.packetLossPct)
        setLastCodec(sample.codec)
        recordRawMediaEvidence(sample)
    }

    fun recordRawMediaEvidence(sample: PerfOverlaySample) {
        lastMonotonicTimestampMs = sample.monotonicTimestampMs
        framesExpected = sample.framesExpected
        framesReceived = sample.framesReceived
        framesRendered = sample.framesRendered
        framesLost = sample.framesLost
        incomingFps = sample.incomingFps
        renderedFps = sample.renderedFps
        decodeTimeMs = sample.decodeTimeMs
        hostProcessingLatencyMs = sample.hostProcessingLatencyMs
        sessionGeneration = sample.sessionGeneration
    }

    fun applySessionStatus(status: PolarisSessionStatus?) {
        if (status == null) {
            return
        }
        val resolvedTargetFps = listOf(
            status.encoder.sessionTargetFps,
            status.encoder.encodeTargetFps,
            status.encoder.requestedClientFps
        ).firstOrNull { it > 0.0 } ?: 0.0
        setTargetFps(resolvedTargetFps)
    }

    fun summary(nowMs: Long = System.currentTimeMillis()): Map<String, Any> {
        val durationS = if (sessionStartTime > 0) ((nowMs - sessionStartTime) / 1000).toInt() else 0
        val avgFps = if (sessionSamples > 0) sessionFpsSum / sessionSamples else 0.0
        val avgLatency = if (sessionLatencySamples > 0) sessionLatencySum / sessionLatencySamples else 0.0
        val summary = mutableMapOf<String, Any>(
            "contract" to "doctor_v2_raw",
            "observational" to true,
            "avg_fps" to avgFps,
            "target_fps" to targetFps,
            "avg_latency_ms" to avgLatency,
            "packet_loss_pct" to if (sessionPacketLossSamples > 0) {
                sessionPacketLossSum / sessionPacketLossSamples
            } else {
                0.0
            },
            "avg_bitrate_kbps" to if (sessionBitrateSamples > 0) {
                (sessionBitrateSum / sessionBitrateSamples).toInt()
            } else {
                lastBitrateKbps
            },
            "codec" to lastCodec,
            "duration_s" to durationS,
            "samples" to sessionSamples
        )
        summary["monotonic_timestamp_ms"] = lastMonotonicTimestampMs
        summary["frames_expected"] = framesExpected
        summary["frames_received"] = framesReceived
        summary["frames_rendered"] = framesRendered
        summary["frames_lost"] = framesLost
        summary["received_fps"] = incomingFps
        summary["rendered_fps"] = renderedFps
        summary["decode_latency_ms"] = decodeTimeMs
        summary["decoded_frames_available"] = false
        summary["duplicate_frames_available"] = false
        summary["transport_bytes_available"] = false
        summary["retransmissions_available"] = false
        summary["session_generation"] = sessionGeneration
        hostProcessingLatencyMs?.let { summary["host_processing_latency_ms"] = it }
        if (sessionLowOnePercentFps > 0.0) summary["low_1_percent_fps"] = sessionLowOnePercentFps
        if (sessionMinFps > 0.0) summary["min_fps"] = sessionMinFps
        // Rendered FPS alone cannot distinguish moving content from a static
        // or duplicate-only source. Polaris owns pacing classification once
        // source/capture cadence is available; Nova reports only raw stages.
        if (sessionPacketLossSamples > 0) {
            summary["packet_loss_source"] = "nova_media_path"
        }
        return summary
    }

}
