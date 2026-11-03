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
    @Test fun receivedPayloadHasItsOwnUnitsAndNeedsCurrentValidMedia() {
        val measured = sample().copy(receivedVideoPayloadKbps = 12000.0)
        val d = details(sample = measured)
        assertEquals("12 Mbps", d.value(R.string.nova_cc_rate_received))
        assertEquals("20 Mbps", d.value(R.string.nova_cc_rate_applied))
        assertEquals("30 Mbps", d.value(R.string.nova_cc_rate_limit))
        assertEquals("0 Mbps", details(sample = measured.copy(receivedVideoPayloadKbps = 0.0))
            .value(R.string.nova_cc_rate_received))
        assertEquals("<0.001 Mbps", details(sample = measured.copy(receivedVideoPayloadKbps = 0.1))
            .value(R.string.nova_cc_rate_received))
        for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(details(sample = measured.copy(receivedVideoPayloadKbps = bad))
                .value(R.string.nova_cc_rate_received).startsWith("Unavailable"))
        }
        assertTrue(details(s = null, sample = measured).value(R.string.nova_cc_rate_received).startsWith("Unavailable"))
        assertTrue(details(sample = measured, now = 7000).value(R.string.nova_cc_rate_received).startsWith("Unavailable"))
        assertTrue(details(sample = measured.copy(monotonicTimestampMs = 1001))
            .value(R.string.nova_cc_rate_received).startsWith("Unavailable"))
    }
    @Test fun advertisedZeroAudioIsExplicitAndUnknownSplitHasNoBreakdown() {
        fun picture(split: Int?) = NovaLiveBitratePresentation(rate = NovaLiveBitrateState(
            negotiatedUnits = PolarisBitrateUnits(50000, 40000, 40000, 0, 20, split)))
        assertEquals("0 Mbps", details(picture = picture(50000)).value(R.string.nova_cc_rate_audio))
        assertFalse(details(picture = picture(null)).rates.any { it.first == context.getString(R.string.nova_cc_rate_audio) })
    }
    @Test fun staleMissingFutureAndMismatchedMediaAreUnavailable() {
        listOf(details(sample = null), details(now = 6001), details(now = 999), details(s = null),
            details(s = status().copy(appSessionId = ""))).forEach {
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
    @Test fun decoderEpochDoesNotNeedToMatchHostGeneration() {
        // MediaCodec emits a local decoder epoch; PyroWave leaves it at zero.
        // Neither counter is Polaris's independently assigned session generation.
        for (media in listOf(sample(generation = 1), sample(generation = 0).copy(codec = "PyroWave"))) {
            val overview = details(status(), sample = media).overview
            assertTrue(media.codec, overview.contains("1920 × 1080"))
            assertTrue(media.codec, overview.contains(media.codec))
        }
    }
    @Test fun mediaBindingRejectsReplacedConnectionsAndHostScopesWithoutChangingDecoderEpoch() {
        val api = Any(); val connection = Any(); val current = status()
        for (media in listOf(sample(generation = 1), sample(generation = 0).copy(codec = "PyroWave"))) {
            val bound = bindCommandCenterMedia(api, connection, current, media)
            assertSame(media, currentCommandCenterMedia(bound, api, connection, current))
            assertNull(currentCommandCenterMedia(bound, Any(), connection, current))
            assertNull(currentCommandCenterMedia(bound, api, Any(), current))
            assertNull(currentCommandCenterMedia(bound, api, connection, null))
            assertNull(currentCommandCenterMedia(bound, api, connection, current.copy(streamingActive = false)))
            assertNull(currentCommandCenterMedia(bound, api, connection, status(live().copy(hostInstance = "replacement"))))
            val replacement = status(live().copy(appSessionId = "new", sessionGeneration = 42))
                .copy(appSessionId = "new", sessionGeneration = 42)
            assertNull(currentCommandCenterMedia(bound, api, connection, replacement))
            assertNull(bindCommandCenterMedia(api, null, current, media))
            assertNull(bindCommandCenterMedia(api, connection, current.copy(authorityContractValid = false), media))
            assertEquals(if (media.codec == "PyroWave") 0L else 1L, media.sessionGeneration)
        }
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
        // BB/current4b reports healthy, not the synthetic supported reason in the shared fixtures.
        assertNull("healthy must not add a missing-reason warning",
            liveTuningReason(context, live().copy(reason = "healthy")))
        for (reason in listOf("encoder_ready", "supported", "")) {
            assertNull(reason, liveTuningReason(context, live().copy(reason = reason)))
        }
        // Real BB/current4b producer values, including lifecycle failures that are not unsupported encoders.
        val known = mapOf(
            "encoder_runtime_update_unsupported" to "This encoder cannot change bitrate during a stream",
            "encoder_not_initialized" to "The encoder has not initialized Live Tuning",
            "encoder_session_init_failed" to "The encoder could not start",
            "encoder_session_ended" to "The encoder session ended",
            "capture_session_ended" to "The capture session ended",
            "runtime_bitrate_update_failed" to "The encoder could not apply a bitrate change",
            "session_scope_unavailable" to "Live Tuning feedback is unavailable for this stream",
            "packet_loss" to "Responding to lost video frames",
            "standing_queue" to "Responding to sustained network delay",
            "finding_the_link" to "Testing the bitrate the connection can sustain",
            "holding_under_a_queue" to "Holding the bitrate after sustained network delay",
            "holding_under_the_link" to "Holding below the connection limit found by Live Tuning",
            "waiting_to_climb" to "Holding the bitrate before testing an increase",
            "testing_a_step_up" to "Testing a higher bitrate",
            "climbing" to "Raising the bitrate toward the quality limit",
            "loss_independent_of_bitrate" to "Lowering the bitrate did not help the video frame loss",
            "frame_pacing" to "Frame timing variation observed",
            "encode_load" to "Encoder load observed",
            "doctor_action" to "Doctor requested this bitrate",
            "paired_client_action" to "A paired client requested this bitrate",
            "doctor_rollback" to "Restoring the bitrate from before the Doctor change",
            "disabled" to "Automatic adjustment is disabled",
            "no_stream" to context.getString(R.string.nova_cc_live_tuning_waiting_stream),
        )
        for ((reason, expected) in known) {
            assertEquals(reason, expected, liveTuningReason(context, live().copy(reason = reason)))
        }
        assertEquals("Reason unavailable", liveTuningReason(context, live().copy(reason = "private_internal_new_reason")))
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
    @Test fun manualPauseRecordsOnlyTheEncoderConfirmedTarget() {
        val history = NovaCommandCenterChanges(); val api = Any(); val conn = Any()
        history.observe(api, conn, status(), 1000)
        history.observe(api, conn,
            status(live(2, applied = 20000, requested = 15000, state = "off").copy(enabled = false)), 2000)
        assertTrue(history.snapshot(api, conn).isEmpty())
        history.observe(api, conn,
            status(live(3, applied = 15000, state = "off").copy(enabled = false)), 3000)
        assertEquals(listOf(NovaConfirmedStreamChange(20000, 15000)), history.snapshot(api, conn))
        history.observe(api, conn,
            status(live(4, applied = 15000, state = "off").copy(enabled = false)), 4000)
        assertEquals(1, history.snapshot(api, conn).size)
        // Some off snapshots have no requested target; the positive applied field
        // still reports an encoder-confirmed transition, independently of a POST.
        history.observe(api, conn,
            status(live(5, applied = 15000, requested = 0, state = "off").copy(enabled = false)), 5000)
        history.observe(api, conn,
            status(live(6, applied = 12000, requested = 0, state = "off").copy(enabled = false)), 6000)
        assertEquals(NovaConfirmedStreamChange(15000, 12000), history.snapshot(api, conn).first())
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
