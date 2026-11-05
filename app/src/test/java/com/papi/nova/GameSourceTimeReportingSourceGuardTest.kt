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
        get() = game.substringAfter("private fun reportClientPresentationIfNeeded(").substringBefore("\nprivate fun ")

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
        val key = report.substringAfter("var reportKey").substringBefore("if ((reportKey")
        assertTrue("a state change must change the dedupe key", key.contains("framePacingState"))
    }

    @Test
    fun theFrameWindowComesFromTheSameReducerAndNeverChurnsTheReport() {
        // Design E.1: render_fps and dropped_frame_ratio, as the reducer bounded them, only when measured.
        assertTrue(report.contains("presentationFacts.renderFps?.let { presentation.put(\"render_fps\", it) }"))
        assertTrue(report.contains("presentationFacts.droppedFrameRatio?.let { presentation.put(\"dropped_frame_ratio\", it) }"))
        val key = report.substringAfter("var reportKey").substringBefore("if ((reportKey")
        for (count in listOf("renderFps", "droppedFrameRatio", "presentationFacts")) {
            assertTrue("$count must not be in the dedupe key", !key.contains(count))
        }
        // The reducer every surface uses is handed the renderer's report (design E.2).
        val reducer = game.substringAfter("internal fun presentationTruth(").substringBefore("\nprivate fun ")
        assertTrue(reducer.contains("report = decoderRenderer?.sourceTimeReport()"))
    }

    @Test
    fun theRuntimeSnapshotAndTheDisplayProbeAreWired() {
        val snapshot = game.substringAfter("private fun reportPolarisClientSettingsSnapshot(").substringBefore("\nprivate fun ")
        assertTrue(snapshot.contains("presentationTruth(lastPolarisSessionStatus, true,"))
        assertTrue(game.contains("private fun prepareDisplayForRendering(currentDisplay:Display?):Float {\n" +
            "displayPresentationCapability = com.papi.nova.binding.video.DisplayPresentationProbe.read(currentDisplay)"))
        assertTrue(game.contains("if (displayId == streamingDisplayId) {\n    displayPresentationCapability ="))
        val sync = File("src/main/java/com/papi/nova/manager/StreamSyncManager.kt").readText()
        assertTrue(sync.contains("put(json, \"display_presentation\", com.papi.nova.binding.video.DisplayPresentationProbe.read(display).toJson())"))
    }
}
