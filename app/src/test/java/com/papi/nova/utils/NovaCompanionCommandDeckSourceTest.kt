package com.papi.nova.utils

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaCompanionCommandDeckSourceTest {
    private val root = File("src/main/java/com/papi/nova")

    @Test
    fun sharedControllerOwnsOneCommandDeckForBothHosts() {
        val controller = File(root, "utils/ExternalDisplayControlController.kt").readText()
        val activity = File(root, "utils/ExternalDisplayControlActivity.kt").readText()
        val presentation = File(root, "utils/ExternalDisplayControlPresentation.kt").readText()
        val host = File(root, "utils/ExternalDisplayControlHost.kt").readText()

        assertTrue(controller.contains("NovaCompanionCommandDeckView("))
        assertTrue(host.contains("fun updateCommandDeckState(state: NovaCompanionCommandDeckState)"))
        assertTrue(activity.contains("controller.updateCommandDeckState(state)"))
        assertTrue(presentation.contains("controller.updateCommandDeckState(state)"))
        assertFalse(activity.contains("NovaCompanionCommandDeckView("))
        assertFalse(presentation.contains("NovaCompanionCommandDeckView("))
    }

    @Test
    fun gameFansExistingRuntimeSamplesIntoAuthoritativeHudProjection() {
        val game = File(root, "Game.kt").readText()

        assertTrue(game.contains("NovaHudUiState.from("))
        assertTrue(game.contains("externalDisplayControlPresentation?.updateCommandDeckState(state)"))
        assertTrue(game.contains("lastCompanionPerfSample = sample"))
        assertFalse(game.contains("class CompanionTelemetryCollector"))
        assertFalse(game.contains("class CompanionTuningModel"))
    }

    @Test
    fun deckPreservesTouchpadDominanceAndProtectedDestructiveAction() {
        val controller = File(root, "utils/ExternalDisplayControlController.kt").readText()
        val deck = File(root, "ui/NovaCompanionCommandDeckView.kt").readText()
        val state = File(root, "ui/NovaCompanionCommandDeckState.kt").readText()

        assertTrue(controller.contains("rootLayout.setOnTouchListener"))
        assertTrue(controller.contains("commandDeckView.render("))
        assertTrue(deck.contains("requestInitialFocus"))
        assertTrue(deck.contains("HapticFeedbackConstants.KEYBOARD_TAP"))
        assertTrue(state.contains("NovaCompanionCommandActionId.END_SESSION"))
        assertTrue(state.contains("destructive = true"))
        assertTrue(state.contains("firstOrNull { it.enabled && !it.destructive }"))
    }

    @Test
    fun defaultDisplayHostKeepsWindowFocusWhileYieldingHandledInputToTheRoot() {
        val activity = File(root, "utils/ExternalDisplayControlActivity.kt").readText()
        val controller = File(root, "utils/ExternalDisplayControlController.kt").readText()

        assertTrue(activity.contains("window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)"))
        assertTrue(activity.contains("override fun prepareForCommandDeckFocus()"))
        assertTrue(activity.contains("window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)"))
        assertFalse(activity.contains("override fun releaseCommandDeckFocus()"))
        assertTrue(controller.contains("host.prepareForCommandDeckFocus()"))
        assertTrue(controller.contains("yieldCommandDeckFocusAfterHandledInput(handled"))
        assertTrue(controller.contains("handler.post"))
        assertTrue(controller.contains("rootLayout.requestFocus()"))
        assertFalse(controller.contains("host.releaseCommandDeckFocus()"))
        assertTrue(controller.contains("restoreCommandDeckFocus()"))
    }

    @Test
    fun hideCompanionIsSessionScopedAndNotificationReopenIsExplicit() {
        val state = File(root, "ui/NovaCompanionCommandDeckState.kt").readText()
        val view = File(root, "ui/NovaCompanionCommandDeckView.kt").readText()
        val controller = File(root, "utils/ExternalDisplayControlController.kt").readText()
        val presentation = File(root, "utils/ExternalDisplayControlPresentation.kt").readText()
        val game = File(root, "Game.kt").readText()
        val receiver = File(root, "StartExternalDisplayControlReceiver.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(state.contains("NovaCompanionCommandActionId.HIDE_COMPANION"))
        assertTrue(view.contains("NovaCompanionCommandActionId.HIDE_COMPANION -> R.string.companion_deck_hide_companion"))
        assertTrue(view.contains("NovaCompanionCommandActionId.HIDE_COMPANION -> R.drawable.ic_menu_collapse"))
        assertTrue(strings.contains("name=\"companion_deck_hide_companion\""))
        assertTrue(controller.contains("NovaCompanionCommandActionId.HIDE_COMPANION -> game.hideCompanionControlsForSession()"))
        assertTrue(game.contains("private var companionControlsDismissedByUser = false"))
        assertTrue(game.contains("private val companionControlReopenGeneration = CompanionControlReopenGeneration()"))
        assertTrue(game.contains("fun beginExplicitCompanionControlsReopen(): Long"))
        assertTrue(game.contains("fun hideCompanionControlsForSession()"))
        assertTrue(game.contains("fun showCompanionControls("))
        assertTrue(game.contains("requestGeneration: Long? = null"))
        assertTrue(game.contains("companionControlsDismissedByUser = false"))
        assertTrue(receiver.contains("beginExplicitCompanionControlsReopen()"))
        assertTrue(receiver.contains("requestGeneration = reopenRequestGeneration"))
        assertTrue(strings.contains("name=\"companion_deck_hide_requires_notifications\""))
        assertTrue(presentation.contains("fun canUseCompanionControlsNotification(context: Context): Boolean"))
        assertTrue(presentation.contains("areNotificationsEnabled()"))
        assertTrue(presentation.contains("NotificationManager.IMPORTANCE_NONE"))
        assertTrue(game.contains("hideCompanionEnabled = ExternalDisplayControlPresentation.canUseCompanionControlsNotification(this)"))
        val streamReady = game.substringAfter("override fun connectionStarted()")
            .substringBefore("// Show Nova Stream HUD")
        val requestInvalidation = streamReady.indexOf("companionControlReopenGeneration.invalidatePendingRequests()")
        val sessionReset = streamReady.indexOf("companionControlsDismissedByUser = false")
        val streamActive = streamReady.indexOf("handleStreamStartedState()")
        val automaticShow = streamReady.indexOf("showCompanionControls()")
        assertTrue("A new stream must invalidate delayed reopen from the previous session", requestInvalidation >= 0)
        assertTrue("A new stream must clear the previous session dismissal", sessionReset > requestInvalidation)
        assertTrue("The session dismissal must reset before the stream is marked active", sessionReset < streamActive)
        assertTrue("Stream-ready automatic launch must respect the dismissal gate", automaticShow > streamActive)
        assertFalse("Automatic stream-ready launch must not bypass lifecycle policy", streamReady.contains("launchCompanionControlsIfAvailable()"))
        val hide = game.substringAfter("fun hideCompanionControlsForSession()").substringBefore("fun showCompanionControls(")
        val hideAvailability = hide.indexOf("CompanionControlLifecyclePolicy.canHide(")
        val hideInvalidation = hide.indexOf("companionControlReopenGeneration.invalidatePendingRequests()")
        val hideDismissal = hide.indexOf("companionControlsDismissedByUser = true")
        assertTrue("Hide must validate a usable reopen authority before invalidating requests", hideAvailability >= 0 && hideAvailability < hideInvalidation)
        assertTrue("Hide must invalidate delayed reopen before dismissing", hideInvalidation < hideDismissal)
        assertTrue(hide.contains("ExternalDisplayControlPresentation.ensureCompanionControlsNotification(this)"))
        assertTrue(hide.contains("R.string.companion_deck_hide_requires_notifications"))
        assertTrue(hide.contains("dismissAfterCurrentCallback()"))
        assertFalse("Hiding must keep the notification reopen path alive", hide.contains("closeCompanionControls("))
        assertFalse("Hiding must not disconnect or end the stream", hide.contains("disconnect(") || hide.contains("quit("))
        val beginReopen = game.substringAfter("fun beginExplicitCompanionControlsReopen(): Long").substringBefore("fun hideCompanionControlsForSession()")
        assertTrue(beginReopen.contains("companionControlReopenGeneration.beginRequest()"))
        assertTrue(beginReopen.contains("companionControlReopenGeneration.isCurrent(requestGeneration)"))
        assertTrue(beginReopen.contains("companionControlsDismissedByUser = false"))
        val show = game.substringAfter("fun showCompanionControls(").substringBefore("fun attachExternalDisplayControlActivity(")
        val staleRequestGuard = show.indexOf("companionControlReopenGeneration.isCurrent(requestGeneration)")
        val lifecycleDecision = show.indexOf("CompanionControlLifecyclePolicy.canShow(")
        assertTrue("Stale explicit reopen must be rejected before lifecycle or dismissal changes", staleRequestGuard >= 0 && staleRequestGuard < lifecycleDecision)
        assertTrue(show.contains("requestGeneration == null"))
        assertTrue(show.contains("CompanionControlLifecyclePolicy.shouldRestoreDismissedCompanion("))
        assertTrue("Display re-add must restore the explicit notification path", show.contains("ensureCompanionControlsNotification(this)"))
        assertTrue("Loss of the explicit reopen path must restore the companion", show.contains("companionControlsDismissedByUser = false"))
        assertTrue(game.contains("override fun onResume()"))
        val resume = game.substringAfter("override fun onResume()").substringBefore("override fun onPause()")
        assertTrue("Returning from notification settings must re-evaluate a hidden companion", resume.contains("showCompanionControls()"))
    }

    @Test
    fun quickKeysAndCommandCenterUseDistinctExistingAuthorities() {
        val controller = File(root, "utils/ExternalDisplayControlController.kt").readText()
        val gameMenu = File(root, "GameMenu.kt").readText()

        assertTrue(gameMenu.contains("fun showSpecialKeysMenuFromCommandDeck()"))
        assertTrue(gameMenu.contains("private fun showSpecialKeysMenu()"))
        assertTrue(controller.contains("NovaCompanionCommandActionId.QUICK_KEYS -> showQuickKeys()"))
        assertTrue(controller.contains("NovaCompanionCommandActionId.COMMAND_CENTER -> showGameMenu()"))
        assertTrue(controller.contains("gameMenu?.showSpecialKeysMenuFromCommandDeck()"))
    }

    @Test
    fun keyboardSelectionAndFocusRestorationTrackIndependentAuthorities() {
        val controller = File(root, "utils/ExternalDisplayControlController.kt").readText()
        val deck = File(root, "ui/NovaCompanionCommandDeckView.kt").readText()

        assertTrue(controller.contains("isAndroidKeyboardVisible"))
        assertTrue(controller.contains("isNovaKeyboardVisible"))
        assertFalse(controller.contains("private var isKeyboardVisible ="))
        assertTrue(controller.contains("androidKeyboardVisible = isAndroidKeyboardVisible"))
        assertTrue(controller.contains("novaKeyboardVisible = isNovaKeyboardVisible"))
        val backHandler = controller.substringAfter("fun handleCompanionBack()")
            .substringBefore("fun handleBackFromOwningGame()")
        assertTrue(backHandler.contains("isNovaKeyboardVisible"))
        assertFalse(backHandler.contains("game.isKeyboardLayoutVisible"))
        assertTrue(controller.contains("commandDeckView.restoreSafeActionFocus()"))
        assertTrue(deck.contains("fun restoreSafeActionFocus()"))
    }

    @Test
    fun semanticThemeAndLocalizedUnavailableCopyAreUsed() {
        val deck = File(root, "ui/NovaCompanionCommandDeckView.kt").readText()
        val state = File(root, "ui/NovaCompanionCommandDeckState.kt").readText()
        val theme = File(root, "ui/NovaThemeManager.kt").readText()

        assertTrue(deck.contains("NovaThemeManager.getErrorColor(context)"))
        assertFalse(deck.contains("Color.rgb("))
        assertTrue(theme.contains("fun getErrorColor(context: Context)"))
        assertTrue(state.contains("unavailableLabel: String"))
        assertFalse(state.contains("private const val UNAVAILABLE"))
    }

    @Test
    fun productCopyIsResourceBackedAndSeparatesDisconnectFromEndSession() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val names = listOf(
            "companion_deck_touchpad",
            "companion_deck_android_keyboard",
            "companion_deck_nova_keyboard",
            "companion_deck_quick_keys",
            "companion_deck_nova_hud",
            "companion_deck_zoom_pan",
            "companion_deck_command_center",
            "companion_deck_disconnect",
            "companion_deck_end_session",
            "companion_deck_status_unavailable",
        )
        names.forEach { name -> assertTrue(strings.contains("name=\"$name\"")) }
        assertTrue(strings.contains(">Disconnect</string>"))
        assertTrue(strings.contains(">End Session</string>"))
    }
}
