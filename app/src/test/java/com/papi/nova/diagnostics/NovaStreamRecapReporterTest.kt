package com.papi.nova.diagnostics

import com.papi.nova.diagnostics.NovaStreamRecapReporter.Outcome
import com.papi.nova.diagnostics.NovaStreamRecapReporter.Saved
import com.papi.nova.ui.NovaCommandCenterRuntime
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Save Report renders on the press and writes only those bytes off the main thread; the saved
 * confirmation reaches only the newest press of a screen still there; Share is its own press.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaStreamRecapReporterTest {
    @get:Rule val folder = TemporaryFolder()

    private class HeldRuntime : NovaCommandCenterRuntime {
        val io = ArrayList<suspend () -> Unit>()
        override fun launchIo(name: String, block: suspend () -> Unit) {
            assertEquals(NovaStreamRecapReporter.TASK, name)
            io += block
        }
        override suspend fun onMain(block: () -> Unit) = block()
        override fun postDelayed(delayMs: Long, block: () -> Unit) = Unit
        fun runIo() = runBlocking { val pending = io.toList(); io.clear(); pending.forEach { it() } }
    }

    private val runtime = HeldRuntime()
    private val rendered = ArrayList<ByteArray>()
    private val written = ArrayList<ByteArray>()
    private val savedReports = ArrayList<Saved>()
    private val offered = ArrayList<File>()
    private val outcomes = ArrayList<Outcome>()
    private var foreground = true
    private var storageWorks = true
    private var renderWorks = true

    private val reporter = NovaStreamRecapReporter(
        runtime = runtime,
        render = { recap ->
            if (!renderWorks) null
            else NovaStreamRecapReport.render(recap, "1.4.15", "release")!!.also { rendered += it }
        },
        write = { endedAtUtc, bytes ->
            written += bytes
            if (storageWorks) NovaStreamRecapExport.write(File(folder.root, "stream-recaps"), endedAtUtc, bytes) else null
        },
        saved = { savedReports += it },
        offer = { file ->
            offered += file
            NovaMomentShare.Result.Offered(file)
        },
        feedback = { outcomes += it },
        canShare = { foreground },
    )

    @Test fun thePressRendersTheReportAndOnlyThoseBytesAreWrittenLater() {
        val recap = NovaStreamRecapReportTest.recap()
        reporter.report(recap)
        assertEquals("rendered on the press", 1, rendered.size)
        assertTrue("nothing written on the main thread", written.isEmpty())
        assertTrue(savedReports.isEmpty())
        runtime.runIo()
        assertArrayEquals(rendered.single(), written.single())
        val saved = savedReports.single()
        assertArrayEquals(rendered.single(), saved.file.readBytes())
        assertEquals(recap.endedAtUtc, saved.endedAtUtc)
        assertTrue("saving never opens the chooser", offered.isEmpty())
    }

    @Test fun anInvalidRecapIsRefusedWithoutAnyWrite() {
        renderWorks = false
        reporter.report(NovaStreamRecapReportTest.recap())
        assertEquals(listOf(Outcome.INVALID), outcomes)
        assertTrue(runtime.io.isEmpty())
        assertTrue(written.isEmpty())
    }

    @Test fun aNewerSaveRetiresTheOlderConfirmation() {
        reporter.report(NovaStreamRecapReportTest.recap(generation = 1L))
        reporter.report(NovaStreamRecapReportTest.recap(generation = 2L))
        runtime.runIo()
        assertEquals("both files are written, only the newest confirms", 2, written.size)
        assertEquals(1, savedReports.size)
        assertArrayEquals(rendered.last(), savedReports.single().file.readBytes())
    }

    @Test fun leavingTheScreenRetiresAConfirmationStillOnItsWay() {
        reporter.report(NovaStreamRecapReportTest.recap())
        reporter.retire()
        runtime.runIo()
        assertEquals(1, written.size)
        assertTrue("no stale confirmation appears", savedReports.isEmpty())
        assertTrue(outcomes.isEmpty())
    }

    @Test fun aStorageFailureIsSaidAndNothingIsOffered() {
        storageWorks = false
        reporter.report(NovaStreamRecapReportTest.recap())
        runtime.runIo()
        assertEquals(listOf(Outcome.STORAGE_FAILED), outcomes)
        assertTrue(savedReports.isEmpty())
        assertTrue(offered.isEmpty())
    }

    @Test fun onlyTheExplicitShareOffersTheSavedFileAndOnlyInTheForeground() {
        reporter.report(NovaStreamRecapReportTest.recap())
        runtime.runIo()
        val saved = savedReports.single()
        assertTrue(offered.isEmpty())
        foreground = false
        reporter.share(saved)
        assertTrue("a screen no longer in front opens nothing", offered.isEmpty())
        foreground = true
        reporter.share(saved)
        assertEquals(listOf(saved.file), offered)
        assertEquals(listOf(Outcome.OFFERED), outcomes)
    }

    @Test fun shareFailuresAreReportedNotSwallowed() {
        val failing = NovaStreamRecapReporter(
            runtime = runtime,
            render = { NovaStreamRecapReport.render(it, "1.4.15", "release") },
            write = { at, bytes -> NovaStreamRecapExport.write(File(folder.root, "r"), at, bytes) },
            saved = { savedReports += it },
            offer = { NovaMomentShare.Result.Failed(NovaMomentShare.Reason.NO_SHARE_TARGET) },
            feedback = { outcomes += it },
            canShare = { true },
        )
        failing.report(NovaStreamRecapReportTest.recap())
        runtime.runIo()
        failing.share(savedReports.single())
        assertEquals(listOf(Outcome.NO_SHARE_TARGET), outcomes)
    }
}
