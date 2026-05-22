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
        val primaryLaunch = detail.section("onPrimaryLaunch = {", "},\n                    onLaunchModeSelected")
        val launchModeSelection = detail.section("fun selectLaunchMode(", "composeView.setContent {")

        assertTrue(
            "primary Play should pass the selected MangoHUD state into the launch request",
            primaryLaunch.contains("currentGame.copy(mangohud = mangoHudEnabled)")
        )
        assertTrue(
            "inline mode selection should keep the selected MangoHUD state in preview/preflight state",
            launchModeSelection.contains("loadOptimization(profilePreference, usesVirtualDisplay = mode == \"virtual_display\")") &&
                launchModeSelection.contains("currentGame = currentGame.copy(launchMode = updatedLaunchMode)")
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
        val directLaunch = trampoline.section(
            "if (currentApp != null) {",
            "} else {\n                                            finish()"
        )

        assertTrue(
            "shortcut launch should split read-only Polaris metadata resolution from side-effecting launch preflight",
            trampoline.contains("private fun resolvePolarisShortcutLaunchPlan(") &&
                trampoline.contains("private fun applyPolarisShortcutLaunchPreflight(")
        )

        val resolvePlan = trampoline.section(
            "private fun resolvePolarisShortcutLaunchPlan(",
            "private fun applyPolarisShortcutLaunchPreflight("
        )
        val applyPreflight = trampoline.section(
            "private fun applyPolarisShortcutLaunchPreflight(",
            "private fun findPolarisShortcutGame("
        )

        assertTrue(
            "shortcut launch should resolve Polaris library metadata before direct game start without mutating host/client state",
            resolvePlan.contains("findPolarisShortcutGame(apiClient, shortcutApp)") &&
                trampoline.contains("apiClient.getGames(limit = 100)") &&
                !resolvePlan.contains("setMangoHud(") &&
                !resolvePlan.contains("syncShortcutLaunchPreflightSettings") &&
                !resolvePlan.contains("getOptimization(")
        )
        assertTrue(
            "shortcut launch should sync the Polaris client settings/profile contract only when a launch is going ahead",
            applyPreflight.contains("apiClient.setMangoHud(polarisGame.id, polarisGame.mangohud)") &&
                applyPreflight.contains("syncShortcutLaunchPreflightSettings(apiClient, withVirtualDisplay)") &&
                applyPreflight.contains("apiClient.getOptimization(") &&
                trampoline.contains("applyPolarisShortcutLaunchPreflight(details, it, prefConfig.useVirtualDisplay)") &&
                directLaunch.contains("startConfirmedShortcutLaunch(")
        )
        assertTrue(
            "shortcut launch should carry Polaris optimization/profile extras into Game just like library launches",
            directLaunch.contains("readyLaunchPlan.profilePreference") &&
                directLaunch.contains("readyLaunchPlan.launchOptimizationJson") &&
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

    @Test
    fun gameBackPressClosesOpenQuickMenuInsteadOfReopeningIt() {
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val onBackPressed = game.section(
            "override fun onBackPressed()",
            "fun sendExecServerCmd("
        )

        assertTrue(
            "Back should dismiss an already-open Command Center instead of opening a second menu window",
            onBackPressed.contains("gameMenuCallbacks?.isMenuOpen() == true") &&
                onBackPressed.contains("hideGameMenu()") &&
                onBackPressed.indexOf("hideGameMenu()") < onBackPressed.indexOf("showGameMenu(null)")
        )
    }

    @Test
    fun streamStartupOverlayWaitsForNativeConnectionStartedBeforeDismissal() {
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val overlay = readSource("src/main/java/com/papi/nova/ui/SessionProgressOverlay.kt")
        val stageStarting = game.section(
            "override fun stageStarting(stage:String)",
            "override fun stageComplete(stage:String)"
        )
        val connectionStarted = game.section(
            "override fun connectionStarted()",
            "fun handleStreamStartedState()"
        )

        assertTrue(
            "native Moonlight stage names should feed the Nova startup overlay instead of only the legacy spinner",
            stageStarting.contains("novaProgressOverlay") &&
                stageStarting.contains("updateState(stage")
        )
        assertTrue(
            "Polaris 'streaming' should mean stream active/waiting for first frame, not immediate overlay dismissal",
            !overlay.contains("state == \"streaming\"")
        )
        assertTrue(
            "raw native stage ordering should not make startup overlay progress jump backwards",
            overlay.contains("progressFraction >= overlayState.value.progressFraction")
        )
        assertTrue(
            "native connectionStarted should mark input ready and then dismiss the startup overlay",
            connectionStarted.contains("updateState(\"input_ready\"") &&
                connectionStarted.contains("NOVA_PROGRESS_READY_DISMISS_DELAY_MS") &&
                connectionStarted.contains("novaProgressOverlay") &&
                connectionStarted.contains("dismiss()")
        )
    }

    @Test
    fun gameAcceptsSyntheticNovaControllerShortcutBeforeIgnoringAdbKeyEvents() {
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val handleKeyDown = game.section(
            "override fun handleKeyDown(event:KeyEvent):Boolean",
            "override fun onKeyUp("
        )
        val handleKeyUp = game.section(
            "override fun handleKeyUp(event:KeyEvent):Boolean",
            "override fun onKeyMultiple("
        )
        val shortcutHandler = game.section(
            "private fun handleFallbackNovaShortcut(",
            "// We cannot simply use modifierFlags"
        )

        assertTrue(
            "Game should keep a fallback shortcut state for adb/synthetic controller keyevents that are not attached to a recognized game controller",
            game.contains("fallbackNovaShortcutState")
        )
        assertTrue(
            "synthetic Nova shortcut handling must run before ignoreSynthEvents can discard adb keyevents",
            handleKeyDown.indexOf("handleFallbackNovaShortcut(event, down = true)") <
                handleKeyDown.indexOf("prefConfig!!.ignoreSynthEvents") &&
                handleKeyUp.indexOf("handleFallbackNovaShortcut(event, down = false)") <
                handleKeyUp.indexOf("prefConfig!!.ignoreSynthEvents")
        )
        assertTrue(
            "fallback Guide/Mode + Start/Menu should open the Command Center without requiring a GameInputDevice context",
            shortcutHandler.contains("NovaControllerShortcutAction.OPEN_QUICK_MENU") &&
                shortcutHandler.contains("showGameMenu(null)")
        )
        assertTrue(
            "fallback shortcuts should still support NovaHUD cycling for adb/controller smoke parity",
            shortcutHandler.contains("NovaControllerShortcutAction.CYCLE_NOVA_HUD") &&
                shortcutHandler.contains("cycleNovaHudFromController()")
        )
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.section(startMarker: String, endMarker: String): String =
        substring(indexOf(startMarker), indexOf(endMarker))
}
