package com.papi.nova.ui

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

    @Test fun thePageGivesEachReadingItsCountAndScopeAndSaysWhatIsNotMeasured() {
        val page = NovaStreamRecapText.page(context, recap())
        assertTrue(page, page.contains(text(R.string.nova_recap_averages)))
        assertTrue(page, page.contains("${text(R.string.nova_recap_rendered_fps)}: 60.0 (2 · 60.0 to 60.0)"))
        assertTrue(page, page.contains(text(R.string.nova_recap_not_measured)))
        assertTrue(page, page.contains(text(R.string.nova_recap_lifetime)))
        assertTrue(page, page.contains("Requested bitrate isn't measured throughput"))
        assertTrue(page, page.contains("not the host app's session"))
        verdicts.forEach { assertFalse("$it in $page", Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(page)) }
    }

    @Test fun noSamplesSaysUnavailableAndShowsNoReadings() {
        val none = recap(samples = emptyList())
        val page = NovaStreamRecapText.page(context, none)
        assertTrue(page, page.contains(text(R.string.nova_recap_no_samples)))
        assertFalse(page, page.contains(text(R.string.nova_recap_averages)))
        assertEquals(text(R.string.nova_recap_card_summary_no_samples, "2026-10-08T14:00:00Z", text(R.string.nova_recap_short_connection_ended)),
            NovaStreamRecapText.card(context, none))
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
        val large = NovaStreamRecapText.card(context, recap(end = End.ERROR, code = 0x8000_0001.toInt()))
        assertTrue(large, large.contains(text(R.string.nova_recap_short_error, "80000001")))
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

    @Test fun durationsReadPlainly() {
        assertEquals("4 s", NovaStreamRecapText.duration(4_900L))
        assertEquals("2 min 05 s", NovaStreamRecapText.duration(125_000L))
        assertEquals("1 h 01 min", NovaStreamRecapText.duration(3_660_000L))
    }

    @Test fun theRecapPageIsAReadOnlyNotice() {
        val page = NovaStreamRecapPages.page(context, recap())
        assertTrue(page.monospace)
        assertEquals(null, page.primary)
        assertEquals(text(R.string.nova_recap_close), page.closeLabel)
    }
}
