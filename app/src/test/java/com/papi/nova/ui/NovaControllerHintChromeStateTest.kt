package com.papi.nova.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaControllerHintChromeStateTest {
    @Test
    fun hintsStartVisibleAndBrowsingIntentCollapsesThem() {
        val initial = NovaControllerHintChromeState()

        assertTrue(initial.visible)
        assertFalse(initial.reduce(NovaControllerHintChromeEvent.CONTROLLER_INPUT).visible)
    }

    @Test
    fun idleLayoutHelpAndExplicitRevealRestoreHints() {
        val hidden = NovaControllerHintChromeState(visible = false)

        assertTrue(hidden.reduce(NovaControllerHintChromeEvent.IDLE).visible)
        assertTrue(hidden.reduce(NovaControllerHintChromeEvent.LAYOUT_CHANGED).visible)
        assertTrue(hidden.reduce(NovaControllerHintChromeEvent.HELP_REQUESTED).visible)
        assertTrue(hidden.reduce(NovaControllerHintChromeEvent.EXPLICIT_REVEAL).visible)
    }

    @Test
    fun repeatedVisibilityEventsAreIdempotent() {
        val visible = NovaControllerHintChromeState()
        val hidden = visible.reduce(NovaControllerHintChromeEvent.CONTROLLER_INPUT)

        assertFalse(hidden.reduce(NovaControllerHintChromeEvent.CONTROLLER_INPUT).visible)
        assertTrue(visible.reduce(NovaControllerHintChromeEvent.IDLE).visible)
    }

    @Test
    fun changingBetweenControllerAndTouchRestoresHintsBeforeRepeatedInputCollapsesThem() {
        val controllerHidden = NovaControllerHintChromeState()
            .reduce(NovaControllerHintChromeEvent.CONTROLLER_INPUT)
        val touchModeRevealed = controllerHidden.reduce(NovaControllerHintChromeEvent.TOUCH_INPUT)
        val touchHidden = touchModeRevealed.reduce(NovaControllerHintChromeEvent.TOUCH_INPUT)
        val controllerModeRevealed = touchHidden.reduce(NovaControllerHintChromeEvent.CONTROLLER_INPUT)

        assertFalse(controllerHidden.visible)
        assertTrue(touchModeRevealed.visible)
        assertFalse(touchHidden.visible)
        assertTrue(controllerModeRevealed.visible)
    }
}
