package com.papi.nova.ui

import android.app.Activity
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
 *   - "fps_only" — tiny floating FPS counter only
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
    private var activeCodecLabel = ""
    private var sessionModeLabel = ""
    private var targetFps = 0.0
    private var optimizationSource = ""
    private var optimizationConfidence = ""
    private var recommendationVersion = 0

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
    var onBitrateAdjust: ((Int) -> Unit)? = null  // callback to adjust bitrate via API

    // Session stats accumulator for end-of-session report
    private var sessionFpsSum = 0.0
    private var sessionLatencySum = 0.0
    private var sessionPacketLossSum = 0.0
    private var sessionPacketLossSamples = 0
    private var sessionSamples = 0
    private var sessionStartTime = 0L
    var lastCodec = ""
    var lastBitrateKbps = 0

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

    fun show() {
        if (hudView != null) return

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

        // Restore sparkline data if switching modes
        sparkline?.let { sv ->
            for (v in sparklineData) sv.push(v)
        }

        renderTargetFps()
        renderStreamMode()
        if (activeCodecLabel.isNotBlank()) {
            applyCodecLabel(activeCodecLabel)
        }

        // Set up touch: drag + tap-to-cycle
        setupTouchHandler()

        // Position — use absolute positioning for drag support
        val margin = (12 * activity.resources.displayMetrics.density).toInt()
        val width = if (currentMode == "full") {
            (232 * activity.resources.displayMetrics.density).toInt()
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

            // Resolution (not in fps_only mode)
            if (currentMode != "fps_only") {
                val resMatch = Regex("""(\d{3,4})\s*[x×]\s*(\d{3,4})""").find(text)
                if (resMatch != null) {
                    resolutionText?.text = if (currentMode == "banner") "  ${resMatch.groupValues[2]}p"
                        else "${resMatch.groupValues[1]}×${resMatch.groupValues[2]}"
                }
            }

            // Latency
            if (currentMode != "fps_only") {
                val latMatch = Regex("""(?:RTT|latency)[^0-9]*(\d+)\s*ms""", RegexOption.IGNORE_CASE).find(text)
                if (latMatch != null) {
                    updateLatency(latMatch.groupValues[1].toIntOrNull() ?: 0)
                }
            }

            // Codec
            if (currentMode != "fps_only") {
                val codecMatch = Regex("""(?:decoder|codec)[:\s]+(\S+)""", RegexOption.IGNORE_CASE).find(text)
                if (codecMatch != null) {
                    val codec = codecMatch.groupValues[1].uppercase()
                    lastCodec = codec
                    applyCodecLabel(codec)
                }
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
            if (currentMode != "fps_only") {
                val codecStr = normalizeCodecLabel(codec)
                applyCodecLabel(codecStr)
                bitrateText?.text = if (currentMode == "banner") "  ${bitrateKbps / 1000}Mbps"
                    else "${bitrateKbps / 1000} Mbps"
                resolutionText?.text = if (currentMode == "banner") "  ${height}p" else "${width}×${height}"
                updateLatency(latencyMs.toInt())
            }
        }
    }

    fun applySessionStatus(status: PolarisSessionStatus?) {
        activity.runOnUiThread {
            val resolvedTargetFps = status?.let(::resolveTargetFps) ?: 0.0
            if (resolvedTargetFps > 0) {
                targetFps = resolvedTargetFps
            }
            optimizationSource = status?.encoder?.optimizationSource.orEmpty()
            optimizationConfidence = status?.encoder?.optimizationConfidence.orEmpty()
            recommendationVersion = status?.encoder?.recommendationVersion ?: 0
            sessionModeLabel = status?.let(::buildSessionModeLabel) ?: ""
            renderTargetFps()
            renderStreamMode()

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

    private fun updateFps(fps: Double) {
        val fpsInt = fps.toInt()
        fpsText?.text = if (currentMode == "banner") "  $fpsInt" else "$fpsInt"

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
        if (low1 > 0) fpsLowText?.text = "1%: $low1"

        // Accumulate session stats
        lastFps = fps
        sessionFpsSum += fps
        sessionSamples++
        if (sessionStartTime == 0L) sessionStartTime = System.currentTimeMillis()

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
        latencyText?.text = if (currentMode == "banner") "  ${ms}ms" else "${ms}ms"
        latencyText?.setTextColor(when {
            ms <= 20 -> 0xFF4ade80.toInt()
            ms <= 50 -> 0xFFfbbf24.toInt()
            else -> 0xFFf87171.toInt()
        })
    }

    fun dismiss() {
        activity.runOnUiThread {
            hudView?.let { view ->
                val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
                rootView.removeView(view)
            }
            hudView = null
            sparklineData.clear()
        }
    }

    /** Get session summary for end-of-session AI report. */
    fun getSessionSummary(): Map<String, Any> {
        val durationS = if (sessionStartTime > 0)
            ((System.currentTimeMillis() - sessionStartTime) / 1000).toInt() else 0
        return mapOf(
            "avg_fps" to if (sessionSamples > 0) sessionFpsSum / sessionSamples else 0.0,
            "target_fps" to targetFps,
            "avg_latency_ms" to if (sessionSamples > 0) sessionLatencySum / sessionSamples else 0.0,
            "packet_loss_pct" to if (sessionPacketLossSamples > 0) sessionPacketLossSum / sessionPacketLossSamples else 0.0,
            "avg_bitrate_kbps" to lastBitrateKbps,
            "codec" to lastCodec,
            "duration_s" to durationS,
            "samples" to sessionSamples,
            "optimization_source" to optimizationSource,
            "optimization_confidence" to optimizationConfidence,
            "recommendation_version" to recommendationVersion
        )
    }

    val isShowing get() = hudView != null

    companion object {
        fun isEnabled(activity: Activity): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean("nova_polaris_hud", false)
        }
    }
}
