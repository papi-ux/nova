package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guards for the Command Center, the stream HUD and the companion deck.
 *
 * Moved verbatim out of NovaComposeSourceGuardTest, so each migration group owns the guards
 * on its own files.
 */
class NovaCommandCenterSourceGuardTest {
    @Test
    fun commandCenterClosesOnlyWhenSomethingElseNeedsTheScreen() {
        val menu = readNovaQuickMenu()

        // A change made from the Command Center used to cost the Command Center. Toggling the
        // on-screen controller or sending the clipboard changes nothing about who owns the screen,
        // and Mouse Mode, More Keys and More Controls are pages pushed inside it, so those stay
        // open. The ones that close hand the screen or the input to something else, wait for the
        // stream to hold focus again before they act, and each says why.
        val staysOpen = mapOf(
            "CONTROLLER" to "game.toggleVirtualController()",
            "PASTE_CLIPBOARD" to "game.sendClipboard(true)",
            "MOUSE_MODE" to "surfaces.panel.push(mouseModePage",
            "MORE_KEYS" to "surfaces.panel.push(keysPage(",
            "MORE_CONTROLS" to "surfaces.panel.push(moreControlsPage(",
        )
        val closes = mapOf(
            "KEYBOARD" to "game.toggleFullKeyboard()",
            "PLAYERS" to "game.reassignPlayers()",
            "ROTATE_SCREEN" to "game.rotateScreen()",
        )

        for ((action, call) in staysOpen + closes) {
            val branch = quickMenuActionBranch(menu, action)
            assertTrue("$action must still be handled from the Command Center", branch.contains(call))
            val closesPanel = branch.contains("dismiss()") || branch.contains("closeThenOnStream(")
            if (action in staysOpen) {
                assertFalse(
                    "$action is a setting or a page of the Command Center: it must stay open so a " +
                        "change does not cost the menu and the place in it",
                    closesPanel,
                )
            } else {
                assertTrue(
                    "$action hands the screen or the input to something else, so it must close " +
                        "the Command Center and act once the stream holds focus again",
                    branch.contains("closeThenOnStream(menu)"),
                )
                assertTrue(
                    "$action closes the Command Center, so the reason must be written next to it",
                    branch.lines().any { it.trim().startsWith("//") },
                )
            }
        }
    }

    /** One `when` branch of the Command Center's action handlers, without the ones after it. */
    private fun quickMenuActionBranch(menu: String, action: String): String {
        val marker = "NovaQuickMenuActionId.$action -> {"
        val start = menu.indexOf(marker)
        assertTrue("Missing Command Center action branch: $action", start >= 0)
        assertEquals(
            "One handler per action, so this guard reads the branch it means to",
            1,
            Regex(Regex.escape(marker)).findAll(menu).count(),
        )
        val ends = listOf(
            menu.indexOf("NovaQuickMenuActionId.", start + marker.length),
            menu.indexOf("else -> Unit", start + marker.length),
        ).filter { it >= 0 }
        val end = ends.minOrNull() ?: menu.length
        return menu.substring(start, end)
    }

    @Test
    fun commandCenterRendersExplanationSourceAndCapabilityExactActions() {
        val menu = readNovaQuickMenu()
        val content = readNovaQuickMenuContent()

        assertTrue(
            "Doctor explanation must stay secondary, informational, visible, and accessibility-visible",
            content.contains("diagnosis.aiExplanation") &&
                content.contains("nova_quick_menu_doctor_ai_explanation") &&
                content.contains("diagnosis.informationalSource") &&
                content.contains("supportingLine = supportingLine") &&
                content.contains("text = supportingLine") &&
                content.contains("listOfNotNull(action.label, action.chip?.label, supportingLine")
        )
        assertTrue(
            "Doctor capability chips must distinguish all four action classes using localized labels",
            content.contains("NovaQuickMenuDoctorCapability.AUTO_FIX") &&
                content.contains("NovaQuickMenuDoctorCapability.RUN_TRIAL") &&
                content.contains("NovaQuickMenuDoctorCapability.RECHECK") &&
                content.contains("NovaQuickMenuDoctorCapability.MANUAL") &&
                content.contains("nova_quick_menu_doctor_capability_auto_fix") &&
                content.contains("nova_quick_menu_doctor_capability_run_trial") &&
                content.contains("nova_quick_menu_doctor_capability_recheck") &&
                content.contains("nova_quick_menu_doctor_capability_manual") &&
                !content.contains("label = if (diagnosis.actionExecutable) \"One click\"")
        )
        assertTrue(
            "deprecated next-launch recovery must have no executable confirmation path",
            !menu.contains("apply_recovery_profile_next_launch") &&
                !menu.contains("nova_quick_menu_doctor_confirm_recovery_message") &&
                menu.contains("if (doctor.requiresConfirmation)") &&
                menu.contains("game.copyNovaHudDiagnostics()")
        )
        assertTrue(
            "read-only Recheck must use owner observation authority instead of host-tuning authority",
            menu.contains("fun canExecuteDoctorAction(") &&
                menu.contains("status.ownedByClient && !status.isViewer") &&
                menu.contains("!canExecuteDoctorAction(latestStatus, latestDoctor)") &&
                menu.contains("!canExecuteDoctorAction(status, doctor)")
        )
    }

    @Test
    fun commandCenterRequestsInitialFocusForDpadNavigationOnOpen() {
        val quickMenuContent = readNovaQuickMenuContent()
        val quickMenuHost = readSource("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt")
        val panelWindow = readSource("src/main/java/com/papi/nova/ui/panel/NovaPanelWindow.kt")
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val controllerHandler = readSource("src/main/java/com/papi/nova/binding/input/ControllerHandler.kt")
        val content = quickMenuContent.section(
            "fun NovaPageScope.NovaQuickMenuContent(",
            "@Composable\nprivate fun NovaPageScope.NovaQuickMenuHeader("
        )
        val sessionStrip = quickMenuContent.section(
            "private fun NovaQuickMenuSessionStrip(",
            "@Composable\nprivate fun NovaQuickMenuPostSessionReportCard("
        )
        val closeButton = quickMenuContent.section(
            "private fun NovaQuickMenuCloseButton(",
            "@Composable\nprivate fun NovaQuickMenuDiagnosisCard("
        )

        assertTrue(
            "Command Center should land on the always-present session strip while asynchronous Doctor data is loading, rather than requesting focus from a disabled card; the page host focuses it one frame after the page opens",
            content.contains("NovaQuickMenuSessionStrip(ui, Modifier.novaInitialFocus())") &&
                sessionStrip.contains("modifier: Modifier") &&
                sessionStrip.contains(".focusable()")
        )
        assertFalse(
            "the Close button must not carry the initial focus",
            closeButton.contains("novaInitialFocus") || closeButton.contains("focusRequester")
        )
        assertTrue(
            "the Command Center opens in the panel window, which holds focus in touch mode and translates A and B for every element through the key gate",
            quickMenuHost.contains("surfaces.open(root, NovaEdge.Start)") &&
                panelWindow.contains("prepareControllerWindow(window, content)") &&
                panelWindow.contains("keyGate.dispatch(event")
        )
        assertFalse(
            "the Command Center translates no keys of its own any more",
            quickMenuHost.contains("setOnKeyListener") || quickMenuHost.contains("KeyEvent.KEYCODE_BUTTON_A")
        )
        assertTrue(
            "Command Center dismissal must relinquish its focusable window and restore the stream input target",
            panelWindow.contains("window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)") &&
                panelWindow.contains("placement.game.restoreStreamInputAfterModalDismissal()") &&
                game.contains("fun restoreStreamInputAfterModalDismissal()") &&
                game.contains("val viewFocusRestored = target.requestFocus()")
        )
        assertTrue(
            "stream-window focus loss must clear physical shortcut state because chord releases may arrive at the modal window",
            game.contains("controllerHandler?.resetNovaShortcutStates()") &&
                controllerHandler.contains("fun resetNovaShortcutStates()") &&
                controllerHandler.contains("inputDeviceContexts.valueAt(i).novaShortcutState.reset()")
        )
    }

    @Test
    fun streamHudCompactTextDoesNotInheritBodyLineHeight() {
        val source = readNovaStreamHudContent()
        val fact = source.section(
            "private fun HudFact(",
            "@Composable\nprivate fun HudColumnRule("
        )
        val valueText = source.section(
            "private fun HudValueText(",
            "@Composable\nprivate fun HudCompactText("
        )
        val compactText = source.section(
            "private fun HudCompactText(",
            "@Composable\nprivate fun HudStatusDot("
        )

        assertTrue(
            "a Debug fact keeps its label and value on one baseline, so a 7 sp label and a 10 sp value line up instead of the value sitting low",
            fact.split(".alignByBaseline()").size == 3
        )
        assertTrue(
            "Debug fact values should not inherit Material body line height",
            fact.contains("lineHeight = 12.sp")
        )
        assertTrue(
            "Debug fact labels should not inherit Material body line height",
            fact.contains("lineHeight = 8.sp")
        )
        assertTrue(
            "large HUD values should use a line height sized to their font",
            valueText.contains("lineHeight = (size + 2).sp")
        )
        assertTrue(
            "compact HUD values should not inherit Material body line height",
            compactText.contains("lineHeight = 12.sp")
        )
    }

    @Test
    fun streamHudDragUsesRawTouchCoordinatesAndPreservesPositionAcrossModeCycles() {
        val source = readSource("src/main/java/com/papi/nova/ui/NovaStreamHud.kt")
        val touchHandler = source.section(
            "private fun setupTouchHandler(view: View)",
            "fun cycleMode()"
        )
        val cycleMode = source.section(
            "fun cycleMode()",
            "fun updateFromPerfSample("
        )

        assertTrue(
            "Nova HUD drag should be handled by the HUD view itself using raw display coordinates so Retroid touch swipes move the floating overlay instead of the stream surface",
            touchHandler.contains("view.setOnTouchListener") &&
                touchHandler.contains("event.rawX") &&
                touchHandler.contains("event.rawY") &&
                touchHandler.contains("viewStartX = touchedView.x") &&
                touchHandler.contains("viewStartY = touchedView.y") &&
                touchHandler.contains("touchedView.x = viewStartX + dx") &&
                touchHandler.contains("touchedView.y = viewStartY + dy") &&
                touchHandler.contains("DRAG_THRESHOLD")
        )
        assertTrue(
            "tap-to-cycle must not reset a user-dragged HUD back to top-left. This used to " +
                "also pin the layoutParams reassignment that cycleMode did to change the " +
                "width between modes -- but every mode is WRAP_CONTENT, so it re-laid out " +
                "to the width it already had. The save and restore is what preserves the " +
                "position, and it is what is pinned.",
            cycleMode.contains("val savedX = view.x") &&
                cycleMode.contains("val savedY = view.y") &&
                cycleMode.contains("view.post") &&
                cycleMode.contains("view.x = savedX") &&
                cycleMode.contains("view.y = savedY")
        )
    }

    @Test
    fun streamHudForwardsPlainTapsToTheStreamInsteadOfKeepingThem() {
        val source = readSource("src/main/java/com/papi/nova/ui/NovaStreamHud.kt")
        val touchHandler = source.section(
            "private fun setupTouchHandler(view: View)",
            "private fun forwardTapToStream("
        )
        assertTrue(
            "a press that neither dragged the HUD nor held it was aimed at the game " +
                "underneath. The gesture's DOWN has to be claimed to recognise a drag or " +
                "long-press at all, so the tap is replayed beneath the HUD instead of " +
                "consumed by it — and cycling modes stays on the Command Center action " +
                "that already does it",
            touchHandler.contains("else -> forwardTapToStream(event)") &&
                !touchHandler.contains("cycleMode()") &&
                touchHandler.contains("if (forwardingTap)")
        )
        assertTrue(
            "the replay dispatches through the decor with the listener standing down, " +
                "and recycles what it obtains",
            source.contains("root.dispatchTouchEvent(down)") &&
                source.contains("root.dispatchTouchEvent(up)") &&
                source.contains("down.recycle()") &&
                source.contains("up.recycle()")
        )
    }

    @Test
    fun commandCenterDragSnapsThroughOneCollectorNotACoroutinePerMove() {
        val content = readNovaQuickMenuContent()
        val frame = readSource("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt")
        assertTrue(
            "dragging the drawer used to launch a coroutine per pointer-move event, sixty to a " +
                "hundred and twenty a second, each stopping whatever the one before it started. " +
                "The panel frame the Command Center now sits in keeps the single snapshotFlow " +
                "collector that snaps it at a frame's pace, and the page adds no drag of its own",
            frame.contains("snapshotFlow { if (dragging.value) dragProgress.floatValue else Float.NaN }") &&
                !content.contains("detectHorizontalDragGestures")
        )
    }

    @Test
    fun commandCenterUsesAnchoredLeftDrawerInsteadOfBottomSheet() {
        val quickMenu = readNovaQuickMenu()
        val content = readNovaQuickMenuContent()
        val pages = readSource("src/main/java/com/papi/nova/ui/NovaCommandCenterPages.kt")
        val frame = readSource("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt")
        val tokens = readSource("src/main/java/com/papi/nova/ui/panel/NovaPanelTokens.kt")

        assertFalse(
            "in-stream Command Center should not use a bottom sheet that floats high/off-center in landscape",
            quickMenu.contains("BottomSheetDialog") || quickMenu.contains("BottomSheetBehavior")
        )
        assertTrue(
            "the Command Center is the panel attached to the start edge, in the panel window, not a dialog of its own",
            quickMenu.contains("surfaces.open(root, NovaEdge.Start)") && !quickMenu.contains("Dialog(game")
        )
        assertTrue(
            "its root is a Wide panel, today's proven width on the RP6 and 560dp on a television",
            pages.contains("override val width: NovaPanelWidth get() = NovaPanelWidth.Wide")
        )
        // Which corners are rounded is the guarantee; the frame rounds only the inner edge.
        assertTrue(
            "the frame rounds the panel's inner edge only, with the drawer radius",
            frame.contains("NovaRadius.drawer")
        )
        assertFalse(
            "the page draws no panel, corner or handle of its own: the frame owns the container",
            content.contains("RoundedCornerShape(topEnd = NovaRadius.drawer") ||
                content.contains(".background(surfaces.panel)") ||
                content.contains("AccentHandle")
        )
        assertTrue(
            "over the stream the frame keeps the Command Center's tuned scrim",
            tokens.contains("const val StreamScrimAlpha = NovaInGameOverlayAlpha.CommandCenterScrim")
        )
        assertTrue(
            "Close invokes the same dismiss the scrim and B do",
            content.contains("onClick = callbacks.onDismiss") && quickMenu.contains("surfaces.panel.close()")
        )
    }

    @Test
    fun commandCenterDrawerUsesFingerTrackedHorizontalMotion() {
        val content = readNovaQuickMenuContent()
        val frame = readSource("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt")

        assertFalse(
            "the Command Center is not a canned AnimatedVisibility drawer; the page slides with the panel frame",
            content.contains("slideInHorizontally(") || content.contains("fun NovaQuickMenuDrawer(")
        )
        assertTrue(
            "the frame's motion is progress-aware: spring in, offset by progress, and follow the finger, dismissing past a threshold",
            frame.contains("Animatable(0f)") &&
                frame.contains("PanelSpring") &&
                frame.contains("offset {") &&
                frame.contains("NovaPanelMetrics.DismissFraction")
        )
    }

    @Test
    fun inGameOverlayNestedOpacityTokensAreSharedByCommandCenterAndHud() {
        val tokenPath = Path.of("src/main/java/com/papi/nova/ui/compose/NovaInGameOverlayTokens.kt")
        assertTrue(
            "Command Center and NovaHUD should share named nested opacity tokens instead of local magic alpha literals",
            Files.exists(tokenPath)
        )
        val tokens = readSource("src/main/java/com/papi/nova/ui/compose/NovaInGameOverlayTokens.kt")
        val commandCenter = readNovaQuickMenuContent()
        val hud = readNovaStreamHudContent()

        // One box style (2026-09-28): the Command Center's rows and cards rest as the one row tile
        // every panel draws, so the nested tile and control alphas it alone used are gone. The
        // scrim and the border stay shared with the HUD.
        assertTrue(
            "token file should name the overlay alpha contract for the scrim and borders",
            tokens.contains("object NovaInGameOverlayAlpha") &&
                tokens.contains("const val CommandCenterScrim") &&
                tokens.contains("const val Border") &&
                !tokens.contains("const val NestedTile") &&
                !tokens.contains("const val NestedControl")
        )
        val panelTokens = readSource("src/main/java/com/papi/nova/ui/panel/NovaPanelTokens.kt")
        assertTrue(
            "Command Center should draw its boxes as the panels' one row tile, with no alpha of its own, and use the frame's stream scrim, which is the Command Center scrim token",
            panelTokens.contains("const val StreamScrimAlpha = NovaInGameOverlayAlpha.CommandCenterScrim") &&
                panelTokens.contains("val novaRowRest: NovaRowRest") &&
                commandCenter.contains("rest = novaRowRest") &&
                !commandCenter.contains("NovaInGameOverlayAlpha.")
        )
        assertTrue(
            "NovaHUD should use its own literal outer opacity plus the same border and divider tokens",
            hud.contains(".background(surfaces.panel.copy(alpha = hudOpacityScale))") &&
                hud.contains("NovaInGameOverlayAlpha.Border") &&
                hud.contains("NovaInGameOverlayAlpha.AccentDivider")
        )
        assertFalse(
            "NovaHUD draws no box inside its panel: each fact's own fill stayed visible as a faint box when the HUD was made transparent, long after the panel under it had faded",
            hud.contains("NovaInGameOverlayAlpha.NestedControl") ||
                hud.contains("NovaInGameOverlayAlpha.NestedTile") ||
                hud.contains("surfaces.control")
        )
        assertFalse(
            "old local overlay alpha literals should be replaced by shared named tokens in the in-game overlay files",
            commandCenter.contains("surfaces.panel.copy(alpha = 0.96f)") ||
                commandCenter.contains("surfaces.tile.copy(alpha = 0.72f)") ||
                hud.contains("surfaces.panel.copy(alpha = 0.96f)") ||
                hud.contains("surfaces.control.copy(alpha = 0.82f)")
        )
    }

    @Test
    fun retroidLoneAppSwitchMapsToCommandCenterWithoutHijackingGenericMenu() {
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val controllerHandler = readSource("src/main/java/com/papi/nova/binding/input/ControllerHandler.kt")
        val shortcuts = readSource("src/main/java/com/papi/nova/binding/input/NovaControllerShortcutState.kt")

        val downShortcut = game.indexOf("handleFallbackNovaShortcut(event, down = true)")
        val downIgnore = if (downShortcut >= 0) {
            game.indexOf("prefConfig!!.ignoreSynthEvents && deviceId <= 0", downShortcut)
        } else {
            -1
        }
        val upShortcut = game.indexOf("handleFallbackNovaShortcut(event, down = false)")
        val upIgnore = if (upShortcut >= 0) {
            game.indexOf("prefConfig!!.ignoreSynthEvents && deviceId <= 0", upShortcut)
        } else {
            -1
        }

        assertTrue(
            "fallback shortcut handling must stay before ignoreSynthEvents so ADB app-owned shortcuts still work",
            downShortcut >= 0 && downIgnore > downShortcut &&
                upShortcut >= 0 && upIgnore > upShortcut
        )
        assertTrue(
            "synthetic fallback state should opt into lone KEYCODE_APP_SWITCH, not generic KEYCODE_MENU",
            game.contains("fallbackNovaShortcutState:NovaControllerShortcutState = NovaControllerShortcutState().apply") &&
                game.contains("loneAppSwitchOpensQuickMenu = true")
        )
        assertTrue(
            "Retroid built-in controller should be recognized by vendor/product before enabling lone app-switch",
            controllerHandler.contains("isRetroidPocketBuiltInController(context)") &&
                controllerHandler.contains("context.vendorId == 0x2022") &&
                controllerHandler.contains("context.productId == 0x3002") &&
                controllerHandler.contains("context.novaShortcutState.loneAppSwitchOpensQuickMenu = true")
        )
        assertTrue(
            "the lone app-switch path should open the same Command Center action as other shortcuts",
            shortcuts.contains("keyCode == KeyEvent.KEYCODE_APP_SWITCH && loneAppSwitchOpensQuickMenu") &&
                shortcuts.contains("NovaControllerShortcutAction.OPEN_QUICK_MENU")
        )
        assertFalse(
            "generic lone KEYCODE_MENU should not be promoted to single-button Command Center until hardware proves it is safe",
            shortcuts.contains("keyCode == KeyEvent.KEYCODE_MENU && loneAppSwitchOpensQuickMenu") ||
                shortcuts.contains("KeyEvent.KEYCODE_MENU && loneAppSwitchOpensQuickMenu")
        )
    }

    @Test
    fun commandCenterFirstPaintPutsSessionHealthThenItsExplanationBeforeTheDailyPanels() {
        val content = readNovaQuickMenuContent()
        val body = content.section(
            "fun NovaPageScope.NovaQuickMenuContent(",
            "@Composable\nprivate fun NovaPageScope.NovaQuickMenuHeader("
        )
        val header = content.section(
            "private fun NovaPageScope.NovaQuickMenuHeader(",
            "@Composable\nprivate fun NovaQuickMenuEndButton("
        )
        val endButton = content.section(
            "private fun NovaQuickMenuEndButton(",
            "@Composable\nprivate fun NovaQuickMenuHeaderButton("
        )
        val headerButton = content.section(
            "private fun NovaQuickMenuHeaderButton(",
            "@Composable\nprivate fun NovaQuickMenuCloseButton("
        )
        val closeButton = content.section(
            "private fun NovaQuickMenuCloseButton(",
            "@Composable\nprivate fun NovaQuickMenuDiagnosisCard("
        )

        val sessionStrip = body.indexOf("NovaQuickMenuSessionStrip(ui, Modifier.novaInitialFocus())")
        val pinnedKeys = body.indexOf("NovaQuickKeys(ui, { it.pinnedQuickKeys }, callbacks)")
        val quickKeysPanel = body.indexOf("NovaSectionLabel(quickKeysTitle)")
        val controlsPanel = body.indexOf("NovaSectionLabel(controlsTitle)")
        val sessionPanel = body.indexOf("NovaSectionLabel(sessionTitle)")
        val overlaysPanel = body.indexOf("NovaSectionLabel(overlaysTitle)")
        val diagnosisCard = body.indexOf("NovaQuickMenuDiagnosisCard(ui, callbacks)")
        val stabilityCard = body.indexOf("NovaQuickMenuStabilityCard(ui, callbacks)")
        val syncCard = body.indexOf("{ it.sync }")
        val advancedToggleCard = body.indexOf("{ it.advancedToggle }")
        val reportCard = body.indexOf("NovaQuickMenuPostSessionReportCard(ui)")

        assertTrue(
            "Command Center first paint should keep the session strip, which carries the health verdict, immediately after the header, with the three keys a handheld cannot press any other way right under it",
            sessionStrip >= 0 && pinnedKeys > sessionStrip && diagnosisCard > pinnedKeys
        )
        assertTrue(
            "the strip is a one-line verdict, so the Doctor's reading and the Stream card that explain it come next instead of three screens down",
            diagnosisCard in 0 until stabilityCard &&
                stabilityCard in 0 until overlaysPanel
        )
        assertTrue(
            "the sections a player adjusts follow: Overlays first because the HUD switch is the frequent tap, then Controls and Session, and the full Quick Keys grid last of them because its top three are already pinned under the strip",
            overlaysPanel in 0 until controlsPanel &&
                controlsPanel in 0 until sessionPanel &&
                sessionPanel in 0 until quickKeysPanel &&
                quickKeysPanel in 0 until syncCard
        )
        assertTrue(
            "sync and Advanced stay at the end",
            syncCard in 0 until advancedToggleCard
        )
        assertTrue(
            "the host safe profile is observational history and lives inside the expanded Advanced section, not in the first paint",
            reportCard > advancedToggleCard &&
                body.substring(advancedToggleCard, reportCard).contains("if (advancedExpanded) {")
        )
        assertTrue(
            "Command Center header should expose an explicit close affordance that invokes the same dismiss callback as scrim/back",
            header.contains("NovaQuickMenuCloseButton(callbacks") &&
                closeButton.contains("onClick = callbacks.onDismiss")
        )
        assertTrue(
            "Close is the primary header button: the menu opens on every Back press, so the safe action wears the accent while Disconnect stays quiet",
            closeButton.contains("primary = true") &&
                headerButton.contains("primary = false") &&
                headerButton.contains("destructive = false")
        )
        assertTrue(
            "End Session confirms in its own slot: a split with the hoisted state, and while it is armed Close and Disconnect make room for the pair",
            endButton.contains("NovaSplitConfirm(") &&
                endButton.contains("state = endSplit") &&
                header.contains("if (!armed) {")
        )
        // papi 2026-09-28, "some of the boxes are mismatched": End Session was a row-shaped confirm,
        // taller and squarer than the Close and Disconnect buttons beside it.
        assertTrue(
            "End Session sits in the header's row of buttons, so it is a button there: their height, their corners, its third of the row",
            endButton.contains("shape = NovaSplitShape.Button") &&
                endButton.contains("fillSlot = true") &&
                !endButton.contains("NovaSplitShape.Row")
        )
    }

    @Test
    fun commandCenterPanelsUseSectionHeadersForHierarchy() {
        val content = readNovaQuickMenuContent()
        val pages = readSource("src/main/java/com/papi/nova/ui/NovaCommandCenterPages.kt")

        // The sections are the panel's own section labels, in the accent chrome face, as every
        // other panel's are; the pill headers and nested boxes went with the drawer.
        assertTrue(content.contains("NovaSectionLabel(overlaysTitle)"))
        assertTrue(content.contains("NovaSectionLabel(quickKeysTitle)"))
        assertTrue(content.contains("NovaSectionLabel(controlsTitle)"))
        assertTrue(content.contains("NovaSectionLabel(sessionTitle)"))
        assertTrue(pages.contains("NovaSectionLabel(title)"))
        assertFalse(content.contains("private fun NovaQuickMenuSectionHeader("))
    }

    @Test
    fun commandCenterExposesInsertThroughExistingSpecialKeyTranslator() {
        val state = readSource("src/main/java/com/papi/nova/ui/NovaQuickMenuUiState.kt")
        val content = readNovaQuickMenuContent()
        val menu = readNovaQuickMenu()
        val keysPage = readSource("src/main/java/com/papi/nova/ui/NovaCommandCenterPages.kt")
        val strings = readSource("src/main/res/values/strings.xml")

        assertTrue(
            "Command Center Quick Keys should expose Insert for OptiScaler without hiding it behind a keyboard pairing workaround",
            state.contains("QUICK_INSERT") &&
                state.contains("R.string.game_menu_send_keys_insert")
        )
        val quickKeyBucket = content.substringAfter("NovaQuickMenuActionId.QUICK_ESC,", "")
            .substringBefore("-> onQuickKey(action.id)", "")
        assertTrue(
            "Insert quick key should route through the same Command Center quick-key callback bucket as the other special keys",
            quickKeyBucket.contains("NovaQuickMenuActionId.QUICK_INSERT,")
        )
        assertTrue(
            "Insert quick key should route through the existing Windows VK_INSERT translator path",
            menu.contains("NovaQuickMenuActionId.QUICK_INSERT -> keys(KeyboardTranslator.VK_INSERT)")
        )
        assertTrue(
            "the Keys page More Keys opens should also expose Insert",
            keysPage.contains("R.string.game_menu_send_keys_insert, KeyboardTranslator.VK_INSERT")
        )
        assertTrue(
            "Insert label should be public-resource backed like the other special keys",
            strings.contains("<string name=\"game_menu_send_keys_insert\">Insert</string>")
        )
    }

    @Test
    fun streamHudUsesCompactBoundedLabels() {
        val source = readNovaStreamHudContent()
        val debugHud = source.section(
            "private fun NovaStreamHudDebug(",
            "@Composable\nprivate fun NovaStreamHudPerformance("
        )
        val performanceHud = source.section(
            "private fun NovaStreamHudPerformance(",
            "@Composable\nprivate fun NovaStreamHudMinimal("
        )
        val minimalHud = source.section(
            "private fun NovaStreamHudMinimal(",
            "@Composable\nprivate fun NovaStreamHudSlim("
        )
        val slimHud = source.section(
            "private fun NovaStreamHudSlim(",
            "@Composable\nprivate fun HudPanel("
        )

        assertTrue(
            "debug HUD should use the shorter HUD-specific status label",
            debugHud.contains("text = state.autopilotHudLabel")
        )
        assertTrue(
            "debug HUD status label should have a max width so it cannot crowd the FPS label",
            debugHud.contains(".widthIn(max = 96.dp)")
        )
        assertTrue(
            "performance HUD should cap its overlay width while allowing narrow parents to constrain it",
            performanceHud.contains("modifier = modifier.widthIn(max = 320.dp)")
        )
        assertFalse(
            "performance leaves Live Tuning to Debug: its label (\"Tuning: On\") was cut to 42 dp there and said nothing at a glance",
            performanceHud.contains("state.autopilot")
        )
        assertTrue(
            "performance glues each fact to its label the way Slim does, instead of spreading four unlabeled columns across the panel",
            performanceHud.contains("HudSlimStat(\"RTT\", state.latencyLabel") &&
                !performanceHud.contains("Modifier.weight(1f)")
        )
        assertTrue(
            "performance HUD should use explicit compact line height for the status chip",
            performanceHud.contains("lineHeight = 11.sp")
        )
        assertFalse(
            "minimal HUD should stay casual: no bitrate readout",
            minimalHud.contains("state.bitrateLabel")
        )
        assertFalse(
            "minimal HUD should avoid sparkline density during casual play",
            minimalHud.contains("NovaHudSparkline")
        )
        assertTrue(
            "minimal HUD is Slim's pill with the frame rate and round trip: no tuning word, whose meaning the health bar's color already carries, and no fixed width with an empty middle",
            minimalHud.contains("cornerRadius = NovaRadius.pill") &&
                minimalHud.contains("HudSlimStat(\"RTT\", state.latencyLabel, state.latencyTone)") &&
                !minimalHud.contains("state.autopilot") &&
                !minimalHud.contains(".width(148.dp)")
        )
        assertTrue(
            "slim HUD is one pill that reads like MangoHud's bar: the health bar, the frame rate with its last minute drawn beside it, then decode, round trip, and bitrate with inline labels; no target, no autopilot text, no breadcrumb, no stacked tiles",
            slimHud.contains("cornerRadius = NovaRadius.pill") &&
                slimHud.contains("state.fpsLabel") &&
                slimHud.contains("NovaHudSparkline(") &&
                slimHud.contains("HudSlimStat(\"DEC\", state.decodeTimeLabel, state.decodeTone)") &&
                slimHud.contains("HudSlimStat(\"RTT\", state.latencyLabel, state.latencyTone)") &&
                slimHud.contains("HudSlimStat(\"BIT\", state.bitrateLabel") &&
                !slimHud.contains("HudMetric(") &&
                !slimHud.contains("HudTinyLabel(") &&
                !slimHud.contains("state.targetFpsLabel") &&
                !slimHud.contains("autopilot") &&
                !slimHud.contains("HudEventBreadcrumb")
        )
    }

    @Test
    fun streamHudDebugShowsTheLatencyBudgetAndFrameFlowTiles() {
        val source = readNovaStreamHudContent()
        val debugHud = source.section(
            "private fun NovaStreamHudDebug(",
            "@Composable\nprivate fun NovaStreamHudPerformance("
        )
        val performanceHud = source.section(
            "private fun NovaStreamHudPerformance(",
            "@Composable\nprivate fun NovaStreamHudMinimal("
        )

        assertTrue(
            "Debug answers 'how long does my panel take to decode', graded against the frame budget rather than a fixed number",
            debugHud.contains("HudFact(\"DEC\", state.decodeTimeLabel, state.decodeTone)")
        )
        assertTrue(
            "Debug shows host encode latency and incoming against rendered fps, the legacy text's remaining facts, so the legacy overlay can retire later",
            debugHud.contains("HudFact(\"HOST\", state.hostLatencyLabel)") &&
                debugHud.contains("HudFact(\"IN\", state.incomingFpsLabel)") &&
                debugHud.contains("HudFact(\"OUT\", state.renderedFpsLabel)")
        )
        assertTrue(
            "Debug keeps the network's facts: loss in the current window graded so zero is the only green, round-trip jitter, and the session's lost-frame count",
            debugHud.contains("HudFact(\"LOSS\", state.packetLossLabel, state.packetLossTone)") &&
                debugHud.contains("HudFact(\"JIT\", state.jitterLabel)") &&
                debugHud.contains("HudFact(\"DROPS\", state.framesLostLabel)")
        )
        val hostColumn = debugHud.indexOf("HudLayerColumn(host?.label")
        val netColumn = debugHud.indexOf("HudLayerColumn(net?.label")
        val clientColumn = debugHud.indexOf("HudLayerColumn(client?.label")
        assertTrue(
            "Debug puts each fact under the layer it belongs to, headed by that layer's health, so the layer that went amber and the numbers that explain it line up",
            hostColumn in 0 until netColumn && netColumn < clientColumn &&
                debugHud.indexOf("HudFact(\"HOST\"") in hostColumn until netColumn &&
                debugHud.indexOf("HudFact(\"RTT\"") in netColumn until clientColumn &&
                debugHud.indexOf("HudFact(\"DEC\"") > clientColumn
        )
        assertFalse(
            "Performance stays the four-metric row it is pinned to",
            performanceHud.contains("decodeTimeLabel")
        )
    }

    @Test
    fun commandCenterHeaderStaysFixedWhileSectionsScroll() {
        val content = readNovaQuickMenuContent()
        val body = content.section(
            "fun NovaPageScope.NovaQuickMenuContent(",
            "@Composable\nprivate fun NovaPageScope.NovaQuickMenuHeader("
        )
        val header = body.indexOf("NovaQuickMenuHeader(ui, callbacks, endSplit)")
        val scroll = body.indexOf(".verticalScroll(sections)")
        val strip = body.indexOf("NovaQuickMenuSessionStrip(ui, Modifier.novaInitialFocus())")

        assertTrue(
            "Close, Disconnect, and End Session must not scroll away: the header lives above the scrolling column, and only the sections scroll",
            header in 0 until scroll && scroll in 0 until strip
        )
    }

    @Test
    fun commandCenterPicksTheHudModeDirectly() {
        val content = readNovaQuickMenuContent()
        val quickMenu = readNovaQuickMenu()
        val picker = content.section(
            "private fun NovaQuickMenuHudModePicker(",
            "\n/**"
        )

        assertTrue(
            "HUD Mode is one value row of every layout, which Left and Right move through in place with the current one checked, not a cycle button that hid where the next press would land",
            picker.contains("hudMode.options.map") &&
                picker.contains("NovaValueRow(") &&
                picker.contains("onChange = callbacks.onHudModeSelect")
        )
        assertTrue(
            "the picker sits right under the Nova HUD row it configures",
            content.contains("if (row.id == NovaQuickMenuActionId.NOVA_HUD) {")
        )
        assertTrue(
            "the host jumps the HUD straight to the chosen layout",
            quickMenu.contains("game.setNovaHudMode(mode)")
        )
        assertFalse(
            "no cycle row survives beside the picker",
            content.contains("NOVA_HUD_MODE") || quickMenu.contains("NOVA_HUD_MODE")
        )
    }

    @Test
    fun commandCenterOpacityPresetsStayCollapsedUntilOpened() {
        val content = readNovaQuickMenuContent()
        val quickMenu = readNovaQuickMenu()
        val menuOpacity = content.section(
            "private fun NovaPageScope.NovaQuickMenuMenuOpacityControl(",
            "@Composable\nprivate fun NovaPageScope.NovaQuickMenuHudOpacityControl("
        )
        val hudOpacity = content.section(
            "private fun NovaPageScope.NovaQuickMenuHudOpacityControl(",
            "// Four layouts in one row"
        )

        // Two open preset strips were most of the Overlays section on a Retroid. Each opacity is
        // now one row that steps through its presets in place, so there is nothing to open.
        assertTrue(
            "menu opacity is one ordered value row",
            menuOpacity.contains("NovaValueRow(") && menuOpacity.contains("ordered = true") && !menuOpacity.contains("expanded")
        )
        assertTrue(
            "HUD opacity is one ordered value row that waits for the HUD",
            hudOpacity.contains("NovaValueRow(") && hudOpacity.contains("enabled = hudOpacity.enabled") && !hudOpacity.contains("expanded")
        )
        assertTrue(
            "a held Left or Right writes the preference once, after the steps stop",
            quickMenu.contains("writeAfterSteps(MENU_OPACITY_WRITE)") &&
                quickMenu.contains("writeAfterSteps(HUD_OPACITY_WRITE)") &&
                quickMenu.contains("SETTING_WRITE_DEBOUNCE_MS = 250L")
        )
    }

    @Test
    fun commandCenterEveryExitSlidesTheDrawerOut() {
        val quickMenu = readNovaQuickMenu()
        val content = readNovaQuickMenuContent()

        assertTrue(
            "Close, B, Back and programmatic hides all close the panel, which slides out on the frame's motion before the window goes",
            content.contains("onClick = callbacks.onDismiss") &&
                quickMenu.contains("onDismiss = { dismiss() }") &&
                quickMenu.contains("override fun hideMenu() {\n        dismiss()") &&
                quickMenu.contains("if (isMenuOpen()) surfaces.panel.close()")
        )
        assertFalse(
            "no fallback timer of its own: the panel window closes when the exit lands",
            quickMenu.contains("DISMISS_MOTION_FALLBACK_MS")
        )
    }

    @Test
    fun streamHudPerformanceSeparatesFpsFromDetailMetrics() {
        val source = readNovaStreamHudContent()
        val performanceHud = source.section(
            "private fun NovaStreamHudPerformance(",
            "@Composable\nprivate fun NovaStreamHudMinimal("
        )

        assertTrue(
            "FPS display should place the FPS/target/sparkline and added detail metrics in separate bounded rows",
            performanceHud.contains("HudPerformancePrimaryRow(state)") &&
                performanceHud.contains("HudPerformanceDetailRow(state)")
        )

        val primaryRow = source.section(
            "private fun HudPerformancePrimaryRow(",
            "@Composable\nprivate fun HudPerformanceDetailRow("
        )
        val detailRow = source.section(
            "private fun HudPerformanceDetailRow(",
            "@Composable\nprivate fun NovaStreamHudMinimal("
        )

        assertTrue(primaryRow.contains("state.fpsLabel"))
        assertTrue(primaryRow.contains("state.targetFpsLabel"))
        assertTrue(primaryRow.contains("NovaHudSparkline("))
        assertFalse(primaryRow.contains("state.latencyLabel"))
        assertFalse(primaryRow.contains("state.bitrateLabel"))
        assertFalse(primaryRow.contains("state.resolutionLabel"))
        assertFalse(primaryRow.contains("state.codecLabel"))

        assertTrue(detailRow.contains("state.latencyLabel"))
        assertTrue(detailRow.contains("state.bitrateLabel"))
        assertTrue(detailRow.contains("state.resolutionLabel"))
        assertTrue(detailRow.contains("state.codecLabel"))
        assertFalse(detailRow.contains("state.fpsLabel"))
    }

    @Test
    fun streamHudTranslucentPanelCastsNoShadow() {
        val source = readNovaStreamHudContent()
        val panel = source.section(
            "private fun HudPanel(",
            "@Composable\nprivate fun HudSlimStat("
        )

        assertTrue(
            "only a solid NovaHUD casts a shadow: Android draws it under the whole panel, and at any opacity below 100% it showed through as a faint box",
            panel.contains(".then(if (hudOpacityScale >= 1f) Modifier.shadow(16.dp, panelShape, clip = false) else Modifier)")
        )
        assertFalse(
            "a shadow scaled by opacity still showed through a translucent panel",
            panel.contains(".shadow(16.dp * hudOpacityScale")
        )
    }

    private fun readNovaStreamHudContent(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaStreamHudContent.kt")

    private fun readNovaQuickMenu(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt")

    private fun readNovaQuickMenuContent(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt")

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    @Test
    fun commandCenterCardsAreWiredToTheRealCallbacks() {
        val quickMenu = readSource("src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt")

        // The diagnosis card was handed `NovaQuickMenuCallbacks()` -- a fresh instance
        // whose every member defaults to a no-op. It rendered `enabled`, sat fourth from
        // the top, and did nothing when pressed. Nothing caught it because a dead click
        // looks exactly like a live one, and the compose test here is a smoke test.
        assertFalse(
            "no surface in the Command Center may construct its own callbacks; the ones " +
                "handed down are the only ones wired to anything",
            quickMenu.contains("NovaQuickMenuCallbacks()")
        )
        assertTrue(
            "the diagnosis card takes callbacks and passes them to the card it draws",
            quickMenu.contains("NovaQuickMenuDiagnosisCard(ui, callbacks)") &&
                quickMenu.contains("callbacks: NovaQuickMenuCallbacks,")
        )
    }

    @Test
    fun endingIsConfirmedOnceInItsOwnSlotAndNeverAskedAgain() {
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val menu = readNovaQuickMenu()
        val controller = readSource("src/main/java/com/papi/nova/utils/ExternalDisplayControlController.kt")
        val deck = readSource("src/main/java/com/papi/nova/ui/NovaCompanionCommandDeckView.kt")

        val endSession = game.section("fun endSession() {", "fun quit() {")
        assertTrue(
            "endSession ends for good and asks nothing: every button that reaches it has already split and been confirmed",
            endSession.contains("quitOnStop = true") &&
                endSession.contains("markLocalSessionEnd()") &&
                endSession.contains("finish()") &&
                !endSession.contains("present(") &&
                !endSession.contains("Confirm(")
        )
        val quit = game.section("fun quit() {", "override fun showGameMenu(")
        assertTrue(
            "quit is only the fallback for paths with no button to split: a Confirm page with Stay focused that ends through endSession",
            quit.contains("NovaCommonPage.Confirm(") && quit.contains("onConfirm = { endSession() }")
        )
        val endStream = menu.section("onEndStream = {", "onStability = {")
        assertTrue(
            "the Command Center's End Session is confirmed by the header's split, so it ends without a second confirm",
            endStream.contains("game.endSession()") && !endStream.contains("game.quit()")
        )
        val deckEnd = controller.section("NovaCompanionCommandActionId.END_SESSION -> {", "handler.post(::renderCommandDeck)")
        assertTrue(
            "the companion deck's End tile splits in the tile and ends without a second confirm",
            deckEnd.contains("game.endSession()") && !deckEnd.contains("game.quit()") &&
                deck.contains("shape = NovaSplitShape.Tile") &&
                deck.contains("state = endSessionSplit")
        )
        val back = controller.section("fun handleCompanionBack() {", "fun handleBackFromOwningGame()")
        assertTrue(
            "the deck's Back takes an armed End back before it does anything else",
            back.indexOf("endSessionSplit.disarm()") in 0 until back.indexOf("isNovaKeyboardVisible")
        )
    }

    @Test
    fun companionDeckSkipsUnchangedPerfIntervals() {
        val deck = readSource("src/main/java/com/papi/nova/ui/NovaCompanionCommandDeckView.kt")

        // render() runs once per perf interval for as long as an external display is
        // attached, and most intervals change nothing. Without the early-out it did ten
        // getString + setText calls and the layout pass they trigger, on the main thread,
        // while a game was streaming.
        assertTrue(
            "an unchanged interval must cost one comparison, not ten setText calls",
            deck.contains("val unchanged = latestState == state") &&
                deck.contains("if (unchanged) {")
        )
    }

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing start marker: $startMarker" }
        val end = indexOf(endMarker, start)
        require(end >= 0) { "Missing end marker: $endMarker" }
        return substring(start, end)
    }
}
