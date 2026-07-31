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
