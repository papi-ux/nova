package com.papi.nova

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A launch the host refused read as a network fault: Mirror Desktop with PyroWave came back as
 * "Failed to start RTSP handshake (error 503)" with the firewall ports under it, and the startup
 * card ghosted behind the page. A policy refusal floated a Toast cut off by its ellipsis.
 */
class GameLaunchRefusalCopySourceGuardTest {
    private val game = File("src/main/java/com/papi/nova/Game.kt").readText()

    @Test
    fun aHostThatAnsweredIsNotAPortProblem() {
        val stage = game.substringAfter("override fun stageFailed(").substringBefore("showNovaLaunchIssueSheet(dialogText)")
        assertTrue(stage.contains("val hostAnswered = errorCode == RTSP_SERVICE_UNAVAILABLE"))
        assertTrue(stage.contains("dialogText = getResources().getString(R.string.nova_launch_host_refused_stream)"))
        assertTrue(stage.contains("&& !hostAnswered)"))
    }

    @Test
    fun theLaunchIssuePageTakesTheStartupCardDown() {
        val sheet = game.substringAfter("private fun showNovaLaunchIssueSheet(message: String) {").substringBefore("val surfaces = novaSurfaces")
        assertTrue(sheet.contains("novaProgressOverlay?.dismiss()"))
    }

    @Test
    fun aPolicyRefusalIsAStatePageNotAToast() {
        val gate = game.substringAfter("if (launchDecision.policyBlocked)").substringBefore("return@launchRuntimeIo")
        assertTrue(gate.contains("showNovaLaunchIssueSheet("))
        assertFalse(gate.contains("Toast.makeText"))
    }
}
