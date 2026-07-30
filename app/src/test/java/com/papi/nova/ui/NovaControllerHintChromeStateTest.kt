package com.papi.nova.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaControllerHintChromeStateTest {
    @Test
    fun hintsStartVisibleAndBrowsingIntentCollapsesThem() {
        val initial = NovaControllerHintChromeState()

        assertTrue(initial.visible)
        assertFalse(initial.reduce(NovaControllerHintChromeEvent.BROWSE_INTENT).visible)
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
        val hidden = visible.reduce(NovaControllerHintChromeEvent.BROWSE_INTENT)

        assertFalse(hidden.reduce(NovaControllerHintChromeEvent.BROWSE_INTENT).visible)
        assertTrue(visible.reduce(NovaControllerHintChromeEvent.IDLE).visible)
    }
}
