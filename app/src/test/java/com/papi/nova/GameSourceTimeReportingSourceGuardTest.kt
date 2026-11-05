package com.papi.nova

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Polaris copies only the client_presentation keys it knows (nvhttp.cpp
 * update_client_presentation_report) and drops the rest without a word, so a new key would be lost
 * silently. #242 sends its state in the frame_pacing_state field Polaris already accepts, and
 * everything structured in the client_runtime object, which Polaris keeps whole.
 */
class GameSourceTimeReportingSourceGuardTest {
    private val game = File("src/main/java/com/papi/nova/Game.kt").readText()
    private val polarisAccepts = setOf(
        "status", "reason", "decoder", "display_mode", "refresh_rate_policy", "frame_pacing_state",
        "applied_refresh_rate_hz", "target_refresh_rate_hz", "render_fps", "dropped_frame_ratio", "display_mode_id",
    )

    private val report: String
        get() = game.substringAfter("private fun clientPresentationReport(").substringBefore("\nprivate fun ")

    private val key: String
        get() = report.substringAfter("var reportKey").substringBefore("var presentation:JSONObject")

    @Test
    fun clientPresentationUsesOnlyKeysPolarisKeeps() {
        val keys = Regex("""presentation\.put\("([a-z_]+)"""").findAll(report).map { it.groupValues[1] }.toSet()
        assertTrue("sent: $keys", keys.isNotEmpty() && polarisAccepts.containsAll(keys))
        assertTrue("frame_pacing_state" in keys)
    }

    @Test
    fun aChangeOfPacingStateIsReportedAndItComesFromTheSharedReducer() {
        assertTrue(report.contains("val presentationFacts = presentationTruth(status, true, appliedRefreshRate)\n" +
            "var framePacingState:String = presentationFacts.client.toFramePacingState()"))
        assertTrue("a state change must change the dedupe key", key.contains("framePacingState"))
    }

    @Test
    fun theFrameWindowComesFromTheSameReducerAndNeverChurnsTheReport() {
        // Design E.1: render_fps and dropped_frame_ratio, as the reducer bounded them, only when measured.
        assertTrue(report.contains("presentationFacts.renderFps?.let { presentation.put(\"render_fps\", it) }"))
        assertTrue(report.contains("presentationFacts.droppedFrameRatio?.let { presentation.put(\"dropped_frame_ratio\", it) }"))
        for (count in listOf("renderFps", "droppedFrameRatio", "presentationFacts")) {
            assertTrue("$count must not be in the dedupe key", !key.contains(count))
        }
        // The reducer every surface uses is handed the renderer's report (design E.2).
        val reducer = game.substringAfter("internal fun presentationTruth(").substringBefore("\nprivate fun ")
        assertTrue(reducer.contains("report = decoderRenderer?.sourceTimeReport()"))
    }

    @Test
    fun anUnchangedReportIsRefreshedOnACadenceAndOnceAtTheEnd() {
        // W26 F5: the state key still decides a transition, but it is no longer the only gate: an
        // unchanged report is sent again on the cadence, and the end of a stream sends it once more.
        // W34 D3: through one sender, so the end is never lost to a report in flight.
        assertTrue(game.contains("private val clientPresentationCadence = com.papi.nova.runtime.PresentationReportCadence()"))
        assertTrue(game.contains("private val clientPresentationReports = com.papi.nova.runtime.PresentationReportSender(clientPresentationCadence)"))
        assertTrue("the key alone no longer gates", !game.contains("lastReportedClientPresentationKey"))
        assertTrue("the gate that dropped the end is gone", !game.contains("clientPresentationReportInFlight"))
        val poll = game.substringAfter("private fun reportClientPresentationIfNeeded(").substringBefore("\nprivate fun ")
        assertTrue(poll.contains("clientPresentationReports.report(report.key, android.os.SystemClock.elapsedRealtime()) { why ->"))
        val stop = game.substringAfter("private fun stopConnection() {").substringBefore("\nprivate fun ")
        assertTrue(stop.contains("\nendClientPresentationReport(lastPolarisSessionStatus)\n"))
        assertTrue("before the status refresh stops", stop.indexOf("endClientPresentationReport(") < stop.indexOf("stopPolarisLiveSessionStatusRefresh()"))
        assertTrue("and before the decoder does", stop.indexOf("endClientPresentationReport(") < stop.indexOf("conn!!.stop()"))
        assertTrue("captured on the caller's thread, not in a lifecycle job", !stop.contains("NovaClientPresentationEnd"))
        val end = game.substringAfter("private fun endClientPresentationReport(").substringBefore("\nprivate fun ")
        val requested = end.indexOf("clientPresentationReports.endRequested()")
        val captured = end.indexOf("val snapshot = captureClientSettingsSnapshot(report.presentation) ?: return null")
        val sent = end.indexOf("return clientPresentationReports.endAsync(report.key, android.os.SystemClock.elapsedRealtime()) { why ->\n" +
            "clientPresentationSent(report, why, sendClientSettingsSnapshot(snapshot))")
        assertTrue("$requested < $captured < $sent", requested in 0 until captured && captured < sent)
        val cadence = File("src/main/java/com/papi/nova/runtime/PresentationReportCadence.kt").readText()
        assertTrue(cadence.contains("const val REFRESH_MS = 5_000L"))
    }

    @Test
    fun theRuntimeSnapshotAndTheDisplayProbeAreWired() {
        val snapshot = game.substringAfter("private fun captureClientSettingsSnapshot(").substringBefore("\nprivate fun ")
        assertTrue(snapshot.contains("presentationTruth(lastPolarisSessionStatus, true,"))
        assertTrue(game.contains("private fun prepareDisplayForRendering(currentDisplay:Display?):Float {\n" +
            "displayPresentationCapability = com.papi.nova.binding.video.DisplayPresentationProbe.read(currentDisplay)"))
        assertTrue(game.contains("if (displayId == streamingDisplayId) {\n    displayPresentationCapability ="))
        val sync = File("src/main/java/com/papi/nova/manager/StreamSyncManager.kt").readText()
        assertTrue(sync.contains("put(json, \"display_presentation\", com.papi.nova.binding.video.DisplayPresentationProbe.read(display).toJson())"))
    }
}
