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

    /** The stream's live state, which keeps changing after the press. */
    private var liveFps = 59.8
    private var streamActive = true

    private fun freezeNow(): NovaMomentReporter.Frozen? {
        if (!NovaMomentReport.canCapture(streamActive, connected = true, finishing = false, destroyed = false)) return null
        val snapshot = NovaMomentReport.capture(
            markedAtUtc = "2026-10-08T11:30:00Z", nowElapsedMs = 10_000L, novaVersion = "1.4.14", buildType = "debug",
            androidRelease = "15", androidSdk = 35, device = "Pixel 9", watching = false, space = false,
            requestedWidth = 1920, requestedHeight = 1080, requestedFps = 60f, requestedBitrateKbps = 20_000, requestedCodec = "AUTO",
            sample = NovaMomentReport.Sample("c2.android.avc.decoder", 1920, 1080, liveFps, 60.0, 2.0, 15, 2, 0.5, null, 100, 1, 9_500L),
        )
        val log = File(folder.root, "nova.log.0").apply { writeText("Nova: app_session_id=live-secret host=192.168.4.4\n") }
        return NovaMomentReporter.Frozen(snapshot, mapOf("Den Rig" to "<host-name>"), listOf(NovaMomentLog.Part(log, log.length())))
    }

    private val outcomes = ArrayList<Outcome>()
    private val offered = ArrayList<File>()

    private fun reporter(runtime: NovaCommandCenterRuntime, offer: (File) -> NovaMomentShare.Result = { file ->
        offered += file
        NovaMomentShare.Result.Offered(file)
    }) = NovaMomentReporter(
        runtime = runtime,
        freeze = ::freezeNow,
        prepare = { frozen -> NovaMomentReporter.prepare(File(folder.root, "out"), frozen) },
        offer = offer,
        feedback = { outcomes += it },
    )

    @Test fun thePressFreezesTheMomentBeforeAnyIoAndLaterStateCannotReplaceIt() {
        val runtime = HeldRuntime()
        reporter(runtime).report()
        assertEquals(1, runtime.io.size)
        // The stream moves on, and its log grows, before the IO half runs.
        liveFps = 12.0
        File(folder.root, "nova.log.0").appendText("Nova: written after the press\n")
        runtime.runIo()
        assertEquals(listOf(Outcome.OFFERED), outcomes)
        val text = offered.single().readText()
        assertTrue(text.contains("\"rendered_fps\": 59.8"))
        assertFalse(text.contains("\"rendered_fps\": 12"))
        assertFalse(text.contains("written after the press"))
        // The log tail is scrubbed of the command token and the address.
        assertFalse(text.contains("live-secret"))
        assertFalse(text.contains("192.168.4.4"))
    }

    @Test fun noActiveStreamMeansNoReportAndNoIo() {
        streamActive = false
        val runtime = HeldRuntime()
        reporter(runtime).report()
        assertTrue(runtime.io.isEmpty())
        assertEquals(listOf(Outcome.NOT_ACTIVE), outcomes)
        assertTrue(offered.isEmpty())
    }

    @Test fun aStreamThatWentAwayRetiresTheHandoff() {
        val runtime = HeldRuntime()
        reporter(runtime).report()
        runtime.alive = false // Activity finishing or destroyed: runtime refuses main-thread work
        runtime.runIo()
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
        // Only the newest press opens the share sheet.
        assertEquals(listOf(Outcome.OFFERED), outcomes)
        assertTrue(offered.single().readText().contains("\"rendered_fps\": 30"))
        assertEquals(2, File(folder.root, "out").listFiles()!!.size)
        reporter.retire()
        reporter.report()
        reporter.retire()
        runtime.runIo()
        assertEquals(1, offered.size)
    }

    @Test fun failuresGiveMeaningfulFeedback() {
        val runtime = HeldRuntime()
        reporter(runtime, offer = { NovaMomentShare.Result.Failed(NovaMomentShare.Reason.NO_SHARE_TARGET) }).report()
        runtime.runIo()
        assertEquals(listOf(Outcome.NO_SHARE_TARGET), outcomes)

        outcomes.clear()
        val failing = NovaMomentReporter(runtime, ::freezeNow, prepare = { null }, offer = { error("not reached") }, feedback = { outcomes += it })
        failing.report()
        runtime.runIo()
        assertEquals(listOf(Outcome.STORAGE_FAILED), outcomes)

        outcomes.clear()
        val throwing = NovaMomentReporter(runtime, ::freezeNow, prepare = { throw java.io.IOException("disk") }, offer = { error("not reached") }, feedback = { outcomes += it })
        throwing.report()
        runtime.runIo()
        assertEquals(listOf(Outcome.STORAGE_FAILED), outcomes)
    }
}
