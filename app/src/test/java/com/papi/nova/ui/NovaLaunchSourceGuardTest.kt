package com.papi.nova.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class NovaLaunchSourceGuardTest {

    @Test
    fun gameDetailLaunchUsesSelectedMangoHudState() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")
        val primaryLaunch = detail.section("onPrimaryLaunch = {", "},\n                    onLaunchOptions")
        val launchOptionsCall = detail.section("onLaunchOptions = {", "},\n                    onProfilePreference")
        val launchOptions = detail.section(
            "private fun showLaunchOptions(",
            "private fun optionLabel("
        )

        assertTrue(
            "primary Play should pass the selected MangoHUD state into the launch request",
            primaryLaunch.contains("currentGame.copy(mangohud = mangoHudEnabled)")
        )
        assertTrue(
            "Launch Options should carry the selected MangoHUD state into the dialog launch",
            launchOptionsCall.contains("mangoHudEnabled") &&
                launchOptions.contains("mangoHudEnabled: Boolean") &&
                launchOptions.contains("game.copy(mangohud = mangoHudEnabled)")
        )
    }

    @Test
    fun libraryLaunchSynchronizesMangoHudBeforeStartingStream() {
        val activity = readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val launchGame = activity.section(
            "private fun launchGame(",
            "private fun resumeActiveSession("
        )

        assertTrue(
            "library launch should explicitly sync MangoHUD state before starting the stream",
            launchGame.contains("apiClient.setMangoHud(game.id, game.mangohud)") &&
                launchGame.indexOf("apiClient.setMangoHud(game.id, game.mangohud)") <
                launchGame.indexOf("ServerHelper.doStart(")
        )
    }

    @Test
    fun shortcutLaunchUsesPolarisPreflightBeforeStartingStream() {
        val trampoline = readSource("src/main/java/com/papi/nova/ShortcutTrampoline.kt")
        val serverHelper = readSource("src/main/java/com/papi/nova/utils/ServerHelper.kt")
        val preparePlan = trampoline.section(
            "private fun preparePolarisShortcutLaunchPlan(",
            "private fun findPolarisShortcutGame("
        )
        val directLaunch = trampoline.section(
            "if (currentApp != null) {",
            "} else {\n                                            finish()"
        )

        assertTrue(
            "shortcut launch should resolve Polaris library metadata before direct game start",
            preparePlan.contains("findPolarisShortcutGame(apiClient, shortcutApp)") &&
                trampoline.contains("apiClient.getGames(limit = 100)")
        )
        assertTrue(
            "shortcut launch should sync the Polaris client settings/profile contract before stream start",
            preparePlan.contains("syncShortcutLaunchPreflightSettings(apiClient, withVirtualDisplay)") &&
                trampoline.contains("apiClient.updateClientSettings(") &&
                preparePlan.contains("apiClient.getOptimization(")
        )
        assertTrue(
            "shortcut launch should carry Polaris optimization/profile extras into Game just like library launches",
            directLaunch.contains("launchPlan.profilePreference") &&
                directLaunch.contains("launchPlan.launchOptimizationJson") &&
                serverHelper.contains("Game.EXTRA_AI_PROFILE_PREFERENCE") &&
                serverHelper.contains("Game.EXTRA_LAUNCH_OPTIMIZATION")
        )
    }

    @Test
    fun libraryFollowsUpActiveSessionRefreshAfterReturningFromStream() {
        val activity = readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val quickMenu = readSource("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt")
        val onResume = activity.section(
            "override fun onResume()",
            "override fun onKeyDown("
        )
        val loadGames = activity.section(
            "private fun loadGames(forceRefresh: Boolean)",
            "private fun refreshActiveSession("
        )
        val followUps = activity.section(
            "private fun scheduleActiveSessionFollowUpRefreshes(",
            "private fun queryActiveSession()"
        )
        val quit = game.section(
            "fun quit()",
            "override fun showGameMenu("
        )
        val markLocalSessionEnd = game.section(
            "private fun markLocalSessionEnd()",
            "fun disconnect()"
        )

        assertTrue(
            "Game End should mark the local session card stale before returning to Library",
            quit.contains("markLocalSessionEnd()") &&
                markLocalSessionEnd.contains("NovaSessionEndSignal.mark(") &&
                markLocalSessionEnd.contains("EXTRA_PC_UUID") &&
                markLocalSessionEnd.contains("EXTRA_HOST")
        )
        assertTrue(
            "Game stop should also mark local End so all confirmed quit paths clear the Library session card",
            game.contains("if (quitOnStop && !watchOnlyRequested)") &&
                game.contains("markLocalSessionEnd()")
        )
        assertTrue(
            "Game should write the local End marker only once so stopConnection cannot re-mark after Library consumes it",
            game.contains("localSessionEndMarked") &&
                markLocalSessionEnd.contains("if (localSessionEndMarked)") &&
                markLocalSessionEnd.contains("localSessionEndMarked = true")
        )
        assertTrue(
            "Command Center End should defer the local End marker to the confirmed Game quit dialog",
            !quickMenu.contains("NovaSessionEndSignal.mark(") &&
                quickMenu.contains("game.quit()")
        )
        assertTrue(
            "Library resume should consume the local End marker before polling can re-add a paused session",
            onResume.contains("consumeLocalSessionEndSignal()") &&
                onResume.contains("scheduleActiveSessionFollowUpRefreshes(clearOnly = true)")
        )
        assertTrue(
            "initial library load should also consume a local End marker before showing session state",
            loadGames.contains("val clearSessionAfterLocalEnd = consumeLocalSessionEndSignal()") &&
                loadGames.contains("if (clearSessionAfterLocalEnd) null else result.activeSession")
        )
        assertTrue(
            "Library should consume the End marker scoped by both PC UUID and host",
            activity.contains("NovaSessionEndSignal.consume(this, streamPcUuid, streamHost)")
        )

        assertTrue(
            "library resume should schedule delayed active-session refreshes for host quit teardown",
            onResume.contains("refreshActiveSession(scheduleFollowUps = true)")
        )
        assertTrue(
            "initial library load should also schedule delayed refreshes when it catches paused teardown",
            loadGames.contains("if (result.activeSession != null)") &&
                loadGames.contains("scheduleActiveSessionFollowUpRefreshes()")
        )
        assertTrue(
            "follow-up refreshes should wait before polling Polaris again",
            followUps.contains("ACTIVE_SESSION_RESUME_REFRESH_DELAYS_MS") &&
                followUps.contains("delay(delayMillis)")
        )
        assertTrue(
            "follow-up refreshes should stop once Polaris reports idle/no active session",
            followUps.contains("if (refreshed == null)")
        )
        assertTrue(
            "clear-only follow-ups should avoid re-showing paused teardown after local End",
            followUps.contains("clearOnly: Boolean = false") &&
                followUps.contains("if (clearOnly && refreshed != null)")
        )
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.section(startMarker: String, endMarker: String): String =
        substring(indexOf(startMarker), indexOf(endMarker))
}
