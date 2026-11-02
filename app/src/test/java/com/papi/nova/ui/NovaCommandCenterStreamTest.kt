package com.papi.nova.ui

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.papi.nova.R
import com.papi.nova.api.LiveTuningStatus
import com.papi.nova.api.PolarisBitrateUnits
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.binding.video.PerfOverlaySample
import com.papi.nova.manager.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCommandCenterStreamTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun live(sequence: Long = 1, applied: Int = 20000, requested: Int = applied, state: String = "stable") =
        LiveTuningStatus(true, state, true, "supported", 30000, requested, applied, "a".repeat(64), "host", sequence, 41, "session")
    private fun status(live: LiveTuningStatus? = live()) = PolarisSessionStatus("streaming", streamingActive = true,
        appSessionId = "session", sessionGeneration = 41, liveTuning = live, liveTuningPresent = true)
    private fun sample(generation: Long = 41, at: Long = 1000) = PerfOverlaySample(60.0, 60.0, 60.0, 1920, 1080,
        "HEVC", 2, 1, 3.0, 0.0, monotonicTimestampMs = at, sessionGeneration = generation)
    private fun details(s: PolarisSessionStatus? = status(), picture: NovaLiveBitratePresentation = NovaLiveBitratePresentation(),
        sample: PerfOverlaySample? = sample(), now: Long = 1000) =
        commandCenterStream(context, s, picture, sample, now, true, 50000, emptyList())
    private fun NovaCommandCenterStream.value(id: Int) = rates.single { it.first == context.getString(id) }.second

    @Test fun budgetsVideoRequestsApplicationsAndMeasurementAreDistinct() {
        val units = PolarisBitrateUnits(50000, 35000, 18000, 256, 20, 42000)
        val picture = NovaLiveBitratePresentation(rate = NovaLiveBitrateState(requestedKbps = 21856,
            units = NovaBitrateUnits.REQUEST, negotiatedUnits = units))
        val d = details(status(live(applied = 15000, requested = 18000, state = "applying")), picture)
        assertEquals("50 Mbps", d.value(R.string.nova_cc_rate_original))
        assertEquals("42 Mbps", d.value(R.string.nova_cc_rate_negotiated))
        assertEquals("21.856 Mbps", d.value(R.string.nova_cc_rate_selected))
        assertEquals("30 Mbps", d.value(R.string.nova_cc_rate_limit))
        assertEquals("18 Mbps", d.value(R.string.nova_cc_rate_requested))
        assertEquals("15 Mbps", d.value(R.string.nova_cc_rate_applied))
        assertTrue(d.value(R.string.nova_cc_rate_received).startsWith("Unavailable"))
        assertEquals("20%", d.value(R.string.nova_cc_rate_repair))
    }
    @Test fun videoUnitsNeverMasqueradeAsARequestBudget() {
        val picture = NovaLiveBitratePresentation(rate = NovaLiveBitrateState(requestedKbps = 20000, units = NovaBitrateUnits.VIDEO))
        assertEquals("Unavailable", details(picture = picture).value(R.string.nova_cc_rate_selected))
        assertFalse(details(picture = picture).rates.any { it.first == context.getString(R.string.nova_cc_rate_audio) })
    }
    @Test fun zeroAppliedAndMissingReceiveRemainUnavailable() {
        val d = details(status(live(applied = 0, requested = 18000, state = "applying")))
        assertEquals("Unavailable", d.value(R.string.nova_cc_rate_applied))
        assertFalse(d.value(R.string.nova_cc_rate_received).contains("0 Mbps"))
    }
    @Test fun advertisedZeroAudioIsExplicitAndUnknownSplitHasNoBreakdown() {
        fun picture(split: Int?) = NovaLiveBitratePresentation(rate = NovaLiveBitrateState(
            negotiatedUnits = PolarisBitrateUnits(50000, 40000, 40000, 0, 20, split)))
        assertEquals("0 Mbps", details(picture = picture(50000)).value(R.string.nova_cc_rate_audio))
        assertFalse(details(picture = picture(null)).rates.any { it.first == context.getString(R.string.nova_cc_rate_audio) })
    }
    @Test fun staleMissingFutureAndMismatchedMediaAreUnavailable() {
        listOf(details(sample = null), details(now = 6001), details(now = 999), details(s = null),
            details(sample = sample(generation = 42)), details(s = status().copy(appSessionId = ""))).forEach {
            assertEquals(context.getString(R.string.nova_cc_media_unavailable), it.overview)
        }
    }
    @Test fun hdrUsesEffectiveHostFeedbackAndNeverLaunchIntent() {
        val hdr = status().copy(health = PolarisSessionStatus.HealthStatus(hdrEffectiveMode = "hdr10"))
        assertTrue(details(hdr).overview.contains("HDR10"))
        val downgraded = hdr.copy(health = hdr.health.copy(hdrEffectiveMode = "sdr_8bit", hdrDowngradeReason = "headless_hdr_unavailable"))
        assertTrue(details(downgraded).overview.contains("SDR"))
        assertFalse(details(downgraded).overview.contains("HDR10"))
        assertTrue(details(hdr.copy(health = hdr.health.copy(hdrDowngradeReason = "headless_hdr_unavailable"))).overview.contains("HDR status unavailable"))
        assertTrue(details().overview.contains("HDR status unavailable"))
    }
    @Test fun missingHostWithdrawsCurrentRatesEvenIfTheOldPictureStillExists() {
        val d = details(s = null, picture = NovaLiveBitratePresentation(rate = NovaLiveBitrateState(
            requestedKbps = 21856, units = NovaBitrateUnits.REQUEST,
            negotiatedUnits = PolarisBitrateUnits(50000, 35000, 18000, 256, 20, 42000))))
        assertEquals("Unavailable", d.value(R.string.nova_cc_rate_selected))
        assertEquals("Unavailable", d.value(R.string.nova_cc_rate_requested))
        assertEquals("Unavailable", d.value(R.string.nova_cc_rate_applied))
    }
    @Test fun reasonsMapOnlyKnownContracts() {
        assertEquals(context.getString(R.string.nova_cc_reason_encoder), liveTuningReason(context, live().copy(reason = "encoder_runtime_update_unsupported")))
        assertEquals("Reason unavailable", liveTuningReason(context, live().copy(reason = "private_internal_new_reason")))
        assertNull(liveTuningReason(context, live()))
    }
    @Test fun manualPauseUsesTheCurrentAdvertisedVideoRequestWithoutClaimingItApplied() {
        val picture = NovaLiveBitratePresentation(rate = NovaLiveBitrateState(requestedKbps = 21856,
            units = NovaBitrateUnits.REQUEST, negotiatedUnits = PolarisBitrateUnits(50000, 35000, 18000, 256, 20, 42000)))
        val d = details(status(live(applied = 15000, requested = 0, state = "off").copy(enabled = false)), picture)
        assertEquals("18 Mbps", d.value(R.string.nova_cc_rate_requested))
        assertEquals("15 Mbps", d.value(R.string.nova_cc_rate_applied))
    }

    @Test fun initialAndUnchangedReadingsAreNotChangesAndRequestsWaitForApplication() {
        val history = NovaCommandCenterChanges(); val api = Any(); val conn = Any()
        history.observe(api, conn, status(), 1000)
        history.observe(api, conn, status(live(2)), 2000)
        history.observe(api, conn, status(live(3, applied = 20000, requested = 15000, state = "applying")), 3000)
        assertTrue(history.snapshot(api, conn).isEmpty())
        history.observe(api, conn, status(live(4, applied = 15000)), 4000)
        assertEquals(listOf(NovaConfirmedStreamChange(20000, 15000)), history.snapshot(api, conn))
        history.observe(api, conn, status(live(4, applied = 30000)), 4001)
        assertEquals(1, history.snapshot(api, conn).size)
    }
    @Test fun failedFeedbackAndLongGapsCannotInventAnAdjustment() {
        val history = NovaCommandCenterChanges(); val api = Any(); val conn = Any()
        history.observe(api, conn, status(), 1000)
        history.observe(api, conn, null, 2000)
        history.observe(api, conn, status(live(2, 15000)), 3000)
        assertTrue(history.snapshot(api, conn).isEmpty())
        history.observe(api, conn, status(live(3, 10000)), 9000)
        assertTrue(history.snapshot(api, conn).isEmpty())
    }
    @Test fun hostSessionAndConnectionReplacementClearHistory() {
        listOf("host", "session", "connection", "api").forEach { replacement ->
            val history = NovaCommandCenterChanges(); val api = Any(); val conn = Any()
            history.observe(api, conn, status(), 1000)
            history.observe(api, conn, status(live(2, 15000)), 2000)
            assertEquals(1, history.snapshot(api, conn).size)
            val nextApi = if (replacement == "api") Any() else api
            val nextConn = if (replacement == "connection") Any() else conn
            val next = when (replacement) {
                "host" -> status(live(3).copy(hostInstance = "replacement"))
                "session" -> status(live(3).copy(appSessionId = "replacement", sessionGeneration = 42)).copy(appSessionId = "replacement", sessionGeneration = 42)
                else -> status(live(3))
            }
            history.observe(nextApi, nextConn, next, 3000)
            assertTrue(replacement, history.snapshot(nextApi, nextConn).isEmpty())
        }
    }
    @Test fun historyIsBoundedAndUnmatchedObservationsHaveNoAuthority() {
        val history = NovaCommandCenterChanges(); val api = Any(); val conn = Any()
        for (i in 1L..10L) history.observe(api, conn, status(live(i, 10000 + i.toInt() * 1000)), i * 1000)
        assertEquals(4, history.snapshot(api, conn).size)
        history.observe(api, conn, status(live(11, 30000).copy(appSessionId = "other")), 11000)
        assertEquals(4, history.snapshot(api, conn).size)
        assertTrue(history.snapshot(api, Any()).isEmpty())
    }
}
