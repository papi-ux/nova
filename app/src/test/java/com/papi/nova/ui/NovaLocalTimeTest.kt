package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.diagnostics.NovaStreamRecap.End
import com.papi.nova.diagnostics.NovaStreamRecapReport
import com.papi.nova.diagnostics.NovaStreamRecapReportTest
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun theBaselineRowSaysWhenInLocalTime() {
        val caption = NovaQuickMenuUiState.workingBaselineAction(
            context, NovaWorkingBaselineRow(hasEntry = true, savedAtUtc = stamp)).caption
        assertFalse(caption, caption.contains(stamp))
        assertFalse(caption, caption.contains("UTC"))
    }

    @Test fun theLastStreamDetailSaysWhenInLocalTime() {
        val recap = NovaStreamRecapReportTest.recap(end = End.END_REQUESTED)
        val page = NovaStreamRecapText.page(context, recap)
        assertFalse(page, page.contains(recap.endedAtUtc))
        assertFalse(page, page.contains("UTC"))
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

    @Test fun theMomentPageIsNotGivenTheRawStamp() {
        val menu = File("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt").readText()
        val page = menu.substringAfter("private fun momentSavedPage(").substringBefore("private fun workingBaselineRow(")
        assertFalse(page, page.contains("R.string.nova_moment_report_saved_message, report.markedAtUtc"))
    }
}
