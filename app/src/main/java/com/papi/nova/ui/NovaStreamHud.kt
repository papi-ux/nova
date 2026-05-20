package com.papi.nova.ui

import android.app.Activity
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.preference.PreferenceManager
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.binding.video.PerfOverlaySample
import com.papi.nova.ui.compose.NovaComposeTheme
import kotlin.math.abs

/**
 * Nova Performance HUD — Compose-backed real-time stream stats overlay.
 *
 * The public methods are intentionally kept stable for Game.java:
 * show/dismiss, metric updates, Polaris status updates, and session summary reporting.
 */
class NovaStreamHud(private val activity: Activity) {
    private var hudView: ComposeView? = null
    private val hudState = mutableStateOf(NovaHudUiState.empty())
    private val sessionStats = NovaHudSessionStats()

    private var currentMode = NovaHudMode.FULL
    private var targetFps = 0.0
    private var lastFps = 0.0
    private var lastLatency = 0.0
    private var currentBitrateKbps = 0
    var lastCodec = ""
    var lastBitrateKbps = 0
    private var width = 0
    private var height = 0
    private var activeCodecLabel = ""
    private var lastSessionStatus: PolarisSessionStatus? = null
    private var streamPolicy = StreamPolicyUiState.from(null)
    private var hostAdaptiveBitrateActive = false
    private var degradedFrames = 0
    private var recoveredFrames = 0
    private var bitrateReduced = false
    private val sparklineData = NovaHudSparklineBuffer()

    var onBitrateAdjust: ((Int) -> Unit)? = null

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var viewStartX = 0f
    private var viewStartY = 0f
    private var isDragging = false

    fun show() {
        activity.runOnUiThread {
            if (hudView != null) {
                return@runOnUiThread
            }
            resetSessionState()
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            currentMode = NovaHudMode.fromPreference(prefs.getString("nova_polaris_hud_mode", "full"))
            publishState()

            val composeView = ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    NovaComposeTheme {
                        NovaStreamHudContent(state = hudState.value)
                    }
                }
            }
            setupTouchHandler(composeView)
            hudView = composeView

            val margin = (12 * activity.resources.displayMetrics.density).toInt()
            val params = FrameLayout.LayoutParams(
                layoutWidthForMode(currentMode),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = margin
                leftMargin = margin
            }
            val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            rootView.addView(composeView, params)
        }
    }

    private fun resetSessionState() {
        sessionStats.reset()
        sparklineData.clear()
        degradedFrames = 0
        recoveredFrames = 0
        bitrateReduced = false
    }

    private fun setupTouchHandler(view: View) {
        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    viewStartX = touchedView.x
                    viewStartY = touchedView.y
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
                        touchedView.x = viewStartX + dx
                        touchedView.y = viewStartY + dy
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        cycleMode()
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun cycleMode() {
        val view = hudView ?: return
        val savedX = view.x
        val savedY = view.y
        currentMode = currentMode.next()
        PreferenceManager.getDefaultSharedPreferences(activity)
            .edit()
            .putString("nova_polaris_hud_mode", currentMode.preferenceValue)
            .apply()
        view.layoutParams = (view.layoutParams as FrameLayout.LayoutParams).apply {
            width = layoutWidthForMode(currentMode)
        }
        publishState()
        view.post {
            view.x = savedX
            view.y = savedY
        }
    }

    fun updateFromPerfText(text: String) {
        val sample = NovaHudPerfSample.fromPerfText(text)
        activity.runOnUiThread {
            if (hudView == null) return@runOnUiThread
            sample.fps?.let(::updateFps)
            if (sample.width != null && sample.height != null) {
                width = sample.width
                height = sample.height
            }
            sample.latencyMs?.let(::updateLatency)
            sample.codec?.let(::applyCodecLabel)
            sample.packetLossPct?.let(sessionStats::recordPacketLoss)
            publishState()
        }
    }

    fun updateFromPerfSample(sample: PerfOverlaySample) {
        activity.runOnUiThread {
            if (hudView == null) return@runOnUiThread
            updateFps(sample.fps)
            width = sample.width
            height = sample.height
            updateLatency(sample.rttMs)
            applyCodecLabel(sample.codec)
            sessionStats.recordPacketLoss(sample.packetLossPct)
            publishState()
        }
    }

    fun setTargetBitrateKbps(bitrateKbps: Int) {
        currentBitrateKbps = bitrateKbps
        lastBitrateKbps = bitrateKbps
        sessionStats.setLastBitrateKbps(bitrateKbps)
        streamPolicy = StreamPolicyUiState.from(lastSessionStatus, bitrateKbps, targetFps)
        publishState()
    }

    fun setTargetFps(fps: Double) {
        if (fps <= 0.0) {
            return
        }
        targetFps = fps
        sessionStats.setTargetFps(fps)
        activity.runOnUiThread { publishState() }
    }

    fun update(fps: Double, codec: String, bitrateKbps: Int, width: Int, height: Int, latencyMs: Double) {
        activity.runOnUiThread {
            updateFps(fps)
            applyCodecLabel(codec)
            this.width = width
            this.height = height
            streamPolicy = StreamPolicyUiState.from(
                lastSessionStatus,
                if (bitrateKbps > 0) bitrateKbps else lastBitrateKbps,
                targetFps
            )
            val displayBitrate = streamPolicy.effectiveBitrateKbps.takeIf { it > 0 } ?: bitrateKbps
            if (displayBitrate > 0) {
                currentBitrateKbps = displayBitrate
                sessionStats.recordBitrate(displayBitrate)
            }
            updateLatency(latencyMs.toInt())
            publishState()
        }
    }

    fun applySessionStatus(status: PolarisSessionStatus?) {
        activity.runOnUiThread {
            lastSessionStatus = status
            sessionStats.applySessionStatus(status)
            val resolvedTargetFps = status?.let(::resolveTargetFps) ?: 0.0
            if (resolvedTargetFps > 0.0) {
                targetFps = resolvedTargetFps
                sessionStats.setTargetFps(resolvedTargetFps)
            }
            hostAdaptiveBitrateActive = status?.tuning?.adaptiveBitrateEnabled == true ||
                status?.adaptiveBitrateEnabled == true
            streamPolicy = StreamPolicyUiState.from(status, lastBitrateKbps, targetFps)
            if (streamPolicy.effectiveBitrateKbps > 0) {
                currentBitrateKbps = streamPolicy.effectiveBitrateKbps
            }
            if (activeCodecLabel.isBlank() && status?.encoder?.codec?.isNotBlank() == true) {
                applyCodecLabel(status.encoder.codec)
            }
            publishState()
        }
    }

    private fun updateFps(fps: Double) {
        if (fps <= 0.0) {
            return
        }
        lastFps = fps
        sparklineData.add(fps.toFloat())
        sessionStats.recordFps(
            fps = fps,
            lowOnePercentFps = sparklineData.lowOnePercent()
        )

        if (hostAdaptiveBitrateActive) {
            degradedFrames = 0
            recoveredFrames = 0
            bitrateReduced = false
            return
        }

        val fpsInt = fps.toInt()
        if (fpsInt < 45 || lastLatency > 50) {
            degradedFrames++
            recoveredFrames = 0
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
            if (recoveredFrames >= 10 && bitrateReduced) {
                val newBitrate = (currentBitrateKbps * 1.15).toInt().coerceAtMost(lastBitrateKbps)
                onBitrateAdjust?.invoke(newBitrate)
                currentBitrateKbps = newBitrate
                if (currentBitrateKbps >= lastBitrateKbps) {
                    bitrateReduced = false
                }
                recoveredFrames = 0
            }
        }
    }

    private fun updateLatency(ms: Int) {
        if (ms <= 0) {
            return
        }
        lastLatency = ms.toDouble()
        sessionStats.recordLatency(ms)
    }

    private fun applyCodecLabel(codec: String) {
        val normalized = NovaHudUiState.normalizeCodecLabel(codec)
        activeCodecLabel = normalized
        if (normalized.isNotBlank()) {
            lastCodec = normalized
            sessionStats.setLastCodec(normalized)
        }
    }

    private fun publishState() {
        val displayBitrate = streamPolicy.effectiveBitrateKbps
            .takeIf { it > 0 }
            ?: currentBitrateKbps.takeIf { it > 0 }
            ?: lastBitrateKbps
        hudState.value = NovaHudUiState.from(
            mode = currentMode,
            fps = lastFps,
            targetFps = targetFps,
            latencyMs = lastLatency.toInt(),
            codec = activeCodecLabel.ifBlank { lastCodec },
            bitrateKbps = displayBitrate,
            width = width,
            height = height,
            status = lastSessionStatus,
            sparklineSamples = sparklineData.snapshot(),
            lowOnePercentFps = sparklineData.lowOnePercent()
        )
    }

    private fun resolveTargetFps(status: PolarisSessionStatus): Double {
        return listOf(
            status.encoder.sessionTargetFps,
            status.encoder.encodeTargetFps,
            status.encoder.requestedClientFps
        ).firstOrNull { it > 0.0 } ?: 0.0
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

    fun getSessionSummary(): Map<String, Any> = sessionStats.summary()

    val isShowing get() = hudView != null

    companion object {
        private const val DRAG_THRESHOLD = 12f

        fun isEnabled(activity: Activity): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean("nova_polaris_hud", false)
        }

        private fun layoutWidthForMode(mode: NovaHudMode): Int {
            return when (mode) {
                NovaHudMode.FULL -> ViewGroup.LayoutParams.WRAP_CONTENT
                NovaHudMode.BANNER -> ViewGroup.LayoutParams.WRAP_CONTENT
                NovaHudMode.FPS_ONLY -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }
}
