package com.papi.nova.diagnostics

import com.papi.nova.diagnostics.NovaMomentReporter.Outcome
import com.papi.nova.ui.NovaCommandCenterRuntime
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaMomentReporterTest {
    @get:Rule val folder = TemporaryFolder()

    /** A runtime whose IO work waits until the test runs it, and whose main side can be gone. */
    private class HeldRuntime : NovaCommandCenterRuntime {
        val io = ArrayList<suspend () -> Unit>()
        var alive = true
        override fun launchIo(name: String, block: suspend () -> Unit) {
            assertEquals(NovaMomentReporter.TASK, name)
            io += block
        }
        override suspend fun onMain(block: () -> Unit) {
            if (alive) block()
        }
        override fun postDelayed(delayMs: Long, block: () -> Unit) = Unit
        fun runIo() = runBlocking { val pending = io.toList(); io.clear(); pending.forEach { it() } }
    }

    private val logDirectory get() = File(folder.root, "diagnostics").apply { mkdirs() }

    /** The stream's live state, which keeps changing after the press. */
    private var liveFps = 59.8
    private var streamActive = true
    private var shareAvailable = true
    private val marked = ArrayList<NovaMomentLog.Marked>()

    private fun freezeNow(): NovaMomentReporter.Frozen? {
        if (!NovaMomentReport.canCapture(streamActive, connected = true, finishing = false, destroyed = false)) return null
        val snapshot = NovaMomentReport.capture(
            markedAtUtc = "2026-10-08T11:30:00Z", nowElapsedMs = 10_000L, novaVersion = "1.4.14", buildType = "debug",
            androidRelease = "15", androidSdk = 35, device = "Pixel 9", watching = false, space = false,
            requestedWidth = 1920, requestedHeight = 1080, requestedFps = 60f, requestedBitrateKbps = 20_000, requestedCodec = "AUTO",
            sample = NovaMomentReport.Sample("c2.android.avc.decoder", 1920, 1080, liveFps, 60.0, 2.0, 15, 2, 0.5, null, 100, 1, 9_500L),
        )
        File(logDirectory, "nova.log.0").appendText("Nova: app_session_id=live-secret host=192.168.4.4 fps=$liveFps\n")
        val log = NovaMomentLog.mark(logDirectory).also { marked += it }
        return NovaMomentReporter.Frozen(snapshot, mapOf("Den Rig" to "<host-name>"), log)
    }

    private val outcomes = ArrayList<Outcome>()
    private val savedReports = ArrayList<NovaMomentReporter.Saved>()
    private val offered = ArrayList<File>()

    private fun reporter(
        runtime: NovaCommandCenterRuntime,
        prepare: (NovaMomentReporter.Frozen) -> File? = { NovaMomentReporter.prepare(File(folder.root, "out"), it) },
        offer: (File) -> NovaMomentShare.Result = { file ->
            offered += file
            NovaMomentShare.Result.Offered(file)
        },
    ) = NovaMomentReporter(
        runtime = runtime,
        freeze = ::freezeNow,
        prepare = prepare,
        saved = { savedReports += it },
        offer = offer,
        feedback = { outcomes += it },
        isActive = { streamActive },
        canShare = { shareAvailable },
    )

    @Test fun thePressSavesTheFrozenMomentAndOpensNothing() {
        val runtime = HeldRuntime()
        reporter(runtime).report()
        assertEquals(1, runtime.io.size)
        // The stream moves on, and its log grows, before the IO half runs.
        liveFps = 12.0
        File(logDirectory, "nova.log.0").appendText("Nova: written after the press\n")
        runtime.runIo()
        // Saved and confirmed in the stream; no chooser, no other app, no send.
        assertEquals(1, savedReports.size)
        assertTrue(offered.isEmpty())
        assertTrue(outcomes.isEmpty())
        val text = savedReports.single().file.readText()
        assertTrue(text.contains("\"rendered_fps\": 59.8"))
        assertFalse(text.contains("\"rendered_fps\": 12"))
        assertFalse(text.contains("written after the press"))
        assertFalse(text.contains("live-secret"))
        assertFalse(text.contains("192.168.4.4"))
        assertEquals("2026-10-08T11:30:00Z", savedReports.single().markedAtUtc)
        // The log was held only until it was read.
        assertTrue(marked.single().isClosed)
    }

    @Test fun onlyTheExplicitShareOpensTheChooserWithTheSavedBytes() {
        val runtime = HeldRuntime()
        val reporter = reporter(runtime)
        reporter.report()
        runtime.runIo()
        val saved = savedReports.single()
        val bytes = saved.file.readBytes()
        liveFps = 30.0 // later state cannot change what is shared
        reporter.share(saved)
        assertEquals(listOf(Outcome.OFFERED), outcomes)
        assertEquals(saved.file, offered.single())
        assertTrue(bytes.contentEquals(offered.single().readBytes()))
    }

    @Test fun noActiveStreamMeansNoReportAndNoIo() {
        streamActive = false
        val runtime = HeldRuntime()
        reporter(runtime).report()
        assertTrue(runtime.io.isEmpty())
        assertEquals(listOf(Outcome.NOT_ACTIVE), outcomes)
        assertTrue(savedReports.isEmpty())
    }

    @Test fun teardownRetiresTheHandoffAndClosesTheHeldLog() {
        val runtime = HeldRuntime()
        val reporter = reporter(runtime)
        reporter.report()
        // The Activity is destroyed before the IO half ran: the log is not left open.
        reporter.retire()
        assertTrue(marked.single().isClosed)
        runtime.runIo()
        assertTrue(savedReports.isEmpty())
        assertTrue(outcomes.isEmpty())
    }

    @Test fun aRuntimeThatIsGonePublishesNothing() {
        val runtime = HeldRuntime()
        reporter(runtime).report()
        runtime.alive = false // Activity finishing or destroyed: runtime refuses main-thread work
        runtime.runIo()
        assertTrue(savedReports.isEmpty())
        assertTrue(outcomes.isEmpty())
        assertTrue(marked.single().isClosed)
    }

    @Test fun aStoppedStreamInALiveActivityPublishesNothing() {
        val runtime = HeldRuntime()
        reporter(runtime).report()
        streamActive = false
        runtime.runIo()
        assertTrue(savedReports.isEmpty())
        assertTrue(outcomes.isEmpty())
        assertTrue(offered.isEmpty())
        assertTrue(marked.single().isClosed)
    }

    @Test fun aNewPressClosesTheLogOfWorkThatNeverEnteredIo() {
        val runtime = HeldRuntime()
        val reporter = reporter(runtime)
        reporter.report()
        runtime.io.clear() // cancelled before the coroutine entered its body
        reporter.report()
        assertTrue(marked.first().isClosed)
        runtime.runIo()
        assertEquals(1, savedReports.size)
        assertTrue(marked.all { it.isClosed })
    }

    @Test fun aSavedMomentCanBeSharedAfterItsStreamStops() {
        val runtime = HeldRuntime()
        val reporter = reporter(runtime)
        reporter.report()
        runtime.runIo()
        streamActive = false
        reporter.share(savedReports.single())
        assertEquals(savedReports.single().file, offered.single())
        assertEquals(listOf(Outcome.OFFERED), outcomes)
    }

    @Test fun aClosedScreenCannotLaunchTheOldShareConfirmation() {
        val runtime = HeldRuntime()
        val reporter = reporter(runtime)
        reporter.report()
        runtime.runIo()
        shareAvailable = false
        reporter.share(savedReports.single())
        assertTrue(offered.isEmpty())
        assertTrue(outcomes.isEmpty())
    }

    @Test fun aNewerPressRetiresAnOlderHandoffAndBothFilesStayDistinct() {
        val runtime = HeldRuntime()
        val reporter = reporter(runtime)
        reporter.report()
        liveFps = 30.0
        reporter.report()
        runtime.runIo()
        // Only the newest press is confirmed; both moments were saved as their own files.
        assertTrue(savedReports.single().file.readText().contains("\"rendered_fps\": 30"))
        assertEquals(2, File(folder.root, "out").listFiles()!!.size)
        assertTrue(marked.all { it.isClosed })
    }

    @Test fun failuresGiveMeaningfulFeedback() {
        val runtime = HeldRuntime()
        reporter(runtime, prepare = { null }).report()
        runtime.runIo()
        assertEquals(listOf(Outcome.STORAGE_FAILED), outcomes)

        outcomes.clear()
        reporter(runtime, prepare = { throw java.io.IOException("disk") }).report()
        runtime.runIo()
        assertEquals(listOf(Outcome.STORAGE_FAILED), outcomes)
        assertTrue(marked.all { it.isClosed })

        outcomes.clear()
        val reporter = reporter(runtime, offer = { NovaMomentShare.Result.Failed(NovaMomentShare.Reason.NO_SHARE_TARGET) })
        reporter.report()
        runtime.runIo()
        reporter.share(savedReports.last())
        assertEquals(listOf(Outcome.NO_SHARE_TARGET), outcomes)
    }
}
