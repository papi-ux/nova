package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feedback drawn over a live stream, and the anchoring that makes it visible at all.
 */
class NovaInStreamFeedbackTest {

    @Test
    fun theCommandCenterDoesNotFallBackToRawToasts() {
        val menu = File("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt").readText()

        val toasts = Regex("""Toast\.makeText""").findAll(menu).count()
        assertEquals(
            "The in-stream Command Center's messages are the only feedback the stability " +
                "apply/fail and blocked End-Session paths have, and they are drawn over a " +
                "running game. They go through NovaSnackbar so they are themed and so a " +
                "failure does not look like a success.",
            0,
            toasts
        )
    }

    @Test
    fun snackbarsRaisedFromADialogAnchorToItAndFallBackWhenItCloses() {
        val snackbar = File("src/main/java/com/papi/nova/ui/NovaSnackbar.kt").readText()

        // A Snackbar draws inside the window of the view it is handed. The activity content
        // view is the wrong window while any Dialog is up, and the Command Center is a
        // full-screen one -- so a naive conversion hides every message behind the drawer.
        // This is the single line that makes the conversion safe.
        assertTrue(
            "NovaSnackbar must accept a view to anchor to, so a caller inside a dialog can " +
                "name the window its message belongs in",
            snackbar.contains("anchor: View? = null")
        )
        assertTrue(
            "and must fall back to the activity when that view is gone: a dismissed Dialog " +
                "detaches its content, which is what lets one call site work both while the " +
                "drawer is open and after it closes, without knowing which it is",
            snackbar.contains("anchor?.takeIf { it.isAttachedToWindow }") &&
                snackbar.contains("?: activity.findViewById<View>(android.R.id.content)")
        )
    }

    @Test
    fun theCommandCenterAnchorsToItsOwnDrawer() {
        val menu = File("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt").readText()

        val calls = Regex("""NovaSnackbar\.(show|showError|showSuccess|showQuiet)\(""")
            .findAll(menu).count()
        val anchored = Regex("""anchor = composeView""").findAll(menu).count()

        assertTrue("the Command Center should report through NovaSnackbar", calls > 0)
        assertEquals(
            "every one of them anchors to the drawer's own view; an unanchored call from " +
                "inside the drawer is invisible while the drawer is up",
            calls,
            anchored
        )
    }
}
