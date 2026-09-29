package com.papi.nova.ui

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Rect
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.semantics.CustomAccessibilityAction
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
    private var lastMediaAtMs: Long? = null
    private var lastHostAtMs: Long? = null
    private var lastMediaGeneration = 0L
    private var launchPresetLabel = ""
    private var positionRoot: ViewGroup? = null
    private val positionListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val view = hudView
        val root = positionRoot
        if (view != null && root != null && !isDragging) restoreHudPosition(view, root, hudMarginPx(true))
    }
    private val freshnessTick = object : Runnable {
        override fun run() {
            if (hudView == null) return
            if (activity.isFinishing || activity.isDestroyed) { dismiss(); return }
            publishState()
            longPressHandler.postDelayed(this, 1_000L)
        }
    }
    private var lastFps = Double.NaN
    private var lastLatency = 0.0
    private var lastLowOnePercentFps = 0.0
    private var lastDecodeTimeMs = 0.0
    private var lastHostLatencyMs: Double? = null
    private var lastIncomingFps = 0.0
    private var lastRenderedFps = 0.0
    private var lastPacketLossPct = -1.0
    private var lastRttVarianceMs = -1
    private var lastFramesLost = -1L
    private var currentBitrateKbps = 0
    var lastCodec = ""
    var lastBitrateKbps = 0
    private var width = 0
    private var height = 0
    private var activeCodecLabel = ""
    private var lastSessionStatus: PolarisSessionStatus? = null
    private var streamPolicy = StreamPolicyUiState.from(null)
    private val sparklineData = NovaHudSparklineBuffer()
    private val hudOpacityScale = mutableStateOf(1f)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == NovaHudPreferences.KEY_OPACITY) {
            hudOpacityScale.value = NovaHudPreferences.opacityScale(
                NovaHudPreferences.readOpacityPercent(prefs)
            )
        }
    }

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
            if (hudView != null || activity.isFinishing || activity.isDestroyed) {
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
                            opacityScale = hudOpacityScale.value,
                            accessibilityActions = listOf(
                                CustomAccessibilityAction("Open Command Center") { onCommandCenterRequested?.invoke(); true },
                                CustomAccessibilityAction("Reset HUD position") { resetPosition(); true }
                            ) + NovaHudCorner.entries.map { corner ->
                                CustomAccessibilityAction("Move HUD to ${corner.label}") { setPosition(corner); true }
                            }
                        )
                    }
                }
            }
            setupTouchHandler(composeView)
            hudView = composeView
            // Shown from the Command Center's own row, it starts as dim as the panel keeps it.
            composeView.alpha = coveredAlpha()

            val margin = (12 * activity.resources.displayMetrics.density).toInt()
            val params = FrameLayout.LayoutParams(
                HUD_LAYOUT_WIDTH,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = hudMarginPx(horizontal = false).toInt()
                leftMargin = hudMarginPx(horizontal = true).toInt()
                rightMargin = leftMargin
                bottomMargin = topMargin
            }
            val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            positionRoot = rootView
            rootView.addOnLayoutChangeListener(positionListener)
            composeView.addOnLayoutChangeListener(positionListener)
            composeView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    if (hudView === view) dismiss()
                }
            })
            ViewCompat.setOnApplyWindowInsetsListener(composeView) { _, insets ->
                composeView.post { if (hudView === composeView) restoreHudPosition(composeView, rootView, margin.toFloat()) }
                insets
            }
            rootView.addView(composeView, params)
            longPressHandler.postDelayed(freshnessTick, 1_000L)
            rootView.post {
                if (hudView !== composeView) return@post
                restoreHudPosition(composeView, rootView, margin.toFloat())
            }
        }
    }

    private fun resetSessionState() {
        sessionStats.reset()
        lastMediaAtMs = null
        lastHostAtMs = null
        lastMediaGeneration = 0L
        lastFps = Double.NaN
        lastLatency = 0.0
        lastSessionStatus = null
        streamPolicy = StreamPolicyUiState.from(null)
        currentBitrateKbps = 0
        lastBitrateKbps = 0
        targetFps = 0.0
        lastCodec = ""
        activeCodecLabel = ""
        launchPresetLabel = ""
        width = 0
        height = 0
        sparklineData.clear()
        eventTrail.clear()
        lastLowOnePercentFps = 0.0
        lastDecodeTimeMs = 0.0
        lastHostLatencyMs = null
        lastIncomingFps = 0.0
        lastRenderedFps = 0.0
        lastPacketLossPct = -1.0
        lastRttVarianceMs = -1
        lastFramesLost = -1L
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
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    if (isDragging) clampAndSaveHudPosition(touchedView)
                    isDragging = false
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
            pendingLongPress = null
            if (hudView !== view || !view.isAttachedToWindow || activity.isFinishing || activity.isDestroyed) return@Runnable
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

    /**
     * Dims the HUD while a panel covers the stream, and brings it back when it closes. Dimmed, not
     * hidden: the Command Center's own Nova HUD rows change it, and the change should still show.
     */
    fun setCovered(covered: Boolean) {
        this.covered = covered
        hudView?.animate()?.cancel()
        hudView?.animate()?.alpha(coveredAlpha())?.setDuration(150L)?.start()
    }

    private var covered = false

    private fun coveredAlpha(): Float = if (covered) COVERED_ALPHA else 1f

    fun cycleMode() {
        applyMode(currentMode.next())
    }

    /** Jump straight to a layout; the Command Center picker uses this instead of cycling blind. */
    fun setMode(mode: NovaHudMode) {
        if (mode == currentMode) {
            return
        }
        applyMode(mode)
    }

    private fun applyMode(mode: NovaHudMode) {
        val view = hudView ?: return
        currentMode = mode
        PreferenceManager.getDefaultSharedPreferences(activity)
            .edit()
            .putString("nova_polaris_hud_mode", currentMode.preferenceValue)
            .apply()
        // No layoutParams reassignment: every mode is WRAP_CONTENT, so this forced a
        // re-layout to set the width to the width it already had.
        publishState()
        view.post {
            if (hudView !== view) return@post
            positionRoot?.let { restoreHudPosition(view, it, hudMarginPx(true)) }
        }
    }

    /**
     * The one feed. The decoder emits this struct once a second whether or not any overlay
     * is up; it carries every number the HUD shows, so the HUD no longer asks the decoder
     * to also build the legacy overlay text on its own thread just to regex it back out.
     */
    fun updateFromPerfSample(sample: PerfOverlaySample) {
        activity.runOnUiThread {
            if (hudView == null) return@runOnUiThread
            val now = SystemClock.elapsedRealtime()
            // Timestamp provenance is monotonic for both decoder paths. Untimestamped
            // compatibility callers age from receipt; replayed old samples never become fresh.
            val sampledAt = sample.monotonicTimestampMs.takeIf { it > 0L } ?: now
            if (sampledAt > now || now - sampledAt > MEDIA_MAX_AGE_MS ||
                (sample.sessionGeneration > 0 && lastMediaGeneration > sample.sessionGeneration)) return@runOnUiThread
            if (sample.sessionGeneration > 0 && sample.sessionGeneration != lastMediaGeneration) {
                sparklineData.clear()
                lastMediaAtMs = null
                lastMediaGeneration = sample.sessionGeneration
            }
            if (lastMediaAtMs?.let { sampledAt < it } == true) return@runOnUiThread
            lastMediaAtMs = sampledAt
            updateFps(sample.renderedFps)
            width = sample.width
            height = sample.height
            updateLatency(sample.rttMs)
            applyCodecLabel(sample.codec)
            sessionStats.recordPacketLoss(sample.packetLossPct)
            lastDecodeTimeMs = sample.decodeTimeMs
            lastHostLatencyMs = sample.hostProcessingLatencyMs
            lastIncomingFps = sample.incomingFps
            lastRenderedFps = sample.renderedFps
            lastPacketLossPct = sample.packetLossPct
            lastRttVarianceMs = sample.rttVarianceMs
            lastFramesLost = sample.framesLost
            // The display helpers above own the HUD's rolling averages. Preserve the
            // exact cumulative decoder evidence too, so Copy diagnostics reports the
            // same raw sample that Doctor uploads instead of default zero counters.
            sessionStats.recordRawMediaEvidence(sample)
            publishState()
        }
    }

    fun setTargetBitrateKbps(bitrateKbps: Int) {
        activity.runOnUiThread {
            currentBitrateKbps = bitrateKbps
            lastBitrateKbps = bitrateKbps
            sessionStats.setLastBitrateKbps(bitrateKbps)
            streamPolicy = StreamPolicyUiState.from(lastSessionStatus, bitrateKbps, targetFps)
            if (hudView != null) publishState()
        }
    }

    fun setTargetFps(fps: Double) {
        if (!fps.isFinite() || fps <= 0.0) {
            return
        }
        targetFps = fps
        sessionStats.setTargetFps(fps)
        activity.runOnUiThread {
            if (hudView != null) publishState()
        }
    }

    fun update(fps: Double, codec: String, bitrateKbps: Int, width: Int, height: Int, latencyMs: Double) {
        activity.runOnUiThread {
            if (hudView == null) return@runOnUiThread
            lastMediaAtMs = SystemClock.elapsedRealtime()
            updateFps(fps)
            lastRenderedFps = fps
            applyCodecLabel(codec)
            this.width = width
            this.height = height
            streamPolicy = StreamPolicyUiState.from(
                lastSessionStatus,
                if (bitrateKbps > 0) bitrateKbps else lastBitrateKbps,
                targetFps
            )
            val displayBitrate = streamPolicy.effectiveBitrateKbps
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
            lastHostAtMs = if (status != null) SystemClock.elapsedRealtime() else null
            lastSessionStatus = status
            sessionStats.applySessionStatus(status)
            val resolvedTargetFps = status?.let(::resolveTargetFps) ?: 0.0
            if (resolvedTargetFps > 0.0) {
                targetFps = resolvedTargetFps
                sessionStats.setTargetFps(resolvedTargetFps)
            }
            streamPolicy = StreamPolicyUiState.from(status, lastBitrateKbps, targetFps)
            // Historical recovery receipts are observational/deprecated and
            // never become a current or next-launch HUD event.
            eventTrail.retireRecoveryProfile()
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
        lastFps = fps.takeIf { it.isFinite() && it >= 0.0 } ?: Double.NaN
        if (!lastFps.isFinite()) return
        sparklineData.add(fps.toFloat())
        // Periodic sample minimum, not a frame-time percentile.
        lastLowOnePercentFps = sparklineData.lowOnePercent()
        sessionStats.recordFps(
            fps = fps,
            lowOnePercentFps = lastLowOnePercentFps
        )

        // The HUD is observational. Low rendered FPS may be static content or
        // a source-cadence gap, so displaying telemetry must never mutate the
        // live bitrate. Evidence-gated changes use Polaris Doctor actions.
    }

    private fun updateLatency(ms: Int) {
        lastLatency = ms.coerceAtLeast(0).toDouble()
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

    /** Bind only the resolved launch preset, never a mutable preference or host prose. */
    fun setLaunchPresetLabel(label: String) {
        activity.runOnUiThread {
            launchPresetLabel = label.lineSequence().firstOrNull().orEmpty().trim().take(48)
            if (hudView != null) publishState()
        }
    }

    private fun publishState() {
        val now = SystemClock.elapsedRealtime()
        val mediaFresh = lastMediaAtMs?.let { now - it in 0..MEDIA_MAX_AGE_MS } == true
        val hostFresh = lastHostAtMs?.let { now - it in 0..HOST_MAX_AGE_MS } == true
        // StreamPolicy owns legacy fallbacks and deliberately returns zero when a
        // present current policy is invalid. Do not replace that with an old rate.
        val displayBitrate = streamPolicy.effectiveBitrateKbps
        hudState.value = NovaHudUiState.from(
            mode = currentMode,
            fps = lastFps,
            targetFps = targetFps,
            latencyMs = lastLatency.toInt(),
            codec = activeCodecLabel.ifBlank { lastCodec },
            bitrateKbps = if (hostFresh) displayBitrate else 0,
            width = width,
            height = height,
            status = lastSessionStatus,
            sparklineSamples = sparklineData.snapshot(),
            eventBreadcrumbLabel = eventTrail.latestLabel,
            lowOnePercentFps = lastLowOnePercentFps,
            decodeTimeMs = lastDecodeTimeMs,
            hostProcessingLatencyMs = lastHostLatencyMs,
            incomingFps = lastIncomingFps,
            renderedFps = lastRenderedFps,
            packetLossPct = lastPacketLossPct,
            rttVarianceMs = lastRttVarianceMs,
            framesLost = lastFramesLost,
            mediaFresh = mediaFresh,
            mediaStale = lastMediaAtMs != null && !mediaFresh,
            hostFresh = hostFresh,
            launchPresetLabel = launchPresetLabel
        )
    }

    private fun restoreHudPosition(view: View, rootView: ViewGroup, fallbackMargin: Float) {
        if (rootView.width <= 0 || rootView.height <= 0 || view.width <= 0 || view.height <= 0) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val bounds = hudPositionBounds(view, rootView)
        // Bound the Compose measurement too: clamping position alone cannot keep an
        // overlay wider than the remaining safe surface out of a right-hand cutout.
        val margins = hudSafeMargins(rootView)
        (view.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            if (params.leftMargin != margins.left || params.topMargin != margins.top ||
                params.rightMargin != margins.right || params.bottomMargin != margins.bottom) {
                params.leftMargin = margins.left
                params.topMargin = margins.top
                params.rightMargin = margins.right
                params.bottomMargin = margins.bottom
                view.layoutParams = params
            }
        }
        val xFraction = prefs.getFloat(PREF_HUD_X_FRACTION, Float.NaN)
        val yFraction = prefs.getFloat(PREF_HUD_Y_FRACTION, Float.NaN)
        val desired = if (xFraction.isFinite() && yFraction.isFinite()) {
            bounds.fromFractions(xFraction, yFraction)
        } else {
            // Migrate the old pixel coordinates once, against the actual safe surface.
            bounds.clamp(prefs.getFloat(PREF_HUD_X, bounds.left), prefs.getFloat(PREF_HUD_Y, bounds.top))
        }
        view.x = desired.first
        view.y = desired.second
        if (!xFraction.isFinite() || !yFraction.isFinite()) saveHudPosition(view.x, view.y)
    }

    private fun clampAndSaveHudPosition(view: View) {
        val rootView = positionRoot ?: return
        val clamped = clampHudPosition(view, rootView, view.x, view.y)
        view.x = clamped.first
        view.y = clamped.second
        saveHudPosition(clamped.first, clamped.second)
    }

    private fun clampHudPosition(view: View, rootView: ViewGroup, desiredX: Float, desiredY: Float): Pair<Float, Float> =
        hudPositionBounds(view, rootView).clamp(desiredX, desiredY)

    private fun hudPositionBounds(view: View, rootView: ViewGroup): NovaHudPositionBounds {
        val margins = hudSafeMargins(rootView)
        return NovaHudPositionBounds.forSurface(rootView.width, rootView.height, view.width, view.height,
            0f, 0f, margins.left, margins.top, margins.right, margins.bottom)
    }

    private fun hudSafeMargins(rootView: ViewGroup): Rect {
        val insets = ViewCompat.getRootWindowInsets(rootView)?.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        // Insets are window-relative; a content root may already sit inside those bars.
        val rootLocation = IntArray(2).also(rootView::getLocationInWindow)
        val decor = activity.window.decorView
        val leftInset = ((insets?.left ?: 0) - rootLocation[0]).coerceAtLeast(0)
        val topInset = ((insets?.top ?: 0) - rootLocation[1]).coerceAtLeast(0)
        val rightInset = ((insets?.right ?: 0) - (decor.width - rootLocation[0] - rootView.width).coerceAtLeast(0)).coerceAtLeast(0)
        val bottomInset = ((insets?.bottom ?: 0) - (decor.height - rootLocation[1] - rootView.height).coerceAtLeast(0)).coerceAtLeast(0)
        return Rect(leftInset + hudMarginPx(true).toInt(), topInset + hudMarginPx(false).toInt(),
            rightInset + hudMarginPx(true).toInt(), bottomInset + hudMarginPx(false).toInt())
    }

    /** Actual laid-out position, including the current surface size, safe insets and saved fractions. */
    val leftPx: Float
        get() = hudView?.takeIf { it.width > 0 }?.x ?: Float.NaN

    /** Null means the player dragged the HUD between the named corners. */
    val positionCorner: NovaHudCorner?
        get() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val x = prefs.getFloat(PREF_HUD_X_FRACTION, Float.NaN)
            val y = prefs.getFloat(PREF_HUD_Y_FRACTION, Float.NaN)
            return NovaHudCorner.entries.firstOrNull { it.x == x && it.y == y }
        }

    /** Controller/Command Center callers can move the HUD without touch dragging. */
    fun setPosition(corner: NovaHudCorner) {
        activity.runOnUiThread {
            PreferenceManager.getDefaultSharedPreferences(activity).edit()
                .putFloat(PREF_HUD_X_FRACTION, corner.x).putFloat(PREF_HUD_Y_FRACTION, corner.y)
                .remove(PREF_HUD_X).remove(PREF_HUD_Y).apply()
            val view = hudView ?: return@runOnUiThread
            val root = positionRoot ?: return@runOnUiThread
            restoreHudPosition(view, root, hudMarginPx(true))
        }
    }

    fun resetPosition() = setPosition(NovaHudCorner.TOP_LEFT)

    /**
     * How far the HUD keeps from the screen's edge: 12dp, or on a television its title-safe 48dp
     * at the sides and 27dp at top and bottom, where overscan would cut the pill.
     */
    private fun hudMarginPx(horizontal: Boolean): Float {
        val dp = if (com.papi.nova.utils.UiHelper.isTvDevice(activity)) {
            if (horizontal) com.papi.nova.ui.panel.NovaPanelMetrics.TvSafeHorizontal.value
            else com.papi.nova.ui.panel.NovaPanelMetrics.TvSafeVertical.value
        } else {
            HUD_SAFE_MARGIN_DP
        }
        return dp * activity.resources.displayMetrics.density
    }

    private fun saveHudPosition(x: Float, y: Float) {
        val view = hudView ?: return
        val root = positionRoot ?: return
        val fractions = hudPositionBounds(view, root).toFractions(x, y)
        PreferenceManager.getDefaultSharedPreferences(activity).edit()
            .putFloat(PREF_HUD_X_FRACTION, fractions.first)
            .putFloat(PREF_HUD_Y_FRACTION, fractions.second)
            .remove(PREF_HUD_X).remove(PREF_HUD_Y).apply()
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
            cancelLongPress()
            longPressHandler.removeCallbacks(freshnessTick)
            positionRoot?.removeOnLayoutChangeListener(positionListener)
            positionRoot = null
            view?.removeOnLayoutChangeListener(positionListener)
            view?.animate()?.cancel()
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
        private const val MEDIA_MAX_AGE_MS = 3_500L
        private const val HOST_MAX_AGE_MS = 5_000L
        private const val PREF_HUD_X_FRACTION = "nova_polaris_hud_position_x_fraction"
        private const val PREF_HUD_Y_FRACTION = "nova_polaris_hud_position_y_fraction"
        private const val DRAG_THRESHOLD = 12f
        private const val HUD_SAFE_MARGIN_DP = 12f
        private const val COVERED_ALPHA = 0.25f
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
