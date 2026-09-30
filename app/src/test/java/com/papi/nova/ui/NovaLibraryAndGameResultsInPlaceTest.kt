package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library and game page results floated as snackbars that were gone before they could be read
 * (audit X2). A launch that cannot start is the library's recovery page, which already said it
 * under the snackbar; a refresh that fails over games still showing is a Notice with Try Again.
 * On the game page a failed host check is its status line, Every Game's host results sit under
 * its plan, and a Steam launch mode the host did not save is its row's caption. What is left is
 * the "Launching" progress while a launch is asked for.
 */
class NovaLibraryAndGameResultsInPlaceTest {
    private val library = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
    private val detail = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText()
    private val content = File("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt").readText()

    private fun String.section(start: String, end: String): String {
        assertTrue("$start is still there", contains(start))
        return substringAfter(start).substringBefore(end)
    }

    @Test
    fun theLibrarySaysLaunchAndRefreshResultsInPlace() {
        val launch = library.section("private fun launchGame(", "private fun resumeActiveSession(")
        val resume = library.section("private fun resumeActiveSession(", "private fun endActiveSession(")
        val load = library.section("private fun loadGames(", "private fun ")
        assertEquals(
            "only the Launching progress floats",
            1,
            Regex("NovaSnackbar\\.").findAll(launch).count(),
        )
        assertTrue(launch.contains("NovaSnackbar.show(\n            this,\n            if (NovaSpaceUiState.isSpace(game))"))
        assertFalse(resume.contains("NovaSnackbar"))
        assertFalse(load.contains("NovaSnackbar"))
        assertTrue(load.contains("if (allGames.isNotEmpty() || activeSession != null) showRefreshFailed(message)"))
        val refresh = library.section("private fun showRefreshFailed(", "\n    }\n")
        assertTrue(refresh.contains("NovaCommonPage.Notice(") && refresh.contains("loadGames(forceRefresh = true)"))
    }

    @Test
    fun theGamePageSaysItsResultsInPlace() {
        assertFalse("nothing on the game page floats", detail.contains("NovaSnackbar"))
        assertTrue(detail.contains("onMessage = { messageRes, isError -> hostSyncNotice = NovaPolarisSyncNotice(getString(messageRes), isError) }"))
        assertTrue(detail.contains("onTextMessage = { message, isError -> hostSyncNotice = NovaPolarisSyncNotice(message, isError) }"))
        assertTrue(detail.contains("hostPlaySetupNotice = hostSyncNotice.takeIf { playSetupScope == NovaPlaySetupScope.EVERY_GAME }"))
        assertTrue(content.contains("hostPlaySetupNotice?.takeIf { everyGame }?.let { notice ->"))
        assertTrue(detail.contains("steamLaunchModeFailed = resolution.failed"))
        assertTrue(detail.section("private fun steamLaunchCaption(", "\n    }\n").contains("R.string.nova_steam_launch_mode_failed"))
        assertTrue(
            "the generic failure carries its sentence to the status line too",
            detail.contains("preflightMessage = getString(R.string.nova_game_detail_launch_preflight_unavailable)"),
        )
    }
}
