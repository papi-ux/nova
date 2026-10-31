package com.papi.nova.ui

import android.text.format.DateUtils
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaStreamRecap
import com.papi.nova.diagnostics.NovaStreamRecap.End
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.launch
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What the card and the page say: descriptive, scoped, and never a verdict or a host claim. */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaStreamRecapTextTest {
    private val context = RuntimeEnvironment.getApplication()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun recap(
        end: End = End.CONNECTION_ENDED,
        code: Int? = null,
        reconnect: Boolean = false,
        samples: List<Long> = listOf(2_000L, 3_000L),
        codecs: List<String> = listOf("c2.qti.hevc.decoder"),
    ): NovaStreamRecap.Recap {
        val acc = NovaStreamRecap.Accumulator(1L)
        acc.launch(launch(reconnect = reconnect))
        acc.connected(1_000L)
        samples.forEachIndexed { index, at ->
            acc.accept(sample(at, expected = 60L * (index + 1), codec = codecs[index % codecs.size]), at)
        }
        return acc.finish(end, code, 4_000L, "2026-10-08T14:00:00Z")!!
    }

    private val verdicts = listOf("healthy", "good", "bad", "poor", "excellent", "regression", "score", "PC", "server", "computer")

    @Test fun aCompressedLineDoesNotLoseTheRawZeroInItsSpokenRange() {
        val acc = NovaStreamRecap.Accumulator(1L)
        acc.launch(launch())
        acc.connected(1_000L)
        repeat(120) { i ->
            val at = 2_000L + i
            acc.accept(sample(at, rendered = if (i == 0) 0.0 else 60.0), at)
        }
        val recap = acc.finish(End.CONNECTION_ENDED, null, 4_000L, "2026-10-08T14:00:00Z")!!
        val trail = recap.fpsTrail!!
        assertEquals(30.0, trail.min, 0.0)
        val words = NovaStreamRecapText.fpsTrail(context, recap, trail)
        assertEquals(text(R.string.nova_recap_fps_trail, text(R.string.nova_recap_rendered_fps), "0.0", "60.0"), words)
    }

    @Test fun aReceivedTrailUsesOnlyReceivedRawExtrema() {
        val recap = recap().copy(renderedFps = NovaStreamRecap.Stat(10, 60.0, 50.0, 70.0),
            receivedFps = NovaStreamRecap.Stat(10, 40.0, 0.0, 80.0))
        val trail = NovaStreamRecap.FpsTrail(NovaStreamRecap.FpsTrail.Kind.RECEIVED, listOf(35.0, 45.0))
        assertEquals(text(R.string.nova_recap_fps_trail, text(R.string.nova_recap_received_fps), "0.0", "80.0"),
            NovaStreamRecapText.fpsTrail(context, recap, trail))
    }

    @Test fun missingOrInvalidMatchingRawExtremaNameOnlyTheLineAverages() {
        val trail = NovaStreamRecap.FpsTrail(NovaStreamRecap.FpsTrail.Kind.RENDERED, listOf(30.0, 60.0))
        val invalid = listOf(null, NovaStreamRecap.Stat(0, 60.0, 0.0, 60.0),
            NovaStreamRecap.Stat(5, 60.0, Double.NaN, 60.0), NovaStreamRecap.Stat(5, 60.0, 80.0, 60.0))
        for (raw in invalid) {
            val recap = recap().copy(renderedFps = raw, receivedFps = NovaStreamRecap.Stat(5, 90.0, 80.0, 100.0))
            assertEquals(text(R.string.nova_recap_fps_trail_averages, text(R.string.nova_recap_rendered_fps), "30.0", "60.0"),
                NovaStreamRecapText.fpsTrail(context, recap, trail))
        }
    }

    @Test fun noValidRawOrLineRangeStaysUnavailable() {
        val recap = recap().copy(renderedFps = null)
        val trail = NovaStreamRecap.FpsTrail(NovaStreamRecap.FpsTrail.Kind.RENDERED, listOf(Double.NaN, 60.0))
        assertEquals(text(R.string.nova_recap_stat_unavailable, text(R.string.nova_recap_rendered_fps)),
            NovaStreamRecapText.fpsTrail(context, recap, trail))
    }

    @Test fun thePageGivesEachReadingItsCountAndScopeAndSaysWhatIsNotMeasured() {
        val page = NovaStreamRecapText.page(context, recap())
        assertTrue(page, page.contains(text(R.string.nova_recap_averages)))
        assertTrue(page, page.contains("${text(R.string.nova_recap_rendered_fps)}: 60.0 (2 · 60.0 to 60.0)"))
        assertTrue(page, page.contains(text(R.string.nova_recap_not_measured)))
        assertTrue(page, page.contains(text(R.string.nova_recap_lifetime)))
        assertTrue(page, page.contains("Viewing it saves or sends nothing"))
        assertTrue(page, page.contains("Choose Save Report for a local copy"))
        assertFalse(page, page.contains("Export isn't available yet"))
        assertTrue(page, page.contains("Processing latency reported by the host"))
        assertTrue(page, page.contains("Requested bitrate isn't measured throughput"))
        assertTrue(page, page.contains("not the host app's session"))
        verdicts.forEach { assertFalse("$it in $page", Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(page)) }
    }

    @Test fun noSamplesSaysUnavailableAndShowsNoReadings() {
        val none = recap(samples = emptyList())
        val page = NovaStreamRecapText.page(context, none)
        assertTrue(page, page.contains(text(R.string.nova_recap_no_samples)))
        assertFalse(page, page.contains(text(R.string.nova_recap_averages)))
        // The host bar says what ended and when; whether there were samples is the page's to say.
        val card = NovaStreamRecapText.card(context, none, ended)
        assertEquals(text(R.string.nova_recap_card_summary, "Connection ended", NovaStreamRecapText.endedAt(context, stamp, ended)), card)
        assertFalse(card, card.contains("sample"))
    }

    @Test fun aNormalEndDoesNotClaimTheHostAppQuit() {
        val page = NovaStreamRecapText.page(context, recap(end = End.CONNECTION_ENDED))
        assertTrue(page, page.contains(text(R.string.nova_recap_end_connection_ended)))
        assertTrue(page, page.contains("doesn't show whether the host app quit"))
        val requested = NovaStreamRecapText.page(context, recap(end = End.END_REQUESTED))
        assertTrue(requested, requested.contains("isn't known here"))
    }

    @Test fun anErrorEndShowsItsCodeTheWayTheErrorDialogDoes() {
        assertTrue(NovaStreamRecapText.page(context, recap(end = End.ERROR, code = 22)).contains(text(R.string.nova_recap_end_error, "22")))
        val large = NovaStreamRecapText.card(context, recap(end = End.ERROR, code = 0x8000_0001.toInt()), ended)
        assertTrue(large, large.startsWith("Ended with error 80000001 · "))
    }

    @Test fun aRequestedReconnectIsNotPresentedAsRecovery() {
        val page = NovaStreamRecapText.page(context, recap(reconnect = true))
        assertTrue(page, page.contains(text(R.string.nova_recap_reconnect_launch)))
        assertFalse(NovaStreamRecapText.page(context, recap()).contains(text(R.string.nova_recap_reconnect_launch)))
    }

    @Test fun mixedCodecsAreSaidSoTheReadingsArentPinnedToTheLastDecoder() {
        val page = NovaStreamRecapText.page(context, recap(codecs = listOf("c2.qti.hevc.decoder", "c2.qti.av1.decoder")))
        assertTrue(page, page.contains(text(R.string.nova_recap_codecs_mixed, "hevc, av1")))
    }

    @Test fun aLastSampleOfUnknownAgeSaysSo() {
        val acc = NovaStreamRecap.Accumulator(1L)
        acc.launch(launch())
        acc.connected(1_000L)
        acc.accept(sample(5_000L), 5_000L)
        val page = NovaStreamRecapText.page(context, acc.finish(End.CONNECTION_ENDED, null, 4_000L, "2026-10-08T14:00:00Z")!!)
        assertTrue(page, page.contains("how long before the end isn't known"))
        assertFalse(page, page.contains("0 s before the end"))
    }

    @Test fun durationsReadPlainly() {
        assertEquals("4 s", NovaStreamRecapText.duration(4_900L))
        assertEquals("2 min 05 s", NovaStreamRecapText.duration(125_000L))
        assertEquals("1 h 01 min", NovaStreamRecapText.duration(3_660_000L))
    }

    /** The fixtures' stamp, and the moment it names. */
    private val stamp = "2026-10-08T14:00:00Z"
    private val ended = 1_791_468_000_000L

    // papi's screenshot of 2026-10-23 23:34 showed "Ended 2026-10-23T22:56:05Z (UTC) · video
    // surface lost · 53 client samples": the stamp as the recap stores it, not a time to read.
    @Test fun theCardSaysWhatEndedThenWhenAsThisDeviceTellsTheTime() {
        val card = NovaStreamRecapText.card(context, recap(end = End.SURFACE_LOST), ended)
        val time = DateUtils.formatDateTime(context, ended, DateUtils.FORMAT_SHOW_TIME)
        assertEquals("Video surface lost · $time", card)
        listOf(stamp, "UTC", "2026", "sample").forEach { assertFalse("$it in $card", card.contains(it)) }
        verdicts.forEach { assertFalse("$it in $card", Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(card)) }
    }

    @Test fun anotherDaysRecapCarriesItsDate() {
        val later = ended + 3 * DateUtils.DAY_IN_MILLIS
        val when3DaysOn = NovaStreamRecapText.endedAt(context, stamp, later)
        assertEquals(
            DateUtils.formatDateTime(context, ended,
                DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH),
            when3DaysOn,
        )
        assertTrue(when3DaysOn, when3DaysOn.contains("Oct"))
        assertFalse(when3DaysOn, when3DaysOn.contains(stamp))
    }

    @Test fun aStampThatDoesNotParseIsShownAsWrittenNeverGuessed() {
        for (raw in listOf("", "unknown", "2026-10-08 14:00:00", "2026-10-08T14:00:00Zjunk", "2026-13-40T25:61:00Z")) {
            assertEquals(raw, NovaStreamRecapText.endedAt(context, raw, ended))
        }
    }

    @Test fun theStampIsReadAsTheRecapWritesIt() {
        // The moment the fixtures' stamp names, in UTC, whatever this machine's zone.
        assertEquals(
            DateUtils.formatDateTime(context, ended, DateUtils.FORMAT_SHOW_TIME),
            NovaStreamRecapText.endedAt(context, stamp, ended),
        )
    }

    @Test fun theRecapPageIsAReadOnlyNotice() {
        val page = NovaStreamRecapPages.page(context, recap())
        assertTrue(page.monospace)
        assertEquals(null, page.primary)
        assertEquals(text(R.string.nova_recap_close), page.closeLabel)
    }
}
