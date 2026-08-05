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
    fun desktopSteamDecisionSheetUsesNovaGlassAndExplicitMirrorDesktopPlumbing() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")
        val serverHelper = readSource("src/main/java/com/papi/nova/utils/ServerHelper.kt")
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val streamConfiguration = readSource("src/main/java/com/papi/nova/nvstream/StreamConfiguration.kt")
        val nvHttp = readSource("src/main/java/com/papi/nova/nvstream/http/NvHTTP.kt")
        val chrome = readSource("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt")
        val decisionSheet = detail.section(
            "private fun showDesktopSteamLaunchDecision(",
            "private fun modeLabel("
        )
        val launchOptions = detail.section(
            "onLaunchOptionSelected = { option ->",
            "onDismissLaunchOptions ="
        )

        assertTrue(
            "desktop Steam active policy should open a Nova-themed Compose bottom sheet, not a legacy square AlertDialog",
            decisionSheet.contains("BottomSheetDialog(requireContext(), theme)") &&
                decisionSheet.contains("NovaDesktopSteamLaunchDecisionContent(") &&
                !decisionSheet.contains("AlertDialog.Builder")
        )
        assertTrue(
            "decision sheet should offer explicit Private Stream, Mirror Desktop, and Cancel actions",
            decisionSheet.contains("nova_desktop_steam_private_stream") &&
                decisionSheet.contains("nova_desktop_steam_mirror_desktop") &&
                decisionSheet.contains("nova_desktop_steam_cancel")
        )
        assertTrue(
            "Launch Options must not bypass desktop-Steam safety for selected private headless launches",
            launchOptions.contains("usesVirtualDisplay = option.usesVirtualDisplay") &&
                launchOptions.contains("showDesktopSteamLaunchDecision(") &&
                launchOptions.contains("onForcePrivateAfterSteamClose = { launchSelected(mirrorDesktop = false, forcePrivateAfterSteamClose = true) }")
        )
        assertTrue(
            "Mirror Desktop must be carried as an explicit launch override through the stream launch path",
            detail.contains("mirrorDesktop = true") &&
                serverHelper.contains("Game.EXTRA_MIRROR_DESKTOP") &&
                game.contains("EXTRA_MIRROR_DESKTOP") &&
                game.contains(".setMirrorDesktop(mirrorDesktop)") &&
                game.contains(".setForcePrivateAfterSteamClose(forcePrivateAfterSteamClose)") &&
                streamConfiguration.contains("fun setMirrorDesktop(enable: Boolean)") &&
                streamConfiguration.contains("fun setForcePrivateAfterSteamClose(enable: Boolean)") &&
                nvHttp.contains("&mirrorDesktop=") &&
                nvHttp.contains("&launchMode=mirror_desktop") &&
                serverHelper.contains("forcePrivateAfterSteamClose = forcePrivateAfterSteamClose")
        )
    }

    @Test
    fun launchFailureAndDesktopSteamActionsUseNovaThemedFlow() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")
        val serverHelper = readSource("src/main/java/com/papi/nova/utils/ServerHelper.kt")
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val nvHttp = readSource("src/main/java/com/papi/nova/nvstream/http/NvHTTP.kt")
        val chrome = readSource("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt")
        val errorSection = game.section(
            "var dialogText:String = getResources().getString(R.string.conn_error_msg)",
            "private fun showNovaLaunchIssueSheet"
        )

        assertTrue(
            "desktop Steam sheet should expose an explicit force-private path that closes desktop Steam before private launch",
            detail.contains("onForcePrivateAfterSteamClose") &&
                detail.contains("nova_desktop_steam_force_private") &&
                serverHelper.contains("Game.EXTRA_FORCE_PRIVATE_AFTER_STEAM_CLOSE") &&
                game.contains("EXTRA_FORCE_PRIVATE_AFTER_STEAM_CLOSE") &&
                nvHttp.contains("closeDesktopSteamForPrivate=1") &&
                nvHttp.contains("launchMode=force_private_stream")
        )
        assertTrue(
            "Game connection failures should route through the Nova themed launch issue drawer instead of legacy square Dialog.displayDialog",
            game.contains("showNovaLaunchIssueSheet(") &&
                ! errorSection.contains("Dialog.displayDialog(")
        )
        assertTrue(
            "Launch issue drawer must use shared transparent Nova glass sheet chrome so the old bottom-sheet theme background cannot peek out as a clipped bump",
            game.contains("NovaSheetChrome.applyBottomSheetChrome(") &&
                game.contains("NovaSheetChrome.createSheetBackground(") &&
                !game.contains("setBackgroundColor(Color.rgb(18, 22, 28))")
        )
        assertTrue(
            "Nova drawers should let content scroll down without minimizing the whole sheet; only the top handle strip may drag-dismiss",
            detail.contains("behavior.isDraggable = false") &&
                detail.contains("novaSheetHandleDrag") &&
                detail.contains("onSheetHandleDismiss") &&
                syncSheetGestureIsLocked() &&
                chrome.contains("isDraggable = false") &&
                chrome.contains("attachHandleDragToDismiss") &&
                game.contains("NovaSheetChrome.attachHandleDragToDismiss(handle, sheet)")
        )
    }


    @Test
    fun transientPopupsUseQuietThemeAwareChromeInsteadOfPurpleSnackbarCards() {
        val snackbar = readSource("src/main/java/com/papi/nova/ui/NovaSnackbar.kt")
        val game = readSource("src/main/java/com/papi/nova/Game.kt")

        assertTrue(
            "Nova Snackbar should use active NovaThemeManager glass tokens, not hardcoded elevated purple card colors",
            snackbar.contains("NovaThemeManager.getDialogBackgroundColor(activity)") &&
                snackbar.contains("NovaThemeManager.getTextPrimaryColor(activity)") &&
                snackbar.contains("NovaThemeManager.getAccentColor(activity)") &&
                !snackbar.contains("setBackgroundTint(activity.getColor(R.color.nova_bg_elevated))")
        )
        assertTrue(
            "Streaming transient messages should not stack Android toast/purple card popups over the launch drawer/overlay",
            game.contains("showQuietStreamTransientMessage") &&
                game.contains("NovaSnackbar.showQuiet") &&
                !game.section("override fun displayTransientMessage", "override fun rumble").contains("Toast.makeText")
        )
    }

    @Test
    fun composeBottomSheetsUseThemeAwareGlassHostInsteadOfStaticOldThemeInset() {
        val gameDetailSheet = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")
        val syncSheet = readSource("src/main/java/com/papi/nova/ui/NovaPolarisSyncSheet.kt")

        assertTrue(
            "Game detail sheet must clear/style the Material host with shared Nova glass chrome so bottom/nav inset gaps do not show old static blue chrome",
            gameDetailSheet.contains("NovaSheetChrome.applyBottomSheetChrome(bottomSheetDialog, contentView)") &&
                gameDetailSheet.contains("NovaSheetChrome.createSheetBackground(requireContext())") &&
                !gameDetailSheet.contains("sheet.setBackgroundResource(sheetBackgroundRes())")
        )
        assertTrue(
            "Polaris sync sheet must use the same theme-aware host chrome instead of static nova_sheet_bg inset background",
            syncSheet.contains("NovaSheetChrome.applyBottomSheetChrome(bottomSheetDialog, contentView)") &&
                !syncSheet.contains("sheet.setBackgroundResource")
        )
    }

    @Test
    fun virtualLaunchPreflightUsesHostVirtualDisplayContractConstants() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")
        val trampoline = readSource("src/main/java/com/papi/nova/ShortcutTrampoline.kt")
        val displayMode = readSource("src/main/java/com/papi/nova/api/PolarisStreamDisplayMode.kt")

        assertTrue(displayMode.contains("fun preflightModeForLaunch("))
        assertTrue(displayMode.contains("PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY"))
        assertTrue(displayMode.contains("PolarisClientSettings.MODE_HEADLESS_STREAM"))
        assertTrue(detail.contains("PolarisStreamDisplayMode.preflightModeForLaunch(usesVirtualDisplay, clientSettings)"))
        assertTrue(trampoline.contains("PolarisStreamDisplayMode.preflightModeForLaunch(withVirtualDisplay, clientSettings)"))
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
                applyPreflight.contains("syncShortcutLaunchPreflightSettings(apiClient, withVirtualDisplay, clientSettings)") &&
                applyPreflight.contains("apiClient.getClientSettings()") &&
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
        val refresh = activity.section(
            "private fun refreshActiveSession(",
            "private fun scheduleActiveSessionFollowUpRefreshes("
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
            onResume.contains("refreshActiveSession(scheduleFollowUps = true)") &&
                refresh.contains("val generation = beginActiveSessionRefresh()") &&
                refresh.contains("if (consumeLocalSessionEndSignal())") &&
                refresh.indexOf("val generation = beginActiveSessionRefresh()") <
                    refresh.indexOf("if (consumeLocalSessionEndSignal())")
        )
        assertTrue(
            "initial session refresh should consume a local End marker before querying Polaris",
            refresh.contains("if (consumeLocalSessionEndSignal())") &&
                refresh.contains("activeSession = null") &&
                refresh.contains("clearOnly = true") &&
                refresh.contains("generation = generation") &&
                refresh.indexOf("if (consumeLocalSessionEndSignal())") <
                    refresh.indexOf("queryActiveSession()")
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
            "cold start should schedule delayed refreshes independently of game-library loading",
            activity.contains("setContentView(content)\n        refreshActiveSession(scheduleFollowUps = true)") &&
                refresh.contains("if (published && scheduleFollowUps && refreshed != null)") &&
                refresh.contains("generation = generation")
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
    fun displayPlannerAndPostSessionReportStayControllerFirstAndLowNoise() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")
        val quickContent = readSource("src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt")
        val planner = readSource("src/main/java/com/papi/nova/ui/NovaDisplayResolutionPlanner.kt")
        val optionSheet = detail.section(
            "private fun NovaLaunchOptionsSheet(",
            "@Composable\nprivate fun NovaProfilePreferenceSheet"
        )

        assertTrue(
            "Launch Options should switch to Polaris display planner rows when display_planner is advertised",
            detail.contains("game.displayPlanner") &&
                detail.contains("NovaDisplayResolutionPlanner.from(") &&
                detail.contains("NovaDisplayResolutionPlanner.buildLaunchOptimizationOverride(")
        )
        assertTrue(
            "Planner choices should keep DPAD focus on meaningful cards rather than redundant Press A badges",
            optionSheet.contains("NovaFocusableCard(") &&
                optionSheet.contains("option.caption") &&
                !optionSheet.contains("Press A") &&
                planner.contains("takeUnless { it.equals(\"Press A\", ignoreCase = true) }")
        )
        assertTrue(
            "Post-session recovery report should surface quality, issue, next launch, and recovery copy in Command Center",
            quickContent.contains("NovaQuickMenuPostSessionReportCard") &&
                quickContent.contains("report.qualityLine") &&
                quickContent.contains("report.issueLine") &&
                quickContent.contains("report.nextLaunchLine") &&
                quickContent.contains("report.recoveryLine")
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
    fun gameKeepsSingleSessionProgressOverlayInstanceForStartupLifecycle() {
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val occurrences = game.split("SessionProgressOverlay(this)").size - 1
        val startup = game.section(
            "setContentView(R.layout.activity_game)",
            "appName = this@Game.getIntent().getStringExtra(EXTRA_APP_NAME)"
        )
        val polarisSetup = game.section(
            "// Nova: set up Polaris integration without blocking stream startup on REST probes.",
            "if (appId == StreamConfiguration.INVALID_APP_ID)"
        )

        assertTrue(
            "Game should create one SessionProgressOverlay instance; replacing it later leaves the first shown overlay orphaned",
            occurrences == 1 &&
                startup.contains("novaProgressOverlay = com.papi.nova.ui.SessionProgressOverlay(this)") &&
                !polarisSetup.contains("novaProgressOverlay = com.papi.nova.ui.SessionProgressOverlay(this)")
        )
    }

    @Test
    fun polarisEventSourceDoesNotReshowStartupOverlayAfterStreamIsActive() {
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val eventSource = game.section(
            "private fun startNovaEventSourceIfSupported()",
            "private fun schedulePolarisLiveSessionStatusRefresh"
        )

        assertTrue(
            "Polaris SSE startup should not resurrect the session progress overlay once native connectionStarted made the stream active",
            eventSource.contains("if (!connected && !isStreamActive)") &&
                eventSource.indexOf("if (!connected && !isStreamActive)") < eventSource.indexOf("novaProgressOverlay") &&
                eventSource.contains("show()")
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


    @Test
    fun virtualDisplayUnavailableCopyUsesHostVirtualDisplayLanguageAndReason() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")
        val strings = readSource("src/main/res/values/strings.xml")

        assertTrue(strings.contains("nova_library_virtual_display_unavailable_title") && strings.contains("Host Virtual Display is not ready"))
        assertTrue(strings.contains("nova_library_virtual_display_unavailable_body") && strings.contains("Polaris says this host cannot start a virtual-display stream right now. Nova will use Private Stream instead."))
        assertTrue(strings.contains("nova_library_virtual_display_unavailable_reason_format") && strings.contains("Reason: %1"))
        assertTrue(detail.contains("nova_library_virtual_display_unavailable_body"))
        assertTrue(detail.contains("nova_library_virtual_display_unavailable_reason_format"))
        assertTrue(detail.contains("virtualDisplayUnavailableReason"))
    }

    @Test
    fun androidExternalDisplayCopyIsSeparateFromHostVirtualDisplay() {
        val preferences = readSource("src/main/res/xml/preferences.xml")
        val strings = readSource("src/main/res/values/strings.xml")

        assertTrue(preferences.contains("checkbox_enable_fullexdisplay"))
        assertTrue(strings.contains("title_fullexdisplay_mode"))
        assertTrue(strings.contains("Use Android external display"))
        assertTrue(strings.contains("Show the stream on an Android-connected display while keeping Nova controls on this device. This is separate from Polaris Host Virtual Display."))
    }

    @Test
    fun novaLaunchAndSessionStringsUsePlayerLifecycleLanguage() {
        val strings = readSource("src/main/res/values/strings.xml")

        assertTrue(
            "launch mode copy should make Private Stream the default, demote GPU-native to a capability/status, and treat desktop mirroring as advanced",
            strings.contains("<string name=\"nova_library_launch_headless\">Private stream</string>") &&
                strings.contains("nova_library_launch_virtual_display" + 34.toChar() + ">Host Virtual Display</string>") &&
                strings.contains("nova_library_launch_desktop_display" + 34.toChar() + ">Mirror Desktop</string>") &&
                strings.contains("nova_library_launch_gpu_native_test" + 34.toChar() + ">Private Stream (GPU-native)</string>") &&
                strings.contains("private stream for this launch") &&
                strings.contains("GPU-native appears in Command Center as a capture path")
        )
        assertTrue(
            "session lifecycle copy should distinguish disconnecting the client from ending the running host session",
            strings.contains("<string name=\"nova_library_resume_ready\">Game still running</string>") &&
                strings.contains("<string name=\"applist_menu_resume\">Resume stream</string>") &&
                strings.contains("<string name=\"applist_menu_watch\">Watch stream</string>") &&
                strings.contains("<string name=\"game_menu_disconnect\">Disconnect</string>") &&
                strings.contains("<string name=\"nova_quick_menu_end_stream\">End session</string>") &&
                strings.contains("<string name=\"applist_menu_quit\">End session</string>")
        )
    }

    @Test
    fun gameReturnsToLibraryWhenPolarisReportsHostSessionEnded() {
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val hostEnded = game.section(
            "private fun handlePolarisHostSessionEnded(",
            " fun disconnect() {"
        )

        assertTrue(
            "resilience give-up should finish the Game activity when Polaris reports the host session is gone",
            game.contains("ConnectionResilienceManager(") &&
                game.contains("handlePolarisHostSessionEnded()") &&
                hostEnded.contains("hostSessionEnded = true") &&
                hostEnded.contains("markLocalSessionEnd()") &&
                hostEnded.contains("finish()")
        )
        assertTrue(
            "SSE terminal events should use the same host-ended teardown path after Nova observes a current-session event",
            game.contains("polarisSseSawCurrentSessionEvent") &&
                game.contains("PolarisSessionEvents.isCurrentSessionEvent(event, state)") &&
                game.contains("PolarisSessionEvents.shouldFinishGameActivity(event, state, polarisSseSawCurrentSessionEvent)") &&
                game.contains("handlePolarisHostSessionEnded()")
        )
        assertTrue(
            "host-ended teardown should stop background-resume state and shut down resilience executor",
            hostEnded.contains("stopBackgroundResumeWindow()") &&
                hostEnded.contains("novaResilienceManager?.shutdown()") &&
                !hostEnded.contains("prepareBackgroundResumeWindow()")
        )
    }

    @Test
    fun gameStreamSurfaceProvidesAccessibilityNodeForDeviceSmokeAutomation() {
        val container = readSource("src/main/java/com/papi/nova/ui/StreamContainer.kt")
        val strings = readSource("src/main/res/values/strings.xml")

        assertTrue(
            "Game stream should expose a stable accessibility node so adb UI automation can identify the foreground stream surface",
            container.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES") &&
                container.contains("contentDescription = context.getString(R.string.nova_stream_surface_accessibility_label)") &&
                strings.contains("nova_stream_surface_accessibility_label")
        )
        assertTrue(
            "StreamContainer should keep the container accessible and hide the raw SurfaceView child from traversal",
            container.contains("child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO")
        )
    }

    @Test
    fun drawerLaunchAppliesPreflightStreamModeToSettingsAndIntent() {
        val activity = readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val launchGame = activity.section(
            "private fun launchGame(",
            "private fun resumeActiveSession("
        )

        assertTrue(
            "drawer launch should derive effective stream mode from preflight optimization before syncing settings",
            launchGame.contains("val launchResolution = StreamSyncManager.resolveAutoSafeResolution(") &&
                launchGame.contains("val launchFps = StreamSyncManager.resolveAutoSafeTargetFps(") &&
                launchGame.indexOf("val launchResolution") < launchGame.indexOf("apiClient.updateClientSettings(")
        )
        assertTrue(
            "client settings and Game intent should use launchResolution and launchFps instead of saved fps only",
            launchGame.contains("launchResolution.width") &&
                launchGame.contains("launchResolution.height") &&
                launchGame.contains("launchFps") &&
                launchGame.contains("launchFps,")
        )
        val launchSetup = game.section(
            "watchOnlyRequested = this@Game.getIntent().getBooleanExtra(EXTRA_WATCH_ONLY, false)",
            "decoderRenderer = MediaCodecDecoderRenderer("
        )
        assertTrue(
            "Game should read explicit stream dimensions before selecting the decoder resolution",
            launchSetup.indexOf("watchStreamWidth = this@Game.getIntent().getIntExtra(EXTRA_STREAM_WIDTH, 0)") <
                launchSetup.indexOf("if (watchStreamWidth > 0 && watchStreamHeight > 0)")
        )
        assertTrue(
            "Game should honor explicit stream FPS extras for normal launch paths, not watch-only paths",
            game.contains("var explicitStreamFpsOverride:Boolean = watchStreamFps > 0f") &&
                game.contains("Nova: Launch using explicit stream FPS") &&
                game.indexOf("watchStreamFps = this@Game.getIntent().getFloatExtra(EXTRA_STREAM_FPS, 0f)") <
                    game.indexOf("var explicitStreamFpsOverride:Boolean = watchStreamFps > 0f")
        )
    }

    @Test
    fun gameDetailPreflightPreservesExplicitPolarisNonVirtualMode() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")
        val preflight = detail.section(
            "private fun syncLaunchPreflightSettings(",
            "private fun showPreflightReview("
        )

        assertTrue(
            "Game detail preflight must use the full Polaris stream display mode helper, not collapse every non-virtual launch to headless_stream",
            preflight.contains("PolarisStreamDisplayMode.preflightModeForLaunch") &&
                !preflight.contains("if (usesVirtualDisplay) \"host_virtual_display\" else \"headless_stream\"")
        )
    }

    @Test
    fun libraryAndShortcutLaunchPreflightPreservePolarisNonVirtualMode() {
        val library = readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val shortcut = readSource("src/main/java/com/papi/nova/ShortcutTrampoline.kt")

        assertTrue(
            "Library launch should preserve the selected full Polaris stream display mode",
            library.contains("PolarisStreamDisplayMode.preflightModeForLaunch") &&
                !library.contains("if (withVirtualDisplay) \"host_virtual_display\" else \"headless_stream\"")
        )
        assertTrue(
            "Shortcut launch should preserve the selected full Polaris stream display mode",
            shortcut.contains("PolarisStreamDisplayMode.preflightModeForLaunch") &&
                !shortcut.contains("if (withVirtualDisplay) \"host_virtual_display\" else \"headless_stream\"")
        )
    }

    @Test
    fun steamLaunchSelectionDoesNotDismissGameDetailOrStartStream() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")
        val selection = detail.section(
            "onSteamLaunchModeSelected = { selected ->",
            "},\n                    onDismissSteamLaunchModeOptions"
        )

        assertTrue(!selection.contains("dismiss()"))
        assertTrue(!selection.contains("onLaunch?.invoke"))
        assertTrue(selection.contains("apiClient.setSteamLaunchMode"))
    }

    @Test
    fun steamLaunchModeUpdateConfirmsHostModeAndStaysInline() {
        val api = readSource("src/main/java/com/papi/nova/api/PolarisApiClient.kt")
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")

        assertTrue(api.contains("fun setSteamLaunchMode(gameId: String, mode: String): String?"))
        assertTrue(api.contains("json.optString(\"mode\", normalizedMode)"))
        assertTrue(detail.contains("steamLaunchOptionsState = steamLaunchModeOptionsState(currentGame)"))
        assertTrue(detail.contains("confirmedMode != null"))
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)


    private fun syncSheetGestureIsLocked(): Boolean {
        val sync = readSource("src/main/java/com/papi/nova/ui/NovaPolarisSyncSheet.kt")
        return sync.contains("isDraggable = false")
    }

    private fun String.section(startMarker: String, endMarker: String): String =
        substring(indexOf(startMarker), indexOf(endMarker))
}
