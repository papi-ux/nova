package com.papi.nova.ui

import com.papi.nova.api.LiveTuningStatus
import com.papi.nova.ui.NovaLiveTuningLine.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaLiveTuningLinesTest {
    private var sequence = 0L
    private fun live(state: String, applied: Int, requested: Int = applied, limit: Int = 34988,
        instance: String = "host-a", generation: Long = 7, session: String = "session-a",
        revision: String = "a".repeat(64), reason: String = "healthy") =
        LiveTuningStatus(true, state, true, reason, limit, requested, applied, revision, instance, ++sequence, generation, session)

    private fun NovaLiveTuningLines.feed(atMs: Long, status: LiveTuningStatus?, codec: String = "hevc") =
        observe(status, codec, atMs)

    /** One reading a second, as the HUD gets them; returns the lines emitted. */
    private fun NovaLiveTuningLines.hold(fromMs: Long, toMs: Long, codec: String = "hevc", status: () -> LiveTuningStatus) =
        (fromMs..toMs step 1_000L).mapNotNull { observe(status(), codec, it) }

    /** Full at 0 s, then an acknowledged 29739 from 2 s, announced at 4 s. */
    private fun eased(lines: NovaLiveTuningLines): NovaLiveTuningLine? {
        lines.feed(0, live("stable", 34988))
        lines.feed(1_000, live("applying", 34988, requested = 29739))
        lines.feed(2_000, live("adjusting", 29739))
        lines.feed(3_000, live("adjusting", 29739))
        return lines.feed(4_000, live("adjusting", 29739))
    }

    @Test fun easesFromTheAcknowledgedRateNotTheRequest() {
        val lines = NovaLiveTuningLines()
        assertNull(lines.feed(0, live("stable", 34988)))
        // A pending request names no rate, however long it waits.
        assertTrue(lines.hold(1_000, 9_000) { live("applying", 34988, requested = 29739) }.isEmpty())
        assertNull(lines.feed(10_000, live("adjusting", 29739)))
        assertNull(lines.feed(11_000, live("adjusting", 29739)))
        val line = lines.feed(12_000, live("adjusting", 29739))!!
        assertEquals(Kind.EASED, line.kind)
        assertEquals(29739, line.appliedKbps)
    }

    @Test fun pendingOrMeasuringRestartsTheDwellAndKeepsTheBaseline() {
        for (held in listOf("applying", "measuring")) {
            val lines = NovaLiveTuningLines()
            lines.feed(0, live("stable", 34988))
            lines.feed(1_000, live("adjusting", 29739))
            lines.feed(2_000, live(held, 29739, requested = 25279))
            lines.feed(2_500, live("adjusting", 29739))
            // Two seconds from the first sighting, but the held reading discarded that dwell.
            assertNull(held, lines.feed(3_500, live("adjusting", 29739)))
            assertEquals(held, Kind.EASED, lines.feed(4_500, live("adjusting", 29739))?.kind)
        }
    }

    @Test fun backToFullNeedsAnEaseAndTenSecondsAtTheLimit() {
        val lines = NovaLiveTuningLines()
        assertNotNull(eased(lines))
        assertTrue(lines.hold(5_000, 39_000) { live("adjusting", 29739) }.isEmpty())
        // Rungs below the limit stay silent.
        assertNull(lines.feed(40_000, live("adjusting", 31523)))
        assertNull(lines.feed(41_000, live("adjusting", 33414)))
        assertTrue(lines.hold(42_000, 51_000) { live("stable", 34988) }.isEmpty())
        assertEquals(Kind.FULL, lines.feed(52_000, live("stable", 34988))?.kind)

        val startedLow = NovaLiveTuningLines()
        startedLow.feed(0, live("adjusting", 30000))
        assertTrue(startedLow.hold(1_000, 30_000) { live("stable", 34988) }.isEmpty())
    }

    @Test fun aChangeReversedInsideTheSpacingSaysNothing() {
        val lines = NovaLiveTuningLines()
        assertNotNull(eased(lines))
        assertTrue(lines.hold(5_000, 24_000) { live("stable", 34988) }.isEmpty())
        assertTrue(lines.hold(25_000, 60_000) { live("adjusting", 29739) }.isEmpty())
    }

    @Test fun onlyAFurtherTenPercentDropEasesAgain() {
        val lines = NovaLiveTuningLines()
        assertNotNull(eased(lines))
        assertTrue(lines.hold(5_000, 45_000) { live("adjusting", 27000) }.isEmpty())
        assertEquals(listOf(26700), lines.hold(46_000, 48_000) { live("adjusting", 26700) }.map { it.appliedKbps })
    }

    @Test fun repeatedDeliveryOfOneReadingEmitsOnce() {
        val lines = NovaLiveTuningLines()
        assertNotNull(eased(lines))
        for (at in 4_100L..60_000L step 500L) assertNull(lines.feed(at, live("adjusting", 29739)))
    }

    @Test fun aNewScopeOrASilenceStartsOverWithoutALine() {
        val changes = listOf<() -> LiveTuningStatus>(
            { live("adjusting", 20000, instance = "host-b") },
            { live("adjusting", 20000, generation = 8) },
            { live("adjusting", 20000, session = "session-b") },
            { live("adjusting", 20000, revision = "b".repeat(64)) },
            { live("adjusting", 20000, limit = 30000) },
        )
        for (change in changes) {
            val lines = NovaLiveTuningLines()
            lines.feed(0, live("stable", 34988))
            assertTrue(lines.hold(1_000, 10_000, status = change).isEmpty())
        }
        val silent = NovaLiveTuningLines()
        silent.feed(0, live("stable", 34988))
        assertTrue(silent.hold(6_000, 15_000) { live("adjusting", 20000) }.isEmpty())
        val unknown = NovaLiveTuningLines()
        unknown.feed(0, live("stable", 34988))
        unknown.feed(1_000, null)
        assertTrue(unknown.hold(2_000, 10_000) { live("adjusting", 20000) }.isEmpty())
    }

    @Test fun visibilityRunsFromEmissionSoARemountCannotReplayIt() {
        val lines = NovaLiveTuningLines()
        val line = eased(lines)!!
        assertEquals(line, lines.visibleLine(line.emittedAtMs + NovaLiveTuningLines.VISIBLE_MS - 1))
        assertNull(lines.visibleLine(line.emittedAtMs + NovaLiveTuningLines.VISIBLE_MS))
        lines.feed(line.emittedAtMs + 5_000, live("adjusting", 29739))
        assertNull(lines.visibleLine(line.emittedAtMs + 5_000))
    }

    @Test fun theHostReasonNeverChangesALine() {
        val emitted = listOf("packet_loss", "standing_queue", "holding_under_a_queue", "not_a_known_reason").map { reason ->
            val lines = NovaLiveTuningLines()
            lines.feed(0, live("stable", 34988, reason = reason))
            lines.feed(2_000, live("adjusting", 29739, reason = reason))
            lines.feed(4_000, live("adjusting", 29739, reason = reason))
        }
        assertNotNull(emitted.first())
        assertEquals(1, emitted.distinct().size)
    }

    @Test fun withoutVideoUnitsNothingIsSaidAndASwitchClearsTheEase() {
        val unknown = NovaLiveTuningLines()
        unknown.feed(0, live("stable", 34988), codec = "pyrowave")
        assertTrue(unknown.hold(1_000, 30_000, codec = "pyrowave") { live("adjusting", 29739) }.isEmpty())
        assertTrue(unknown.hold(31_000, 60_000, codec = "pyrowave") { live("stable", 34988) }.isEmpty())
        // A known ease, then full quality on unknown units: no lone Back, then or after.
        val switched = NovaLiveTuningLines()
        assertNotNull(eased(switched))
        assertTrue(switched.hold(5_000, 20_000, codec = "pyrowave") { live("stable", 34988) }.isEmpty())
        assertTrue(switched.hold(21_000, 40_000) { live("stable", 34988) }.isEmpty())
    }
}
