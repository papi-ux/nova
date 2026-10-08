package com.papi.nova

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Game and the Library meet the After-stream recap. The behaviour itself is tested on the
 * model and store; this guards the wiring a JVM test can't reach: one publication inside the
 * existing stop guard, a reason at every stop, the HUD-independent feed, and no authority.
 */
class GameStreamRecapSourceGuardTest {
    private val game = File("src/main/java/com/papi/nova/Game.kt").readText()
    private fun section(start: String, end: String) = game.substringAfter(start).substringBefore(end)

    @Test fun theRecapIsPublishedOnceInsideTheExistingStopGuardAfterDoctorsUnchangedSummary() {
        val stop = section("private fun stopConnection() {", "override fun stageFailed(")
        val guard = stop.indexOf("if (connecting || connected)")
        val summary = stop.indexOf("val summary:Map<String, Any> = doctorTelemetry.summary()")
        val publish = stop.indexOf("streamRecap.finish(")
        val hud = stop.indexOf("novaHud?.dismiss()")
        assertTrue(guard in 0 until summary)
        assertTrue("after Doctor's summary, which is unchanged", publish > summary)
        assertTrue("before the HUD and controllers are torn down", publish in 0 until hud)
        assertEquals(1, Regex("streamRecap\\.finish\\(").findAll(game).count())
        assertTrue(stop.contains("NovaStreamRecapStore.process::publish"))
    }

    @Test fun everyStopSaysWhyFirst() {
        val calls = Regex("\\n(stopConnection\\(\\))").findAll(game).map { it.range.first }.toList()
        assertEquals(4, calls.size)
        calls.forEach { at ->
            val before = game.substring(maxOf(0, at - 400), at)
            assertTrue("a stop without a recap reason near ${game.substring(at, at + 20)}", before.contains("noteStreamRecapEnd("))
        }
        val terminated = section("override fun connectionTerminated(errorCode:Int)", "override fun connectionStatusUpdate(")
        assertTrue(terminated.contains("ML_ERROR_GRACEFUL_TERMINATION) com.papi.nova.diagnostics.NovaStreamRecap.End.CONNECTION_ENDED"))
        val reconnect = section("private fun reconnectAfterStreamError(", "private fun endStreamAfterUnreachableHost(")
        assertTrue(reconnect.contains("End.RECONNECT_REQUESTED"))
    }

    @Test fun samplesFeedTheRecapWhetherOrNotTheHudShows() {
        val perf = section("override fun onPerfSample(", "override fun onUsbPermissionPromptStarting(")
        val accept = perf.indexOf("streamRecap.accept(sample, android.os.SystemClock.elapsedRealtime())")
        val hud = perf.indexOf("if (novaHud != null && novaHud!!.isShowing)")
        assertTrue("fed before, and outside, the HUD's own branch", accept in 0 until hud)
        assertTrue(section("fun handleStreamStartedState() {", "attachNovaLiveBitrate()").contains("streamRecap.connected("))
    }

    @Test fun theGenerationIsTakenWhenTheGameBeginsAndReconnectsAreMarkedAsRequested() {
        assertTrue(game.contains("private val streamRecapGeneration:Long = com.papi.nova.diagnostics.NovaStreamRecapStore.process.begin()"))
        val launch = section("private fun launchReplacementStream(", "private fun reconnectAfterStreamError(")
        assertTrue(launch.contains("relaunchIntent.putExtra(EXTRA_RECONNECT_REQUESTED, true)"))
        assertTrue(game.contains("reconnectRequested = getIntent().getBooleanExtra(EXTRA_RECONNECT_REQUESTED, false)"))
    }

    @Test fun theRecapHasNoStorageLogNetworkOrTuningReach() {
        val sources = listOf(
            "diagnostics/NovaStreamRecap.kt", "diagnostics/NovaStreamRecapStore.kt",
            "ui/NovaStreamRecapText.kt", "ui/NovaStreamRecapCard.kt",
        ).map { File("src/main/java/com/papi/nova/$it").readText() }
        val forbidden = listOf("SharedPreferences", "File(", "LimeLog", "Log.", "http", "PolarisApiClient", "NvHTTP",
            "doctorTelemetry", "uploadDoctorSample", "DoctorAction", "setBitrate", "LiveTuning", "NovaMomentShare", "NovaMomentReporter", "startActivity")
        sources.forEach { source -> forbidden.forEach { assertFalse(it, source.contains(it)) } }
    }

    @Test fun theLibraryOnlyReadsTheRecapForItsRequestedHost() {
        val library = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
        val onResume = library.substringAfter("override fun onResume()").substringBefore("override fun ")
        assertTrue(onResume.contains("NovaStreamRecapStore.process.forHost(streamPcUuid)"))
        assertFalse(library.contains("NovaStreamRecapStore.process.publish"))
        assertFalse(library.contains("NovaStreamRecapStore.process.begin"))
    }
}
