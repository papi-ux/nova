package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaMomentShare
import com.papi.nova.diagnostics.NovaStreamRecap
import com.papi.nova.diagnostics.NovaStreamRecapExport
import com.papi.nova.diagnostics.NovaStreamRecapReport
import com.papi.nova.diagnostics.NovaStreamRecapReportTest
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real Save Report sequence in a real panel: Save Report on the recap page, its Saving page,
 * the IO work and the main-thread answer each held, and whatever the player does in between.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h480dp-land-xhdpi")
class NovaStreamRecapReportPanelComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** IO work and its main-thread answer, each waiting until the test runs it. */
    private class HeldRuntime : NovaCommandCenterRuntime {
        val io = ArrayList<suspend () -> Unit>()
        val main = ArrayList<() -> Unit>()
        override fun launchIo(name: String, block: suspend () -> Unit) {
            io += block
        }
        override suspend fun onMain(block: () -> Unit) {
            main += block
        }
        override fun postDelayed(delayMs: Long, block: () -> Unit) = Unit
        fun runIo() = runBlocking { val pending = io.toList(); io.clear(); pending.forEach { it() } }
        fun runMain() { val pending = main.toList(); main.clear(); pending.forEach { it() } }
    }

    private class Plain(override val key: String) : NovaPage {
        override val title = key
    }

    private val state = NovaPanelState()
    private val runtime = HeldRuntime()
    private val written = ArrayList<File>()
    private val offered = ArrayList<File>()
    private var foreground = true
    private var storageWorks = true
    private var offerResult: (File) -> NovaMomentShare.Result = { NovaMomentShare.Result.Offered(it) }

    private val controller by lazy {
        NovaStreamRecapReportPanel(
            context = rule.activity,
            panel = state,
            show = { page -> if (state.isOpen) state.push(page) else state.open(page) },
            foreground = { foreground },
            runtime = runtime,
            render = { NovaStreamRecapReport.render(it, "1.4.15", "release") },
            write = { endedAtUtc, bytes ->
                if (!storageWorks) null
                else NovaStreamRecapExport.write(File(rule.activity.cacheDir, "stream-recaps"), endedAtUtc, bytes)?.also { written += it }
            },
            offer = { file -> offered += file; offerResult(file) },
        )
    }

    private val recap: NovaStreamRecap.Recap = NovaStreamRecapReportTest.recap()
    private fun text(id: Int) = rule.activity.getString(id)

    /** The Library with the recap page open, as View leaves it. */
    private fun recapOpen(): NovaTestKeys {
        state.open(Plain("library-root"))
        state.push(NovaStreamRecapPages.page(rule.activity, recap, onSaveReport = { controller.save(recap) }))
        return rule.setPanelContent {
            NovaPageStackHost(state = state, onCloseRequest = { state.close() }) {
                NovaRow(title = it.key, onClick = {}, modifier = Modifier.novaInitialFocus())
            }
        }
    }

    /** Save Report pressed on the recap page, through the page's own exit. */
    private fun pressSave() {
        rule.onNodeWithText(text(R.string.nova_recap_save_report)).performClick()
        rule.waitForIdle()
    }

    private fun saving() = state.top?.key?.startsWith("nova-stream-recap-saving-") == true

    private fun finish() {
        rule.runOnUiThread { runtime.runIo() }
        rule.runOnUiThread { runtime.runMain() }
        rule.waitForIdle()
    }

    @Test fun anOwnedSaveReplacesItsSavingPageWithSavedAndDoneIsFocused() {
        recapOpen()
        pressSave()
        assertTrue("Save shows its own Saving page", saving())
        assertTrue("nothing is written on the press", written.isEmpty())
        rule.runOnUiThread { runtime.runIo() }
        assertEquals(1, written.size)
        assertTrue("the answer waits for the main thread", saving())
        rule.runOnUiThread { runtime.runMain() }
        rule.waitForIdle()
        val saved = state.top as NovaCommonPage.Confirm
        assertEquals(text(R.string.nova_recap_report_saved_title), saved.title)
        rule.onNodeWithText(text(R.string.nova_recap_report_done)).assertIsFocused()
        assertEquals(2, state.depth)
        assertTrue("saving never opens the chooser", offered.isEmpty())
    }

    @Test fun bOnTheSavingPageDropsTheAnswer() {
        val keys = recapOpen()
        pressSave()
        keys.back()
        rule.waitForIdle()
        assertEquals("library-root", state.top?.key)
        finish()
        assertEquals("nothing reopens", "library-root", state.top?.key)
        assertEquals(1, state.depth)
    }

    @Test fun closeOnTheSavingPageDropsTheAnswer() {
        recapOpen()
        pressSave()
        rule.onNodeWithText(text(R.string.nova_recap_close)).performClick()
        rule.waitForIdle()
        finish()
        assertEquals("library-root", state.top?.key)
        assertFalse(state.contains("nova-stream-recap-saved-${written.single().name}"))
    }

    @Test fun closingThePanelDuringTheSaveKeepsItClosed() {
        recapOpen()
        pressSave()
        rule.runOnUiThread { state.close() }
        rule.waitForIdle()
        finish()
        assertFalse(state.isOpen)
    }

    @Test fun switchingRootDuringTheSaveIsNotUndone() {
        recapOpen()
        pressSave()
        rule.runOnUiThread { state.switchRoot(Plain("options"), NovaEdge.Start) }
        rule.waitForIdle()
        finish()
        assertEquals("options", state.top?.key)
        assertEquals(1, state.depth)
    }

    @Test fun aCoveredSavingPageNeverStealsThePageAboveIt() {
        recapOpen()
        pressSave()
        val savingKey = state.top!!.key
        rule.runOnUiThread { state.push(Plain("search")) }
        rule.waitForIdle()
        finish()
        assertEquals("search", state.top?.key)
        assertFalse("the covered Saving page goes quietly", state.contains(savingKey))
    }

    @Test fun aNewerSaveRetiresTheOlderOne() {
        recapOpen()
        pressSave()
        val first = state.top!!.key
        rule.runOnUiThread { controller.save(recap) }
        rule.waitForIdle()
        assertFalse(state.contains(first))
        assertTrue(saving())
        finish()
        assertEquals("both writes finish", 2, written.size)
        val saved = state.top as NovaCommonPage.Confirm
        assertEquals("nova-stream-recap-saved-${written.last().name}", saved.key)
        assertFalse(state.contains("nova-stream-recap-saved-${written.first().name}"))
    }

    @Test fun theScreenStoppingDropsTheAnswerAndItsSavingPage() {
        recapOpen()
        pressSave()
        val savingKey = state.top!!.key
        rule.runOnUiThread { controller.retire() }
        rule.waitForIdle()
        assertFalse(state.contains(savingKey))
        finish()
        assertEquals("library-root", state.top?.key)
    }

    @Test fun anAnswerArrivingInTheBackgroundIsDropped() {
        recapOpen()
        pressSave()
        foreground = false
        finish()
        assertFalse(saving())
        assertFalse(state.top is NovaCommonPage.Confirm)
    }

    @Test fun aStorageFailureReplacesTheSavingPageInPlace() {
        storageWorks = false
        recapOpen()
        pressSave()
        finish()
        val problem = state.top as NovaCommonPage.Notice
        assertEquals(text(R.string.nova_recap_report_storage_failed), problem.message)
    }

    @Test fun anInvalidRecapIsAnsweredAtOnceAndNothingIsWritten() {
        val bad = recap.copy(acceptedSamples = -1)
        state.open(Plain("library-root"))
        state.push(NovaStreamRecapPages.page(rule.activity, bad, onSaveReport = { controller.save(bad) }))
        rule.setPanelContent {
            NovaPageStackHost(state = state, onCloseRequest = { state.close() }) {
                NovaRow(title = it.key, onClick = {}, modifier = Modifier.novaInitialFocus())
            }
        }
        pressSave()
        assertEquals(text(R.string.nova_recap_report_invalid), (state.top as NovaCommonPage.Notice).message)
        assertTrue(runtime.io.isEmpty())
        assertTrue(written.isEmpty())
    }

    @Test fun shareIsOnlyTheSavedPagesExplicitPressAndItsProblemIsSaidInPlace() {
        offerResult = { NovaMomentShare.Result.Failed(NovaMomentShare.Reason.NO_SHARE_TARGET) }
        recapOpen()
        pressSave()
        finish()
        assertTrue(offered.isEmpty())
        rule.onNodeWithText(text(R.string.nova_recap_report_share)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(written.single()), offered)
        assertEquals(text(R.string.nova_recap_report_no_share_target), (state.top as NovaCommonPage.Notice).message)
    }
}
