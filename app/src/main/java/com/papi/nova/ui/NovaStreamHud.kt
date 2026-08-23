package com.papi.nova.ui

import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
class NovaStreamHud(
    private val activity: Activity,
    private val onCommandCenterRequested: (() -> Unit)? = null
) {
    private var hudView: ComposeView? = null
    private val hudState = mutableStateOf(NovaHudUiState.empty())
    private val sessionStats = NovaHudSessionStats()
    private val eventTrail = NovaHudEventTrail()
    private val longPressHandler = Handler(Looper.getMainLooper())

    private var currentMode = NovaHudMode.MINIMAL
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
    private val hudOpacityScale = mutableStateOf(1f)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == NovaHudPreferences.KEY_OPACITY) {
            hudOpacityScale.value = NovaHudPreferences.opacityScale(
                NovaHudPreferences.readOpacityPercent(prefs)
            )
        }
    }

    var onBitrateAdjust: ((Int) -> Unit)? = null

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var viewStartX = 0f
    private var viewStartY = 0f
    private var isDragging = false
    private var longPressTriggered = false
    private var forwardingTap = false
    private var pendingLongPress: Runnable? = null

    fun show() {
        activity.runOnUiThread {
            if (hudView != null) {
                return@runOnUiThread
            }
            resetSessionState()
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            currentMode = NovaHudMode.fromPreference(prefs.getString("nova_polaris_hud_mode", "minimal"))
            hudOpacityScale.value = NovaHudPreferences.opacityScale(
                NovaHudPreferences.readOpacityPercent(prefs)
            )
            prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
            publishState()

            val composeView = ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    NovaComposeTheme(menuOpacityPercent = NovaMenuPreferences.MAX_OPACITY_PERCENT) {
                        NovaStreamHudContent(
                            state = hudState.value,
                            opacityScale = hudOpacityScale.value
                        )
                    }
                }
            }
            setupTouchHandler(composeView)
            hudView = composeView

            val margin = (12 * activity.resources.displayMetrics.density).toInt()
            val params = FrameLayout.LayoutParams(
                HUD_LAYOUT_WIDTH,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = margin
                leftMargin = margin
            }
            val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            rootView.addView(composeView, params)
            rootView.post {
                restoreHudPosition(composeView, rootView, margin.toFloat())
            }
        }
    }

    private fun resetSessionState() {
        sessionStats.reset()
        sparklineData.clear()
        degradedFrames = 0
        recoveredFrames = 0
        bitrateReduced = false
        eventTrail.clear()
    }

    private fun setupTouchHandler(view: View) {
        view.setOnTouchListener { touchedView, event ->
            // Standing down while a tap is being replayed, so the dispatch in
            // forwardTapToStream() falls through this view to the stream surface.
            if (forwardingTap) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    viewStartX = touchedView.x
                    viewStartY = touchedView.y
                    isDragging = false
                    longPressTriggered = false
                    scheduleLongPress(touchedView)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD) {
                        isDragging = true
                        cancelLongPress()
                    }
                    if (isDragging) {
                        touchedView.x = viewStartX + dx
                        touchedView.y = viewStartY + dy
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    when {
                        longPressTriggered -> Unit
                        isDragging -> clampAndSaveHudPosition(touchedView)
                        // A tap was never the HUD's to keep: the readout sits over the
                        // game, and a press that neither dragged it nor held it was
                        // aimed at what is underneath. Only a drag or a long-press
                        // claims the gesture; mode cycling stays on the Command
                        // Center action that already does it.
                        else -> forwardTapToStream(event)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Replay a plain tap beneath the HUD.
     *
     * Android hands every later event of a gesture to whoever consumed its DOWN, so a
     * listener that wants to recognise drags and long-presses has no choice but to
     * claim the DOWN — by the time the gesture turns out to have been a tap, the game
     * never saw it. The repair is to replay it: with the listener standing down, decor
     * dispatch walks past this view and delivers the same coordinates to the stream
     * surface underneath.
     */
    private fun forwardTapToStream(event: MotionEvent) {
        val root = activity.window.decorView
        forwardingTap = true
        try {
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, event.rawX, event.rawY, 0)
            val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, event.rawX, event.rawY, 0)
            try {
                root.dispatchTouchEvent(down)
                root.dispatchTouchEvent(up)
            } finally {
                down.recycle()
                up.recycle()
            }
        } finally {
            forwardingTap = false
        }
    }

    private fun scheduleLongPress(view: View) {
        cancelLongPress()
        val task = Runnable {
            longPressTriggered = true
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onCommandCenterRequested?.invoke()
        }
        pendingLongPress = task
        longPressHandler.postDelayed(task, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelLongPress() {
        pendingLongPress?.let(longPressHandler::removeCallbacks)
        pendingLongPress = null
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
        // No layoutParams reassignment: every mode is WRAP_CONTENT, so this forced a
        // re-layout to set the width to the width it already had.
        publishState()
        view.post {
            view.x = savedX
            view.y = savedY
            clampAndSaveHudPosition(view)
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
            val safeTarget = status?.health?.safeTargetFps ?: 0.0
            val recoveryQueued = status?.autoQuality?.isRecoveryQueued == true
            if (safeTarget > 0.0 && (recoveryQueued || status?.health?.relaunchRecommended == true)) {
                eventTrail.recordRecoveryProfile(safeTarget, recoveryQueued = recoveryQueued)
            }
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
                val previousBitrate = currentBitrateKbps
                val newBitrate = (currentBitrateKbps * 0.75).toInt().coerceAtLeast(2000)
                onBitrateAdjust?.invoke(newBitrate)
                eventTrail.recordBitrateChange(previousBitrate, newBitrate)
                currentBitrateKbps = newBitrate
                bitrateReduced = true
                degradedFrames = 0
            }
        } else {
            recoveredFrames++
            degradedFrames = 0
            if (recoveredFrames >= 10 && bitrateReduced) {
                val previousBitrate = currentBitrateKbps
                val newBitrate = (currentBitrateKbps * 1.15).toInt().coerceAtMost(lastBitrateKbps)
                onBitrateAdjust?.invoke(newBitrate)
                eventTrail.recordBitrateChange(previousBitrate, newBitrate)
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
            eventBreadcrumbLabel = eventTrail.latestLabel,
            lowOnePercentFps = sparklineData.lowOnePercent()
        )
    }

    private fun restoreHudPosition(view: View, rootView: ViewGroup, fallbackMargin: Float) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val savedX = prefs.getFloat(PREF_HUD_X, Float.NaN)
        val savedY = prefs.getFloat(PREF_HUD_Y, Float.NaN)
        if (savedX.isNaN() || savedY.isNaN()) {
            return
        }
        val clamped = clampHudPosition(view, rootView, savedX, savedY, fallbackMargin)
        view.x = clamped.first
        view.y = clamped.second
    }

    private fun clampAndSaveHudPosition(view: View) {
        val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        val margin = HUD_SAFE_MARGIN_DP * activity.resources.displayMetrics.density
        val clamped = clampHudPosition(view, rootView, view.x, view.y, margin)
        view.x = clamped.first
        view.y = clamped.second
        saveHudPosition(clamped.first, clamped.second)
    }

    private fun clampHudPosition(
        view: View,
        rootView: ViewGroup,
        desiredX: Float,
        desiredY: Float,
        margin: Float
    ): Pair<Float, Float> {
        val viewWidth = view.width.takeIf { it > 0 } ?: view.measuredWidth.takeIf { it > 0 } ?: 1
        val viewHeight = view.height.takeIf { it > 0 } ?: view.measuredHeight.takeIf { it > 0 } ?: 1
        val maxX = (rootView.width - viewWidth - margin).coerceAtLeast(margin)
        val maxY = (rootView.height - viewHeight - margin).coerceAtLeast(margin)
        return desiredX.coerceIn(margin, maxX) to desiredY.coerceIn(margin, maxY)
    }

    private fun saveHudPosition(x: Float, y: Float) {
        PreferenceManager.getDefaultSharedPreferences(activity)
            .edit()
            .putFloat(PREF_HUD_X, x)
            .putFloat(PREF_HUD_Y, y)
            .apply()
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
            PreferenceManager.getDefaultSharedPreferences(activity)
                .unregisterOnSharedPreferenceChangeListener(preferenceListener)
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

    fun getDiagnosticSummaryText(): String = NovaHudDiagnosticReport.format(getSessionSummary())

    val isShowing get() = hudView != null

    companion object {
        private const val DRAG_THRESHOLD = 12f
        private const val HUD_SAFE_MARGIN_DP = 12f
        private const val PREF_HUD_X = "nova_polaris_hud_x"
        private const val PREF_HUD_Y = "nova_polaris_hud_y"
        fun isEnabled(activity: Activity): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean("nova_polaris_hud", false)
        }

        /**
         * The HUD is content-sized in every mode.
         *
         * This was a `when` over all three modes returning WRAP_CONTENT three times,
         * which read as though the width varied by mode. It does not, and each mode's
         * own composable already bounds itself.
         */
        private const val HUD_LAYOUT_WIDTH = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
