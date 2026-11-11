package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.diagnostics.NovaStreamRecap.End
import com.papi.nova.diagnostics.NovaStreamRecapReport
import com.papi.nova.diagnostics.NovaStreamRecapReportTest
import java.io.File
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where a person reads a time, it is this device's time and zone; UTC stays inside exported files
 * (#418). RP6, 2026-10-30: baselines, moments and the Last Stream detail read
 * "Saved 2026-10-30T16:52:40Z (UTC)", under a header that said 12:56 PM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaLocalTimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val stamp = "2026-10-08T13:00:00Z"
    private val zoneBefore = TimeZone.getDefault()

    @Before fun newYork() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    @After fun restoreZone() = TimeZone.setDefault(zoneBefore)

    private val sameDay = java.util.GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { set(2026, 9, 8, 15, 0, 0) }.timeInMillis

    @Test fun aRecordSaysTheLocalTimeAndItsZone() {
        val time = NovaLocalTime.of(context, stamp, sameDay)
        assertEquals("9:00 AM", time)
        assertEquals("9:00 AM EDT", NovaLocalTime.withZone(context, stamp, sameDay))
    }

    @Test fun anotherDayCarriesTheDateAndAStampThatDoesNotParseIsShownAsWritten() {
        val later = sameDay + 3L * 24 * 60 * 60 * 1000
        val when3DaysOn = NovaLocalTime.withZone(context, stamp, later)
        assertTrue(when3DaysOn, when3DaysOn.contains("Oct") && when3DaysOn.contains("8") && when3DaysOn.endsWith("EDT"))
        listOf("unknown", "2026-10-08T13:00:00", "2026-10-08T13:00:00Zjunk").forEach {
            assertEquals(it, NovaLocalTime.of(context, it, sameDay))
            assertEquals(it, NovaLocalTime.withZone(context, it, sameDay))
        }
    }

    @Test fun theBaselineRowSaysWhenInLocalTime() {
        val caption = NovaQuickMenuUiState.workingBaselineAction(
            context, NovaWorkingBaselineRow(hasEntry = true, savedAtUtc = stamp)).caption
        assertFalse(caption, caption.contains(stamp))
        assertFalse(caption, caption.contains("UTC"))
        assertTrue(caption, caption.contains(NovaLocalTime.withZone(context, stamp)))
    }

    @Test fun theLastStreamDetailSaysWhenInLocalTime() {
        val recap = NovaStreamRecapReportTest.recap(end = End.END_REQUESTED)
        val page = NovaStreamRecapText.page(context, recap)
        assertFalse(page, page.contains(recap.endedAtUtc))
        assertFalse(page, page.contains("UTC"))
        assertTrue(page, page.contains(NovaLocalTime.withZone(context, recap.endedAtUtc)))
    }

    @Test fun exportedFilesKeepTheirUtcStamp() {
        val recap = NovaStreamRecapReportTest.recap(end = End.END_REQUESTED)
        val report = String(NovaStreamRecapReport.render(recap, "1.5.0", "release")!!)
        assertTrue(report, report.contains(recap.endedAtUtc))
    }

    @Test fun noPersonReadsAUtcStampFromTheseStrings() {
        val strings = File("src/main/res/values/strings.xml").readLines()
        val shown = listOf(
            "nova_moment_report_saved_message", "nova_working_baseline_saved_caption", "nova_working_baseline_status_saved",
            "nova_working_baseline_confirm_replace_note", "nova_working_baseline_compare_baseline",
            "nova_working_baseline_compare_now", "nova_working_baseline_compare_now_unavailable",
            "nova_recap_ended", "nova_recap_report_saved_message",
        )
        shown.forEach { name ->
            val line = strings.single { it.contains("name=\"$name\"") }
            assertFalse(line, line.contains("UTC"))
        }
    }

    /** Read from the page itself since its builder can be reached (#470): local time and zone, never the stamp. */
    @Test fun theMomentPageIsGivenLocalTime() {
        val saved = com.papi.nova.diagnostics.NovaMomentReporter.Saved(File("nova-moment-20261008T130000Z.md"), stamp)
        val message = novaMomentSavedPage(context, saved, onShare = {}).message.text
        assertTrue(message, message.contains(NovaLocalTime.withZone(context, stamp)))
        assertFalse(message, message.contains(stamp))
        assertFalse(message, message.contains("UTC"))
    }
}
