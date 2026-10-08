package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaStreamRecapReportTest
import com.papi.nova.diagnostics.NovaStreamRecapReporter
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recap page's Save Report and the saved confirmation's Share are separate explicit presses.
 * Closing the page or pressing B saves nothing; on the confirmation, Done is focused and B shares
 * nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h480dp-land-xhdpi")
class NovaStreamRecapReportPagesComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val state = NovaPanelState()
    private var saves = 0
    private var shares = 0

    private class Root : NovaPage {
        override val key = "root"
        override val title = "Library"
    }

    private fun host(page: NovaPage): NovaTestKeys {
        state.open(Root())
        state.push(page)
        return rule.setPanelContent {
            NovaPageStackHost(state = state, onCloseRequest = {}) {
                NovaRow(title = "Library", onClick = {}, modifier = Modifier.novaInitialFocus())
            }
        }
    }

    private fun text(id: Int) = rule.activity.getString(id)

    private var leaves = 0

    @Test fun viewingTheRecapAndLeavingItSavesNothingAndRetires() {
        val keys = host(NovaStreamRecapPages.page(rule.activity, NovaStreamRecapReportTest.recap(),
            onSaveReport = { saves++ }, onLeave = { leaves++ }))
        rule.onNodeWithText(text(R.string.nova_recap_save_report)).assertExists()
        keys.back()
        rule.waitForIdle()
        assertEquals(0, saves)
        assertEquals(1, leaves)
        assertEquals(1, state.depth)
    }

    @Test fun saveReportIsItsOwnPressAndDoesNotRetireItself() {
        host(NovaStreamRecapPages.page(rule.activity, NovaStreamRecapReportTest.recap(),
            onSaveReport = { saves++ }, onLeave = { leaves++ }))
        rule.onNodeWithText(text(R.string.nova_recap_save_report)).performClick()
        rule.waitForIdle()
        assertEquals(1, saves)
        assertEquals(0, leaves)
        assertEquals(0, shares)
    }

    /** IO work that waits for the test, then lands on the main thread. */
    private class HeldRuntime : NovaCommandCenterRuntime {
        val io = ArrayList<suspend () -> Unit>()
        override fun launchIo(name: String, block: suspend () -> Unit) { io += block }
        override suspend fun onMain(block: () -> Unit) = block()
        override fun postDelayed(delayMs: Long, block: () -> Unit) = Unit
        fun runIo() = kotlinx.coroutines.runBlocking { val pending = io.toList(); io.clear(); pending.forEach { it() } }
    }

    @Test fun aSaveLeftBehindByClosingTheRecapNeverReopensItsResult() {
        val runtime = HeldRuntime()
        val results = ArrayList<NovaStreamRecapReporter.Saved>()
        val reporter = NovaStreamRecapReporter(
            runtime = runtime,
            render = { com.papi.nova.diagnostics.NovaStreamRecapReport.render(it, "1.4.15", "release") },
            write = { _, _ -> File(rule.activity.cacheDir, "unused.md") },
            saved = { results += it },
            offer = { com.papi.nova.diagnostics.NovaMomentShare.Result.Offered(it) },
            feedback = {},
            canShare = { true },
        )
        val recap = NovaStreamRecapReportTest.recap()
        // Save Report from the page, then the recap page is viewed again and closed with B
        // before the save's IO has finished.
        reporter.report(recap)
        val keys = host(NovaStreamRecapPages.page(rule.activity, recap,
            onSaveReport = { reporter.report(recap) }, onLeave = { reporter.retire() }))
        keys.back()
        rule.waitForIdle()
        rule.runOnUiThread { runtime.runIo() }
        rule.waitForIdle()
        assertTrue("no result reopens after the page was left", results.isEmpty())
        // Its own transition still works: a save left alone confirms.
        reporter.report(recap)
        rule.runOnUiThread { runtime.runIo() }
        assertEquals(1, results.size)
    }

    @Test fun theSavedConfirmationFocusesDoneAndBSharesNothing() {
        val saved = NovaStreamRecapReporter.Saved(File("nova-stream-recap-x.md"), "2026-10-08T14:00:00Z")
        val keys = host(NovaStreamRecapPages.saved(rule.activity, saved) { shares++ })
        rule.onNodeWithText(text(R.string.nova_recap_report_done)).assertIsFocused()
        keys.back()
        rule.waitForIdle()
        assertEquals(0, shares)
    }

    @Test fun shareIsTheConfirmationsExplicitChoice() {
        val saved = NovaStreamRecapReporter.Saved(File("nova-stream-recap-x.md"), "2026-10-08T14:00:00Z")
        host(NovaStreamRecapPages.saved(rule.activity, saved) { shares++ })
        rule.onNodeWithText(text(R.string.nova_recap_report_share)).performClick()
        rule.waitForIdle()
        assertEquals(1, shares)
    }

    @Test fun problemsAreSaidInPlaceAndAnOpenedChooserNeedsNoWord() {
        val context = rule.activity
        assertNull(NovaStreamRecapPages.problem(context, NovaStreamRecapReporter.Outcome.OFFERED))
        val storage = NovaStreamRecapPages.problem(context, NovaStreamRecapReporter.Outcome.STORAGE_FAILED)!!
        assertEquals(text(R.string.nova_recap_report_storage_failed), storage.message)
        assertNull(storage.primary)
        val withoutSave = NovaStreamRecapPages.page(context, NovaStreamRecapReportTest.recap())
        assertNull("the page offers Save Report only when given one", withoutSave.primary)
        assertTrue(text(R.string.nova_recap_report_saved_message).contains("Nothing has been sent"))
    }
}
