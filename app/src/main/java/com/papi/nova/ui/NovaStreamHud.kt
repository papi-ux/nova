package com.papi.nova.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.papi.nova.R
import com.papi.nova.api.PolarisSessionStatus
import kotlin.math.abs

/**
 * Nova Performance HUD — real-time stream stats overlay.
 *
 * Interactive:
 *   - Drag to reposition anywhere on screen
 *   - Tap to cycle modes: full → banner → fps-only → (repeat)
 *
 * Modes:
 *   - "full"     — panel with sparkline, stat grid, per-stat colors
 *   - "banner"   — MangoHud-style one-line strip with inline sparkline
 *   - "fps_only" — NanoHUD compact stats capsule
 *
 * Per-stat dynamic colors:
 *   FPS:     green >= 55, amber 30-54, red < 30
 *   Latency: green <= 20ms, amber 21-50ms, red > 50ms
 *   Codec:   accent purple (static)
 *   Bitrate: ice white (static)
 */
class NovaStreamHud(private val activity: Activity) {

    private var hudView: View? = null
    private var fpsText: TextView? = null
    private var targetFpsText: TextView? = null
    private var codecText: TextView? = null
    private var bitrateText: TextView? = null
    private var latencyText: TextView? = null
    private var resolutionText: TextView? = null
    private var sparkline: SparklineView? = null
    private var fpsLowText: TextView? = null
    private var codecLabel: TextView? = null
    private var streamModeText: TextView? = null
    private var autopilotText: TextView? = null
    private var statusDot: View? = null
    private var activeCodecLabel = ""
    private var sessionModeLabel = ""
    private var targetFps = 0.0
    private var optimizationSource = ""
    private var optimizationConfidence = ""
    private var recommendationVersion = 0
    private var healthGrade = ""
    private var healthPrimaryIssue = ""
    private var healthLimitingFactor = ""
    private var healthAutoAction = ""
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
    private var clientPresentationStatus = ""
    private var clientPresentationAppliedRefreshRateHz = 0.0
    private var clientPresentationTargetRefreshRateHz = 0.0
    private var optimizerSyncState = ""
    private var optimizerSyncLabel = ""
    private var optimizerSyncMessage = ""
    private var lastSessionStatus: PolarisSessionStatus? = null
    private var autoQualityState = AutoQualityUiState.from(null)

    // Mode cycling: full → banner → fps_only → full
    private val modes = listOf("full", "banner", "fps_only")
    private var currentModeIndex = 0

    // Proactive quality monitor — tracks degradation and triggers bitrate adjustment
    private var lastFps = 0.0
    private var lastLatency = 0.0
    private var degradedFrames = 0       // consecutive low-quality samples
    private var recoveredFrames = 0      // consecutive healthy samples
    private var currentBitrateKbps = 0   // last known bitrate
    private var bitrateReduced = false
    private var hostAdaptiveBitrateActive = false
    private var hostAdaptiveTargetBitrateKbps = 0
    private var streamPolicy = StreamPolicyUiState.from(null)
    var onBitrateAdjust: ((Int) -> Unit)? = null  // callback to adjust bitrate via API

    // Session stats accumulator for end-of-session report
    private var sessionFpsSum = 0.0
    private var sessionLatencySum = 0.0
    private var sessionPacketLossSum = 0.0
    private var sessionPacketLossSamples = 0
    private var sessionSamples = 0
    private var sessionStartTime = 0L
    private var sessionMinFps = 0.0
    private var sessionLowOnePercentFps = 0.0
    private var sessionBadPacingSamples = 0
    var lastCodec = ""
    var lastBitrateKbps = 0
    private var sessionBitrateSum = 0L
    private var sessionBitrateSamples = 0

    // Drag state
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var viewStartX = 0f
    private var viewStartY = 0f
    private var isDragging = false
    private val DRAG_THRESHOLD = 12f  // px — distinguish tap from drag

    // Sparkline data persists across mode switches
    private val sparklineData = mutableListOf<Float>()

    private val currentMode get() = modes[currentModeIndex]

    private fun resetSessionStats() {
        sessionFpsSum = 0.0
        sessionLatencySum = 0.0
        sessionPacketLossSum = 0.0
        sessionPacketLossSamples = 0
        sessionSamples = 0
        sessionStartTime = 0L
        sessionMinFps = 0.0
        sessionLowOnePercentFps = 0.0
        sessionBadPacingSamples = 0
        sessionBitrateSum = 0L
        sessionBitrateSamples = 0
        sparklineData.clear()
    }

    fun show() {
        if (hudView != null) return
        resetSessionStats()

        activity.runOnUiThread {
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val mode = prefs.getString("nova_polaris_hud_mode", "full") ?: "full"
            currentModeIndex = modes.indexOf(mode).coerceAtLeast(0)

            inflateCurrentMode()
        }
    }

    private fun inflateCurrentMode() {
        // Remove existing view if any
        hudView?.let { view ->
            val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            rootView.removeView(view)
        }

        val layoutId = when (currentMode) {
            "banner" -> R.layout.nova_stream_hud_banner
            "fps_only" -> R.layout.nova_stream_hud_fps
            else -> R.layout.nova_stream_hud
        }

        val inflater = LayoutInflater.from(activity)
        hudView = inflater.inflate(layoutId, null)

        // Apply OLED theme if active
        if (NovaThemeManager.isOled(activity)) {
            hudView?.setBackgroundResource(
                if (currentMode == "banner") R.drawable.nova_hud_bg_oled
                else R.drawable.nova_hud_bg_oled
            )
        }

        // Wire up view references (some may be null depending on mode)
        fpsText = hudView?.findViewById(R.id.hud_fps)
        targetFpsText = hudView?.findViewById(R.id.hud_target_fps)
        codecText = hudView?.findViewById(R.id.hud_codec)
        bitrateText = hudView?.findViewById(R.id.hud_bitrate)
        latencyText = hudView?.findViewById(R.id.hud_latency)
        resolutionText = hudView?.findViewById(R.id.hud_resolution)
        sparkline = hudView?.findViewById(R.id.hud_sparkline)
        fpsLowText = hudView?.findViewById(R.id.hud_fps_low)
        codecLabel = hudView?.findViewById(R.id.hud_codec_label)
        streamModeText = hudView?.findViewById(R.id.hud_stream_mode)
        autopilotText = hudView?.findViewById(R.id.hud_autopilot)
        statusDot = hudView?.findViewById(R.id.hud_status_dot)

        // Restore sparkline data if switching modes
        sparkline?.let { sv ->
            for (v in sparklineData) sv.push(v)
        }

        renderTargetFps()
        renderStreamMode()
        renderAutopilotIndicator()
        if (activeCodecLabel.isNotBlank()) {
            applyCodecLabel(activeCodecLabel)
        }

        // Set up touch: drag + tap-to-cycle
        setupTouchHandler()

        // Position — use absolute positioning for drag support
        val margin = (12 * activity.resources.displayMetrics.density).toInt()
        val width = if (currentMode == "full") {
            (236 * activity.resources.displayMetrics.density).toInt()
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }

        val params = FrameLayout.LayoutParams(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = margin
            leftMargin = margin
        }

        val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(hudView, params)
    }

    private fun setupTouchHandler() {
        hudView?.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    viewStartX = view.x
                    viewStartY = view.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD) {
                        isDragging = true
                    }
                    if (isDragging) {
                        view.x = viewStartX + dx
                        view.y = viewStartY + dy
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Tap — cycle to next mode
                        cycleMode()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun cycleMode() {
        // Save current position
        val savedX = hudView?.x ?: 0f
        val savedY = hudView?.y ?: 0f

        currentModeIndex = (currentModeIndex + 1) % modes.size

        activity.runOnUiThread {
            inflateCurrentMode()
            // Restore position after layout
            hudView?.post {
                hudView?.x = savedX
                hudView?.y = savedY
            }
        }
    }

    /**
     * Parse key metrics from Moonlight's performance overlay text.
     */
    fun updateFromPerfText(text: String) {
        activity.runOnUiThread {
            if (hudView == null) return@runOnUiThread

            // FPS
            val fpsMatch = Regex("""(\d+(?:\.\d+)?)\s*(?:fps|FPS)""", RegexOption.IGNORE_CASE).find(text)
                ?: Regex("""FPS[:\s]+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(text)
                ?: Regex("""(\d+\.\d)\s*$""", RegexOption.MULTILINE).find(text.lines().firstOrNull() ?: "")
            if (fpsMatch != null) {
                updateFps(fpsMatch.groupValues[1].toDoubleOrNull() ?: 0.0)
            }

            val resMatch = Regex("""(\d{3,4})\s*[x×]\s*(\d{3,4})""").find(text)
            if (resMatch != null) {
                resolutionText?.text = if (currentMode == "banner") "${resMatch.groupValues[2]}p"
                    else "${resMatch.groupValues[1]}×${resMatch.groupValues[2]}"
            }

            // Latency
            val latMatch = Regex("""(?:RTT|latency)[^0-9]*(\d+)\s*ms""", RegexOption.IGNORE_CASE).find(text)
            if (latMatch != null) {
                updateLatency(latMatch.groupValues[1].toIntOrNull() ?: 0)
            }

            // Codec
            val codecMatch = Regex("""(?:decoder|codec)[:\s]+(\S+)""", RegexOption.IGNORE_CASE).find(text)
            if (codecMatch != null) {
                val codec = codecMatch.groupValues[1].uppercase()
                lastCodec = codec
                applyCodecLabel(codec)
            }

            // Packet loss / net drops
            val packetLossMatch = Regex(
                """(?:packet loss|frames dropped by your network connection|netdrops)[^0-9]*(\d+(?:\.\d+)?)\s*%""",
                RegexOption.IGNORE_CASE
            ).find(text)
                ?: Regex("""(\d+(?:\.\d+)?)\s*%\s*(?:packet loss|netdrops)""", RegexOption.IGNORE_CASE).find(text)
            if (packetLossMatch != null) {
                val packetLossPct = packetLossMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                sessionPacketLossSum += packetLossPct
                sessionPacketLossSamples++
            }
        }
    }

    fun setTargetBitrateKbps(bitrateKbps: Int) {
        currentBitrateKbps = bitrateKbps
        lastBitrateKbps = bitrateKbps
        streamPolicy = StreamPolicyUiState.from(lastSessionStatus, bitrateKbps, targetFps)
        renderBitrate(streamPolicy.effectiveBitrateKbps)
    }

    fun setTargetFps(fps: Double) {
        if (fps <= 0) {
            return
        }

        targetFps = fps
        activity.runOnUiThread { renderTargetFps() }
    }

    fun update(fps: Double, codec: String, bitrateKbps: Int, width: Int, height: Int, latencyMs: Double) {
        activity.runOnUiThread {
            updateFps(fps)
            val codecStr = normalizeCodecLabel(codec)
            applyCodecLabel(codecStr)
            streamPolicy = StreamPolicyUiState.from(
                lastSessionStatus,
                if (bitrateKbps > 0) bitrateKbps else lastBitrateKbps,
                targetFps
            )
            val displayBitrate = streamPolicy.effectiveBitrateKbps.takeIf { it > 0 } ?: bitrateKbps
            if (displayBitrate > 0) {
                currentBitrateKbps = displayBitrate
                sessionBitrateSum += displayBitrate.toLong()
                sessionBitrateSamples++
            }
            renderBitrate(displayBitrate)
            resolutionText?.text = if (currentMode == "banner") "${height}p" else "${width}×${height}"
            updateLatency(latencyMs.toInt())
        }
    }

    fun applySessionStatus(status: PolarisSessionStatus?) {
        activity.runOnUiThread {
            lastSessionStatus = status
            val resolvedTargetFps = status?.let(::resolveTargetFps) ?: 0.0
            if (resolvedTargetFps > 0) {
                targetFps = resolvedTargetFps
            }
            optimizationSource = status?.encoder?.optimizationSource.orEmpty()
            optimizationConfidence = status?.encoder?.optimizationConfidence.orEmpty()
            recommendationVersion = status?.encoder?.recommendationVersion ?: 0
            healthGrade = status?.health?.grade.orEmpty()
            healthPrimaryIssue = status?.health?.primaryIssue.orEmpty()
            healthLimitingFactor = status?.health?.limitingFactor.orEmpty()
            healthAutoAction = status?.health?.autoAction.orEmpty()
            healthIssues = status?.health?.issues ?: emptyList()
            decoderRisk = status?.health?.decoderRisk.orEmpty()
            hdrRisk = status?.health?.hdrRisk.orEmpty()
            networkRisk = status?.health?.networkRisk.orEmpty()
            hostRenderLimited = status?.isHostRenderLimited == true
            safeBitrateKbps = status?.health?.safeBitrateKbps ?: 0
            safeCodec = status?.health?.safeCodec.orEmpty()
            safeDisplayMode = status?.health?.safeDisplayMode.orEmpty()
            safeTargetFps = status?.health?.safeTargetFps ?: 0.0
            safeHdr = status?.health?.safeHdr
            relaunchRecommended = status?.health?.relaunchRecommended == true
            hostAdaptiveBitrateActive = status?.tuning?.adaptiveBitrateEnabled == true || status?.adaptiveBitrateEnabled == true
            hostAdaptiveTargetBitrateKbps = status?.tuning?.adaptiveTargetBitrateKbps
                ?: status?.adaptiveTargetBitrateKbps
                ?: 0
            streamPolicy = StreamPolicyUiState.from(status, lastBitrateKbps, targetFps)
            if (streamPolicy.effectiveBitrateKbps > 0) {
                currentBitrateKbps = streamPolicy.effectiveBitrateKbps
                renderBitrate(streamPolicy.effectiveBitrateKbps)
            }
            capturePath = resolveCapturePath(status)
            clientPresentationStatus = status?.clientPresentation?.status.orEmpty()
            clientPresentationAppliedRefreshRateHz = status?.clientPresentation?.appliedRefreshRateHz ?: 0.0
            optimizerSyncState = status?.syncStatus?.state.orEmpty()
            optimizerSyncLabel = status?.syncStatus?.label.orEmpty()
            optimizerSyncMessage = status?.syncStatus?.message.orEmpty()
            val reportedTargetRefresh = status?.clientPresentation?.targetRefreshRateHz ?: 0.0
            clientPresentationTargetRefreshRateHz = if (reportedTargetRefresh > 0.0) {
                reportedTargetRefresh
            } else {
                status?.presentationPolicy?.targetRefreshRateHz ?: 0.0
            }
            autoQualityState = AutoQualityUiState.from(status, targetFps, lastFps)
            sessionModeLabel = status?.let(::buildSessionModeLabel) ?: ""
            renderTargetFps()
            renderStreamMode()
            renderAutopilotIndicator()

            if (currentMode == "fps_only") {
                return@runOnUiThread
            }

            if (activeCodecLabel.isNotBlank()) {
                applyCodecLabel(activeCodecLabel)
            } else if (currentMode == "banner") {
                codecLabel?.text = sessionModeLabel
            }
        }
    }

    private fun resolveCapturePath(status: PolarisSessionStatus?): String {
        if (status == null) {
            return ""
        }
        return when {
            status.isVirtualDisplayMode -> "virtual_display"
            status.capture.transport.equals("shm", ignoreCase = true) ||
                status.capture.residency.equals("cpu", ignoreCase = true) ||
                status.encoder.targetResidency.equals("cpu", ignoreCase = true) -> "cpu_fallback"
            status.isHeadlessMode -> "headless"
            else -> "desktop"
        }
    }

    private fun resolveTargetFps(status: PolarisSessionStatus): Double {
        return when {
            status.encoder.sessionTargetFps > 0 -> status.encoder.sessionTargetFps
            status.encoder.encodeTargetFps > 0 -> status.encoder.encodeTargetFps
            status.encoder.requestedClientFps > 0 -> status.encoder.requestedClientFps
            else -> 0.0
        }
    }

    private fun buildSessionModeLabel(status: PolarisSessionStatus): String {
        val mode = when {
            status.isHeadlessMode -> activity.getString(R.string.nova_session_mode_headless)
            status.isVirtualDisplayMode -> activity.getString(R.string.nova_session_mode_virtual_display)
            else -> activity.getString(R.string.nova_session_mode_host_display)
        }
        val bitDepth = if (status.isTenBitActive) "10b" else "8b"
        val path = when {
            status.isGpuPath -> "GPU"
            status.encoder.targetResidency.equals("cpu", ignoreCase = true) -> "CPU"
            else -> ""
        }
        val modeSource = when (status.displayMode.requested) {
            "auto" -> "AUTO"
            "headless", "virtual_display" -> "EXP"
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

    private fun applyCodecLabel(codec: String) {
        val normalized = normalizeCodecLabel(codec)
        activeCodecLabel = normalized
        if (currentMode == "banner") {
            val decorated = listOf(normalized, sessionModeLabel)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            codecLabel?.text = decorated
        } else {
            codecText?.text = normalized
        }
    }

    private fun normalizeCodecLabel(codec: String): String {
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

    private fun renderTargetFps() {
        val view = targetFpsText ?: return
        if (targetFps <= 0) {
            view.visibility = View.GONE
            return
        }

        val rounded = targetFps.toInt()
        view.visibility = View.VISIBLE
        view.text = when (currentMode) {
            "banner", "fps_only" -> "/$rounded"
            else -> "TGT $rounded"
        }
    }

    private fun renderStreamMode() {
        if (currentMode == "banner") {
            if (activeCodecLabel.isNotBlank()) {
                applyCodecLabel(activeCodecLabel)
            } else if (sessionModeLabel.isNotBlank()) {
                codecLabel?.text = sessionModeLabel
            }
            return
        }

        streamModeText?.text = sessionModeLabel
        streamModeText?.visibility = if (sessionModeLabel.isNotBlank()) View.VISIBLE else View.GONE
    }

    private data class AutopilotIndicator(
        val fullLabel: String,
        val compactLabel: String,
        val color: Int
    )

    private fun renderAutopilotIndicator() {
        val indicator = buildAutopilotIndicator()
        autopilotText?.text = when (currentMode) {
            "banner", "fps_only" -> indicator.compactLabel
            else -> indicator.fullLabel
        }
        autopilotText?.setTextColor(indicator.color)
        setStatusDotColor(indicator.color)
    }

    private fun renderBitrate(bitrateKbps: Int) {
        if (bitrateKbps <= 0) {
            return
        }
        val label = StreamPolicyUiState.formatMbps(bitrateKbps)
        bitrateText?.text = if (currentMode == "banner" || currentMode == "fps_only") {
            label.replace(" Mbps", "M").replace(" ", "")
        } else {
            label
        }
    }

    private fun buildAutopilotIndicator(): AutopilotIndicator {
        val red = 0xFFf87171.toInt()
        val amber = 0xFFfbbf24.toInt()
        val green = 0xFF4ade80.toInt()
        val blue = 0xFF38bdf8.toInt()
        val muted = 0xFF94a3b8.toInt()

        val state = AutoQualityUiState.from(lastSessionStatus, targetFps, lastFps)
        autoQualityState = state
        val color = when (state.tone) {
            AutoQualityUiState.Tone.DANGER -> red
            AutoQualityUiState.Tone.WARNING -> amber
            AutoQualityUiState.Tone.INFO -> blue
            AutoQualityUiState.Tone.STABLE -> green
            AutoQualityUiState.Tone.MUTED -> muted
        }
        return AutopilotIndicator(state.label, state.compactLabel, color)
    }

    private fun isHostRenderLimited(): Boolean {
        return hostRenderLimited ||
            healthLimitingFactor.equals("host_render", ignoreCase = true) ||
            healthPrimaryIssue.equals("host_render_limited", ignoreCase = true) ||
            healthIssues.any { it.equals("host_render_limited", ignoreCase = true) }
    }

    private fun formatRefreshRateLabel(): String {
        val refresh = when {
            clientPresentationAppliedRefreshRateHz > 0.0 -> clientPresentationAppliedRefreshRateHz
            clientPresentationTargetRefreshRateHz > 0.0 -> clientPresentationTargetRefreshRateHz
            targetFps > 0.0 -> targetFps
            else -> 0.0
        }
        if (refresh <= 0.0) {
            return "Display"
        }
        return "${refresh.toInt()} Hz"
    }

    private fun isBelowTargetFps(): Boolean {
        if (targetFps <= 0.0 || lastFps <= 0.0) {
            return false
        }
        return lastFps < targetFps - 4.0
    }

    private fun isSeverelyBelowTargetFps(): Boolean {
        if (targetFps <= 0.0 || lastFps <= 0.0) {
            return false
        }
        return lastFps < targetFps * 0.75
    }

    private fun setStatusDotColor(color: Int) {
        val dot = statusDot ?: return
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun updateFps(fps: Double) {
        val fpsInt = fps.toInt()
        fpsText?.text = "$fpsInt"

        val color = when {
            fpsInt >= 55 -> 0xFF4ade80.toInt()  // green
            fpsInt >= 30 -> 0xFFfbbf24.toInt()  // amber
            else -> 0xFFf87171.toInt()           // red
        }
        fpsText?.setTextColor(color)

        // Feed sparkline and persist data
        sparkline?.lineColor = color
        sparkline?.push(fps.toFloat())
        sparklineData.add(fps.toFloat())
        if (sparklineData.size > 60) sparklineData.removeAt(0)

        // Update 1% low metric (stutter detection)
        val low1 = sparkline?.get1PercentLow()?.toInt() ?: 0
        if (low1 > 0) {
            sessionLowOnePercentFps = low1.toDouble()
            fpsLowText?.text = "1%: $low1"
        }

        // Accumulate session stats
        lastFps = fps
        sessionFpsSum += fps
        sessionSamples++
        if (fps > 0.0 && (sessionMinFps <= 0.0 || fps < sessionMinFps)) {
            sessionMinFps = fps
        }
        if (targetFps > 0.0 && fps > 0.0 && fps < targetFps * 0.85) {
            sessionBadPacingSamples++
        }
        if (sessionStartTime == 0L) sessionStartTime = System.currentTimeMillis()
        autoQualityState = AutoQualityUiState.from(lastSessionStatus, targetFps, fps)
        renderAutopilotIndicator()

        if (hostAdaptiveBitrateActive) {
            degradedFrames = 0
            recoveredFrames = 0
            bitrateReduced = false
            return
        }

        // Proactive quality monitor: detect sustained degradation
        if (fpsInt < 45 || lastLatency > 50) {
            degradedFrames++
            recoveredFrames = 0
            // If degraded for 3+ consecutive updates (~3 seconds), reduce bitrate
            if (degradedFrames >= 3 && !bitrateReduced && currentBitrateKbps > 3000) {
                val newBitrate = (currentBitrateKbps * 0.75).toInt().coerceAtLeast(2000)
                onBitrateAdjust?.invoke(newBitrate)
                currentBitrateKbps = newBitrate
                bitrateReduced = true
                degradedFrames = 0
            }
        } else {
            recoveredFrames++
            degradedFrames = 0
            // If healthy for 10+ updates (~10 seconds), restore bitrate
            if (recoveredFrames >= 10 && bitrateReduced) {
                val newBitrate = (currentBitrateKbps * 1.15).toInt().coerceAtMost(lastBitrateKbps)
                onBitrateAdjust?.invoke(newBitrate)
                currentBitrateKbps = newBitrate
                if (currentBitrateKbps >= lastBitrateKbps) bitrateReduced = false
                recoveredFrames = 0
            }
        }
    }

    private fun updateLatency(ms: Int) {
        lastLatency = ms.toDouble()
        sessionLatencySum += ms.toDouble()
        latencyText?.text = "${ms}ms"
        latencyText?.setTextColor(when {
            ms <= 20 -> 0xFF4ade80.toInt()
            ms <= 50 -> 0xFFfbbf24.toInt()
            else -> 0xFFf87171.toInt()
        })
    }

    fun dismiss() {
        activity.runOnUiThread {
            val view = hudView
            hudView = null
            sparklineData.clear()
            view?.let { safeRemoveFromParent(it) }
        }
    }

    private fun safeRemoveFromParent(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        parent.post {
            val currentParent = view.parent as? ViewGroup
            currentParent?.removeView(view)
        }
    }

    /** Get session summary for end-of-session AI report. */
    fun getSessionSummary(): Map<String, Any> {
        val durationS = if (sessionStartTime > 0)
            ((System.currentTimeMillis() - sessionStartTime) / 1000).toInt() else 0
        val avgFps = if (sessionSamples > 0) sessionFpsSum / sessionSamples else 0.0
        val badPacingPct = if (sessionSamples > 0)
            (sessionBadPacingSamples.toDouble() / sessionSamples.toDouble()) * 100.0 else 0.0
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
            "avg_latency_ms" to if (sessionSamples > 0) sessionLatencySum / sessionSamples else 0.0,
            "packet_loss_pct" to if (sessionPacketLossSamples > 0) sessionPacketLossSum / sessionPacketLossSamples else 0.0,
            "avg_bitrate_kbps" to if (sessionBitrateSamples > 0) (sessionBitrateSum / sessionBitrateSamples).toInt() else lastBitrateKbps,
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
        val summaryPrimaryIssue = when {
            isHostRenderLimited() -> "host_render_limited"
            healthPrimaryIssue.isNotBlank() -> healthPrimaryIssue
            else -> ""
        }
        val summaryIssues = if (isHostRenderLimited() && healthIssues.none { it.equals("host_render_limited", ignoreCase = true) }) {
            healthIssues + "host_render_limited"
        } else {
            healthIssues
        }
        if (summaryPrimaryIssue.isNotBlank()) summary["primary_issue"] = summaryPrimaryIssue
        if (summaryIssues.isNotEmpty()) summary["issues"] = summaryIssues
        if (decoderRisk.isNotBlank()) summary["decoder_risk"] = decoderRisk
        if (hdrRisk.isNotBlank()) summary["hdr_risk"] = hdrRisk
        if (networkRisk.isNotBlank()) summary["network_risk"] = networkRisk
        if (capturePath.isNotBlank()) summary["capture_path"] = capturePath
        if (safeBitrateKbps > 0) summary["safe_bitrate_kbps"] = safeBitrateKbps
        if (safeCodec.isNotBlank()) summary["safe_codec"] = safeCodec
        if (safeDisplayMode.isNotBlank()) summary["safe_display_mode"] = safeDisplayMode
        safeHdr?.let { summary["safe_hdr"] = it }
        if (derivedRelaunchRecommended) summary["relaunch_recommended"] = true
        return summary
    }

    val isShowing get() = hudView != null

    companion object {
        fun isEnabled(activity: Activity): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean("nova_polaris_hud", false)
        }
    }
}
