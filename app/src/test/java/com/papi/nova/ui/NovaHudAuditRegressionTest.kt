package com.papi.nova.ui

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.MutableState
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.preference.PreferenceManager
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.binding.video.PerfOverlaySample
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Audit fixtures reproduce the RP6's 121 expected / 115 received / 81 drawn window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHudAuditRegressionTest {
    private val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
    private val activity = controller.get()
    private val root = activity.findViewById<ViewGroup>(android.R.id.content).apply {
        setViewTreeLifecycleOwner(activity)
        setViewTreeViewModelStoreOwner(activity)
        setViewTreeSavedStateRegistryOwner(activity)
    }
    private var requests = 0
    private val hud = NovaStreamHud(activity) { requests++ }

    @After fun close() { hud.dismiss(); controller.pause().stop().destroy() }

    @Suppress("UNCHECKED_CAST")
    private fun state(): NovaHudUiState = (NovaStreamHud::class.java.getDeclaredField("hudState")
        .apply { isAccessible = true }.get(hud) as MutableState<NovaHudUiState>).value
    private fun view(): View = NovaStreamHud::class.java.getDeclaredField("hudView")
        .apply { isAccessible = true }.get(hud) as View
    private fun sample(rendered: Double = 81.0, timestamp: Long = SystemClock.elapsedRealtime()) = PerfOverlaySample(
        fps = 121.0, incomingFps = 115.0, renderedFps = rendered, width = 3840, height = 2160,
        codec = "HEVC", rttMs = 4, rttVarianceMs = 1, decodeTimeMs = 7.2, packetLossPct = 1.6,
        monotonicTimestampMs = timestamp, framesExpected = 1000, framesReceived = 984,
        framesRendered = 700, framesLost = 16, sessionGeneration = 1,
    )
    private fun start() { hud.show(); hud.setTargetFps(120.0); hud.updateFromPerfSample(sample()) }
    private fun ui(status: PolarisSessionStatus? = null, fps: Double = 120.0,
                   incoming: Double = 120.0, loss: Double = 0.0, decode: Double = 7.2) = NovaHudUiState.from(
        NovaHudMode.DEBUG, fps, 120.0, 4, "HEVC", 193851, 3840, 2160, status,
        listOf(119f, 120f, 120f), decodeTimeMs = decode, incomingFps = incoming,
        renderedFps = fps, packetLossPct = loss,
    )

    @Test fun headlineAndSummaryUseRenderedFrames() {
        start()
        assertEquals("81", state().fpsLabel)
        assertEquals(81.0, hud.getSessionSummary()["avg_fps"] as Double, 0.001)
    }
    @Test fun zeroRenderedFramesReplaceThePreviousReading() {
        start(); hud.updateFromPerfSample(sample(rendered = 0.0))
        assertEquals("0", state().fpsLabel)
        assertEquals("0", state().renderedFpsLabel)
        assertEquals(40.5, hud.getSessionSummary()["avg_fps"] as Double, 0.001)
    }
    @Test fun silenceExpiresWithoutANewCallback() {
        start(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals("--", state().fpsLabel)
        assertEquals("--ms", state().latencyLabel)
        assertEquals("--", state().decodeTimeLabel)
        assertTrue(state().healthReasonLabel.contains("stale", ignoreCase = true))
        assertTrue(state().layerHealth.all { it.tone == NovaHudTone.MUTED })
    }
    @Test fun delayedSamplesCannotResurrectAStalledStream() {
        val old = SystemClock.elapsedRealtime(); start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        hud.updateFromPerfSample(sample(timestamp = old))
        assertEquals("--", state().fpsLabel)
    }
    @Test fun unavailableRoundTripClearsOldLatency() {
        start(); hud.updateFromPerfSample(sample().copy(rttMs = 0, rttVarianceMs = 0))
        assertEquals("--ms", state().latencyLabel)
        assertEquals("--", state().jitterLabel)
    }
    @Test fun reopeningStartsWithNoRetainedReadings() {
        start(); hud.dismiss(); hud.show()
        assertEquals("--", state().fpsLabel)
        assertEquals("--ms", state().latencyLabel)
    }
    @Test fun unknownReadingsNeverPaintHealthyLayers() {
        assertTrue(NovaHudUiState.empty().layerHealth.all { it.tone == NovaHudTone.MUTED })
    }
    @Test fun liveLocalReadingsDoNotSayWaitingForHost() {
        assertFalse(ui().healthReasonLabel.contains("Waiting"))
        assertEquals(NovaHudTone.MUTED, ui().layerHealth[0].tone)
    }
    @Test fun localFrameLossReachesDeliveryHealth() {
        val state = ui(loss = 1.6)
        assertEquals(NovaHudTone.DANGER, state.layerHealth[1].tone)
        assertTrue(state.healthReasonLabel.contains("frames", ignoreCase = true))
    }
    @Test fun receiveRenderGapReachesClientHealth() {
        val state = ui(fps = 81.0, incoming = 115.0)
        assertEquals(NovaHudTone.WARNING, state.layerHealth[2].tone)
        assertTrue(state.healthReasonLabel.contains("Render", ignoreCase = true))
    }
    @Test fun lowCadenceIsNamedWithoutInventingACause() {
        val state = ui(fps = 22.0, incoming = 22.0)
        assertEquals("Below target", state.healthReasonLabel)
        assertEquals(NovaHudTone.INFO, state.fpsTone)
    }
    @Test fun decodingWithinAWholeFrameBudgetIsHealthy() {
        assertEquals(NovaHudTone.STABLE, NovaHudUiState.toneForDecode(7.2, 120.0))
        assertEquals(NovaHudTone.MUTED, NovaHudUiState.toneForDecode(7.2, 0.0))
    }
    @Test fun hostCpuResidencyDoesNotBlameTheClient() {
        val status = PolarisSessionStatus("streaming", streamingActive = true,
            encoder = PolarisSessionStatus.EncoderStatus(targetResidency = "cpu"))
        assertEquals(NovaHudTone.STABLE, ui(status, decode = 2.0).layerHealth[2].tone)
    }
    @Test fun gpuCaptureCannotEraseAHostWarning() {
        val status = PolarisSessionStatus("streaming", streamingActive = true,
            capture = PolarisSessionStatus.CaptureStatus(transport = "dmabuf", residency = "gpu"),
            encoder = PolarisSessionStatus.EncoderStatus(targetResidency = "gpu"),
            health = PolarisSessionStatus.HealthStatus(primaryIssue = "host_render_limited"))
        assertEquals(NovaHudTone.WARNING, ui(status).layerHealth[0].tone)
    }
    @Test fun defaultAutoProfileIsNotEvidenceOfALaunchPreset() {
        assertFalse(ui(PolarisSessionStatus("streaming")).streamTruthLabel.contains("Auto profile"))
    }
    @Test fun tuningRatesRoundTheSameWayAsTheBitrateTile() {
        val status = PolarisSessionStatus("streaming", liveTuning = com.papi.nova.api.LiveTuningStatus(
            enabled = true, supported = true, state = "adjusting", appliedBitrateKbps = 193851, qualityLimitKbps = 268988,
            reason = "", requestedBitrateKbps = 193851, configurationRevision = "a".repeat(64),
            hostInstance = "test", sequence = 1, sessionGeneration = 1, appSessionId = "test"))
        assertEquals("Auto: 194 / 269M", ui(status).autopilotHudLabel)
    }
    @Test fun diagnosticsNameFrameLossInsteadOfPacketLoss() {
        assertTrue(NovaHudDiagnosticReport.format(mapOf("packet_loss_pct" to 1.6)).contains("frames missing"))
    }
    @Test fun sampleMinimumIsNotExportedAsFrameTimePercentile() {
        val stats = NovaHudSessionStats()
        stats.recordFps(60.0, nowMs = 1000, lowOnePercentFps = 55.0)
        assertFalse(stats.summary().containsKey("low_1_percent_fps"))
        assertEquals(55.0, stats.summary()["window_min_sampled_fps"])
    }
    @Test fun dismissedLongPressCannotReopenTheCommandCenter() {
        hud.show()
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        view().dispatchTouchEvent(down); down.recycle(); hud.dismiss()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(0, requests)
    }
    @Test fun detachedLongPressCannotReopenTheCommandCenter() {
        hud.show()
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        val view = view(); view.dispatchTouchEvent(down); down.recycle(); root.removeView(view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(0, requests)
    }
    @Test fun savedPositionScalesWithTheAvailableSurface() {
        hud.show(); val view = view()
        root.layout(0, 0, 1000, 600); view.layout(0, 0, 100, 100)
        view.x = 888f; view.y = 488f
        NovaStreamHud::class.java.getDeclaredMethod("clampAndSaveHudPosition", View::class.java)
            .apply { isAccessible = true }.invoke(hud, view)
        root.layout(0, 0, 1600, 900)
        NovaStreamHud::class.java.getDeclaredMethod("restoreHudPosition", View::class.java, ViewGroup::class.java, Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(hud, view, root, 12f)
        assertTrue("bottom-right stays bottom-right on a larger surface", view.x > 1400f && view.y > 700f)
        assertTrue(PreferenceManager.getDefaultSharedPreferences(activity).contains("nova_polaris_hud_position_x_fraction"))
    }
}
