package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
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
            val autoQuality = AutoQualityUiState.from(status, targetFps, fps)
            val healthReason = buildHealthReason(status, fps, targetFps, latencyMs)
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
                fpsTone = toneForFps(fps, targetFps),
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

        private fun toneForFps(fps: Double, targetFps: Double): NovaHudTone {
            if (fps <= 0.0) {
                return NovaHudTone.MUTED
            }
            return when {
                targetFps > 0.0 && fps < targetFps * 0.75 -> NovaHudTone.WARNING
                fps >= 55.0 -> NovaHudTone.STABLE
                fps >= 30.0 -> NovaHudTone.WARNING
                else -> NovaHudTone.DANGER
            }
        }

        private fun toneForLatency(ms: Int): NovaHudTone = when {
            ms <= 0 -> NovaHudTone.MUTED
            ms <= 20 -> NovaHudTone.STABLE
            ms <= 50 -> NovaHudTone.WARNING
            else -> NovaHudTone.DANGER
        }

        // Polaris serves risk fields unconditionally as "normal" | "elevated", so presence
        // alone means nothing — only the elevated value is a warning.
        private fun riskElevated(risk: String?): Boolean = risk.equals("elevated", ignoreCase = true)

        private fun buildHealthReason(
            status: PolarisSessionStatus?,
            fps: Double,
            targetFps: Double,
            latencyMs: Int
        ): Pair<String, NovaHudTone> {
            val primaryIssue = status?.health?.primaryIssue.orEmpty().lowercase()
            val issues = status?.health?.issues.orEmpty().map { it.lowercase() }
            return when {
                status?.isHdrDowngraded == true -> "HDR downgraded" to NovaHudTone.WARNING
                status?.isHostRenderLimited == true || primaryIssue == "host_render_limited" || issues.contains("host_render_limited") ->
                    "Host capped" to NovaHudTone.WARNING
                primaryIssue.contains("network") || riskElevated(status?.health?.networkRisk) ->
                    "Network jitter" to NovaHudTone.WARNING
                primaryIssue.contains("decoder") || riskElevated(status?.health?.decoderRisk) ->
                    "Decoder late" to NovaHudTone.WARNING
                latencyMs > 50 -> "High latency" to NovaHudTone.DANGER
                targetFps > 0.0 && fps > 0.0 && fps < targetFps * 0.75 ->
                    "FPS below target" to NovaHudTone.WARNING
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
            val primaryIssue = status?.health?.primaryIssue.orEmpty().lowercase()
            val issues = status?.health?.issues.orEmpty().map { it.lowercase() }
            val hostTone = when {
                status?.isHostRenderLimited == true || primaryIssue.contains("host") || issues.any { it.contains("host") } ->
                    NovaHudTone.WARNING
                status?.health?.grade.equals("degraded", ignoreCase = true) -> NovaHudTone.WARNING
                else -> NovaHudTone.STABLE
            }
            val networkTone = when {
                primaryIssue.contains("network") || issues.any { it.contains("network") } ||
                    riskElevated(status?.health?.networkRisk) -> NovaHudTone.WARNING
                latencyMs > 50 -> NovaHudTone.DANGER
                latencyMs > 20 -> NovaHudTone.WARNING
                else -> NovaHudTone.STABLE
            }
            val clientTone = when {
                primaryIssue.contains("decoder") || issues.any { it.contains("decoder") } ||
                    riskElevated(status?.health?.decoderRisk) -> NovaHudTone.WARNING
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

            return listOf(mode, bitDepth, path, modeSource, lifecycle, optimization, normalized)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }

        private fun AutoQualityUiState.hudLabel(): String = when (state) {
            AutoQualityUiState.State.OFF -> "Auto Off"
            AutoQualityUiState.State.WATCHING -> "Auto Check"
            AutoQualityUiState.State.OPTIMIZING -> "Optimizing"
            AutoQualityUiState.State.STABLE -> when {
                manualOverride -> "Quality Preset"
                label.contains("cap", ignoreCase = true) -> "Auto Cap"
                else -> "Auto Stable"
            }
            AutoQualityUiState.State.RECOVERING -> when {
                compactLabel == "HOST" -> "AI Recovery"
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

    fun recordRecoveryProfile(targetFps: Double, recoveryQueued: Boolean) {
        if (targetFps <= 0.0) {
            return
        }
        val label = if (recoveryQueued) {
            "Next launch recovery: ${targetFps.roundToInt()} FPS"
        } else {
            "Fallback ready: ${targetFps.roundToInt()} FPS"
        }
        record(label)
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
    private var sessionBadPacingSamples = 0
    private var targetFps = 0.0
    private var lastCodec = ""
    private var lastBitrateKbps = 0
    private var sessionBitrateSum = 0L
    private var sessionBitrateSamples = 0
    private var optimizationSource = ""
    private var optimizationConfidence = ""
    private var recommendationVersion = 0
    private var healthGrade = ""
    private var healthPrimaryIssue = ""
    private var healthIssues: List<String> = emptyList()
    private var decoderRisk = ""
    private var hdrRisk = ""
    private var networkRisk = ""
    private var hostRenderLimited = false
    private var capturePath = ""
    private var safeBitrateKbps = 0
    private var safeCodec = ""
    private var safeDisplayMode = ""
    private var safeTargetFps = 0.0
    private var safeHdr: Boolean? = null
    private var relaunchRecommended = false
    private var diagnosisClassification = ""
    private var diagnosisLikelyCause = ""
    private var diagnosisTryFirst = ""
    private var diagnosisConfidence = ""

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
        sessionBadPacingSamples = 0
        sessionBitrateSum = 0L
        sessionBitrateSamples = 0
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
        if (targetFps > 0.0 && fps < targetFps * 0.85) {
            sessionBadPacingSamples++
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
        optimizationSource = status.encoder.optimizationSource
        optimizationConfidence = status.encoder.optimizationConfidence
        recommendationVersion = status.encoder.recommendationVersion
        healthGrade = status.health.grade
        healthPrimaryIssue = status.health.primaryIssue
        healthIssues = status.health.issues
        decoderRisk = status.health.decoderRisk
        hdrRisk = status.health.hdrRisk
        networkRisk = status.health.networkRisk
        hostRenderLimited = status.isHostRenderLimited
        capturePath = resolveCapturePath(status)
        safeBitrateKbps = status.health.safeBitrateKbps
        safeCodec = status.health.safeCodec
        safeDisplayMode = status.health.safeDisplayMode
        safeTargetFps = status.health.safeTargetFps
        safeHdr = status.health.safeHdr
        relaunchRecommended = status.health.relaunchRecommended
        diagnosisClassification = status.doctor.classification
        diagnosisLikelyCause = status.doctor.likelyCause
        diagnosisTryFirst = status.doctor.firstTry
        diagnosisConfidence = status.doctor.confidence
    }

    fun summary(nowMs: Long = System.currentTimeMillis()): Map<String, Any> {
        val durationS = if (sessionStartTime > 0) ((nowMs - sessionStartTime) / 1000).toInt() else 0
        val avgFps = if (sessionSamples > 0) sessionFpsSum / sessionSamples else 0.0
        val avgLatency = if (sessionLatencySamples > 0) sessionLatencySum / sessionLatencySamples else 0.0
        val badPacingPct = if (sessionSamples > 0) {
            (sessionBadPacingSamples.toDouble() / sessionSamples.toDouble()) * 100.0
        } else {
            0.0
        }
        val lowSignalFps = when {
            sessionLowOnePercentFps > 0.0 -> sessionLowOnePercentFps
            sessionMinFps > 0.0 -> sessionMinFps
            else -> avgFps
        }
        val severePacing = targetFps >= 55.0 && sessionSamples >= 10 &&
            (
                badPacingPct >= 18.0 ||
                    (lowSignalFps > 0.0 && lowSignalFps < targetFps * 0.72) ||
                    (avgFps > 0.0 && avgFps < targetFps * 0.82)
                )
        val moderatePacing = targetFps >= 55.0 && sessionSamples >= 10 &&
            (
                badPacingPct >= 8.0 ||
                    (lowSignalFps > 0.0 && lowSignalFps < targetFps * 0.85) ||
                    (avgFps > 0.0 && avgFps < targetFps * 0.90)
                )
        val canHoldStable40 = targetFps in 55.0..75.0 &&
            avgFps > 0.0 &&
            avgFps >= targetFps * 0.82 &&
            lowSignalFps > 0.0 &&
            lowSignalFps >= targetFps * 0.70
        val highRefreshPacing = targetFps >= 90.0 && (severePacing || moderatePacing)
        val canHoldStable60 = highRefreshPacing &&
            avgFps >= 58.0 &&
            lowSignalFps >= 45.0 &&
            badPacingPct < 18.0
        val derivedSafeTargetFps = when {
            safeTargetFps > 0.0 -> safeTargetFps
            highRefreshPacing && canHoldStable60 -> 60.0
            highRefreshPacing -> 30.0
            severePacing -> 30.0
            moderatePacing && canHoldStable40 -> 40.0
            moderatePacing -> 30.0
            else -> 0.0
        }
        val derivedRelaunchRecommended = relaunchRecommended ||
            (
                derivedSafeTargetFps > 0.0 &&
                    targetFps > 0.0 &&
                    derivedSafeTargetFps < targetFps
                )
        val summary = mutableMapOf<String, Any>(
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
            "samples" to sessionSamples,
            "optimization_source" to optimizationSource,
            "optimization_confidence" to optimizationConfidence,
            "recommendation_version" to recommendationVersion
        )
        if (sessionLowOnePercentFps > 0.0) summary["low_1_percent_fps"] = sessionLowOnePercentFps
        if (sessionMinFps > 0.0) summary["min_fps"] = sessionMinFps
        if (badPacingPct > 0.0) summary["frame_pacing_bad_pct"] = badPacingPct
        if (derivedSafeTargetFps > 0.0) summary["safe_target_fps"] = derivedSafeTargetFps
        if (healthGrade.isNotBlank()) summary["health_grade"] = healthGrade
        val primaryIssue = when {
            hostRenderLimited -> "host_render_limited"
            healthPrimaryIssue.isNotBlank() -> healthPrimaryIssue
            else -> ""
        }
        val issues = if (hostRenderLimited && healthIssues.none { it.equals("host_render_limited", ignoreCase = true) }) {
            healthIssues + "host_render_limited"
        } else {
            healthIssues
        }
        if (primaryIssue.isNotBlank()) summary["primary_issue"] = primaryIssue
        if (issues.isNotEmpty()) summary["issues"] = issues
        if (decoderRisk.isNotBlank()) summary["decoder_risk"] = decoderRisk
        if (hdrRisk.isNotBlank()) summary["hdr_risk"] = hdrRisk
        if (networkRisk.isNotBlank()) summary["network_risk"] = networkRisk
        if (capturePath.isNotBlank()) summary["capture_path"] = capturePath
        if (safeBitrateKbps > 0) summary["safe_bitrate_kbps"] = safeBitrateKbps
        if (safeCodec.isNotBlank()) summary["safe_codec"] = safeCodec
        if (safeDisplayMode.isNotBlank()) summary["safe_display_mode"] = safeDisplayMode
        safeHdr?.let { summary["safe_hdr"] = it }
        if (derivedRelaunchRecommended) summary["relaunch_recommended"] = true
        if (diagnosisClassification.isNotBlank()) summary["diagnosis_classification"] = diagnosisClassification
        if (diagnosisLikelyCause.isNotBlank()) summary["diagnosis_likely_cause"] = diagnosisLikelyCause
        if (diagnosisTryFirst.isNotBlank()) summary["diagnosis_try_first"] = diagnosisTryFirst
        if (diagnosisConfidence.isNotBlank()) summary["diagnosis_confidence"] = diagnosisConfidence
        return summary
    }

    private fun resolveCapturePath(status: PolarisSessionStatus): String {
        return when {
            status.isVirtualDisplayMode -> "virtual_display"
            status.capture.transport.equals("shm", ignoreCase = true) ||
                status.capture.residency.equals("cpu", ignoreCase = true) ||
                status.encoder.targetResidency.equals("cpu", ignoreCase = true) -> "cpu_fallback"
            status.isHeadlessMode -> "headless"
            else -> "desktop"
        }
    }
}
