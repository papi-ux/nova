package com.papi.nova.binding.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class NovaControllerShortcutStateTest {
    @Test
    fun guideStartOpensQuickMenuAndConsumesRelease() {
        val shortcuts = NovaControllerShortcutState()

        assertEquals(
            NovaControllerShortcutAction.DEFER_GUIDE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_MODE, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.OPEN_QUICK_MENU,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_START, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_START)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_MODE)
        )
    }

    @Test
    fun guideStartConsumesReleaseWhenGuideIsReleasedFirst() {
        val shortcuts = NovaControllerShortcutState()

        assertEquals(
            NovaControllerShortcutAction.DEFER_GUIDE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_MODE, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.OPEN_QUICK_MENU,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_START, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_MODE)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_START)
        )
    }

    @Test
    fun guideYShowsOrCyclesNovaHudAndConsumesRelease() {
        val shortcuts = NovaControllerShortcutState()

        assertEquals(
            NovaControllerShortcutAction.DEFER_GUIDE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_MODE, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.CYCLE_NOVA_HUD,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_Y, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_Y)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_MODE)
        )
    }

    @Test
    fun guideRepeatStaysDeferredUntilChordOrRelease() {
        val shortcuts = NovaControllerShortcutState()

        assertEquals(
            NovaControllerShortcutAction.DEFER_GUIDE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_MODE, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.DEFER_GUIDE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_MODE, repeatCount = 1)
        )
        assertEquals(
            NovaControllerShortcutAction.PASS_THROUGH_GUIDE_TAP,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_MODE)
        )
    }

    @Test
    fun guideAlonePassesThroughOnRelease() {
        val shortcuts = NovaControllerShortcutState()

        assertEquals(
            NovaControllerShortcutAction.DEFER_GUIDE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_MODE, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.PASS_THROUGH_GUIDE_TAP,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_MODE)
        )
    }

    @Test
    fun unrelatedGuideChordForwardsGuideToHost() {
        val shortcuts = NovaControllerShortcutState()

        assertEquals(
            NovaControllerShortcutAction.DEFER_GUIDE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_MODE, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.FORWARD_GUIDE_TO_HOST,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_A, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.NONE,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_A)
        )
        assertEquals(
            NovaControllerShortcutAction.NONE,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_MODE)
        )
    }

    @Test
    fun startSelectOpensQuickMenuWithoutGuideAndConsumesRelease() {
        val shortcuts = NovaControllerShortcutState()

        assertEquals(
            NovaControllerShortcutAction.NONE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_START, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.OPEN_QUICK_MENU,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_SELECT, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_START, repeatCount = 1)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_SELECT)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_START)
        )
    }

    @Test
    fun selectStartOpensQuickMenuWithoutGuideAndConsumesRelease() {
        val shortcuts = NovaControllerShortcutState()

        assertEquals(
            NovaControllerShortcutAction.NONE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_SELECT, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.OPEN_QUICK_MENU,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_START, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_START)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_SELECT)
        )
    }

    @Test
    fun backYShowsOrCyclesNovaHudWithoutGuideAndConsumesRelease() {
        val shortcuts = NovaControllerShortcutState()

        assertEquals(
            NovaControllerShortcutAction.NONE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BACK, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.CYCLE_NOVA_HUD,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_Y, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_Y, repeatCount = 1)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_Y)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BACK)
        )
    }

    @Test
    fun yBackShowsOrCyclesNovaHudWithoutGuideAndConsumesRelease() {
        val shortcuts = NovaControllerShortcutState()

        assertEquals(
            NovaControllerShortcutAction.NONE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_Y, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.CYCLE_NOVA_HUD,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BACK, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BACK)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_Y)
        )
    }

    @Test
    fun guideConsumedShortcutClearsAnyPendingNoGuideChordKeys() {
        val shortcuts = NovaControllerShortcutState()

        assertEquals(
            NovaControllerShortcutAction.NONE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_START, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.DEFER_GUIDE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_MODE, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.CYCLE_NOVA_HUD,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_Y, repeatCount = 0)
        )

        assertEquals(
            NovaControllerShortcutAction.NONE,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_START)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_Y)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_BUTTON_MODE)
        )

        assertEquals(
            "released Start should not remain latched after a consumed Guide shortcut",
            NovaControllerShortcutAction.NONE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_SELECT, repeatCount = 0)
        )
    }


    @Test
    fun loneAppSwitchOpensQuickMenuWhenEnabledAndConsumesRelease() {
        val shortcuts = NovaControllerShortcutState()
        shortcuts.loneAppSwitchOpensQuickMenu = true

        assertEquals(
            NovaControllerShortcutAction.OPEN_QUICK_MENU,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_APP_SWITCH, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_APP_SWITCH, repeatCount = 1)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_APP_SWITCH)
        )
    }

    @Test
    fun loneAppSwitchDoesNotOpenQuickMenuByDefault() {
        val shortcuts = NovaControllerShortcutState()

        assertEquals(
            NovaControllerShortcutAction.NONE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_APP_SWITCH, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.NONE,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_APP_SWITCH)
        )
    }

    @Test
    fun loneMenuStillDoesNotOpenQuickMenuWithoutSelect() {
        val shortcuts = NovaControllerShortcutState()
        shortcuts.loneAppSwitchOpensQuickMenu = true

        assertEquals(
            NovaControllerShortcutAction.NONE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_MENU, repeatCount = 0)
        )
    }

    @Test
    fun appSwitchShortcutDoesNotLatchStartSelectChordState() {
        val shortcuts = NovaControllerShortcutState()
        shortcuts.loneAppSwitchOpensQuickMenu = true

        assertEquals(
            NovaControllerShortcutAction.OPEN_QUICK_MENU,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_APP_SWITCH, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
            shortcuts.onButtonUp(KeyEvent.KEYCODE_APP_SWITCH)
        )
        assertEquals(
            NovaControllerShortcutAction.NONE,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_START, repeatCount = 0)
        )
        assertEquals(
            NovaControllerShortcutAction.OPEN_QUICK_MENU,
            shortcuts.onButtonDown(KeyEvent.KEYCODE_BUTTON_SELECT, repeatCount = 0)
        )
    }

}
