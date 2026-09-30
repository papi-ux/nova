package com.papi.nova.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.nvstream.HostRefusal
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaStatePages
import com.papi.nova.ui.panel.NovaSurfaces
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import com.papi.nova.utils.Dialog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * R5 on the three blocking pages whose primary is a recovery: B, Back and Escape each take the
 * way out the page names, and never Unlock, Try Again or Reconnect. Each page is the one its
 * caller builds, drawn as the window draws it, and each exit is tried on its own page. B and
 * Escape go through the key gate a Nova window puts in front of its content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaProblemBackComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private enum class Exit { B, Back, Escape }

    private fun NovaTestKeys.leave(exit: Exit) = when (exit) {
        Exit.B -> gatedPress(KeyEvent.KEYCODE_BUTTON_B)
        Exit.Back -> back()
        Exit.Escape -> gatedPress(KeyEvent.KEYCODE_ESCAPE)
    }

    @After
    fun disposeSurfaces() {
        NovaSurfaces.existing(rule.activity)?.dispose()
    }

    /**
     * The window NovaSurfaces opened for a page is closed at once, so the page is drawn only here,
     * where the test's keys reach it. Its state pages stay posted.
     */
    private fun closeSurfacesWindow() {
        (ShadowDialog.getLatestDialog() as? ComponentDialog)?.takeIf { it.isShowing }?.dismiss()
        rule.waitForIdle()
    }

    /** The activity's state pages, drawn here as its window draws them. */
    private fun drawSurfaces(): NovaTestKeys {
        val surfaces = NovaSurfaces.of(rule.activity)
        return rule.setPanelContent {
            val pages by surfaces.states.collectAsState()
            NovaStatePages(pages = pages, onShowingChange = {}, isPosted = { key -> surfaces.states.value.any { it.key == key } })
        }
    }

    // Host locked.

    @Test
    fun hostLockedBNeverUnlocks() = hostLocked(Exit.B)

    @Test
    fun hostLockedBackNeverUnlocks() = hostLocked(Exit.Back)

    @Test
    fun hostLockedEscapeNeverUnlocks() = hostLocked(Exit.Escape)

    private fun hostLocked(exit: Exit) {
        val client = Mockito.mock(PolarisApiClient::class.java)
        val overlay = LockScreenOverlay(rule.activity, client)
        val keys = drawSurfaces()
        rule.runOnUiThread { overlay.show() }
        closeSurfacesWindow()
        rule.onNodeWithText("Unlock host").assertIsFocused()

        keys.leave(exit)

        verify(client, never()).unlockScreen()
        assertFalse("$exit set the page aside, as Not Now does", overlay.isShowing)
        rule.onNodeWithText("Unlock host").assertDoesNotExist()
    }

    // Launch failed.

    @Test
    fun launchFailedBNeverRetries() = launchFailed(Exit.B)

    @Test
    fun launchFailedBackNeverRetries() = launchFailed(Exit.Back)

    @Test
    fun launchFailedEscapeNeverRetries() = launchFailed(Exit.Escape)

    private fun launchFailed(exit: Exit) {
        var pages by mutableStateOf(emptyList<NovaStatePage>())
        var retries = 0
        var leaves = 0
        val keys = rule.setPanelContent { NovaStatePages(pages = pages, onShowingChange = {}) }
        val refusal = HostRefusal(status = 503, code = "space_busy", message = "The Space is still starting.", action = null)
        for (space in listOf(false, true)) {
            rule.runOnUiThread {
                pages = listOf(
                    novaLaunchIssuePage(
                        context = rule.activity,
                        key = "launch-issue-$space",
                        message = "The host refused the launch.",
                        space = space,
                        refusal = refusal.takeIf { space },
                        retry = { retries++ },
                        leave = { leaves++ },
                        takeDown = { pages = emptyList() },
                    ),
                )
            }
            rule.waitForIdle()
            rule.onNodeWithText(rule.activity.getString(if (space) R.string.nova_space_launch_issue_retry else R.string.nova_stream_launch_retry))
                .assertIsFocused()

            keys.leave(exit)

            assertEquals("$exit never retries a launch (space $space)", 0, retries)
            assertTrue("$exit took the page down", pages.isEmpty())
        }
        assertEquals("$exit returned to Nova from both pages", 2, leaves)
    }

    // Connection lost.

    @Test
    fun connectionLostBNeverReconnects() = connectionLost(Exit.B)

    @Test
    fun connectionLostBackNeverReconnects() = connectionLost(Exit.Back)

    @Test
    fun connectionLostEscapeNeverReconnects() = connectionLost(Exit.Escape)

    private fun connectionLost(exit: Exit) {
        var reconnects = 0
        val keys = drawSurfaces()
        rule.runOnUiThread {
            Dialog.displayDialog(
                rule.activity,
                "Connection lost",
                "The host stopped answering.",
                true,
                "Reconnect",
                Runnable { reconnects++ },
                help = true,
            )
        }
        closeSurfacesWindow()
        rule.onNodeWithText("Reconnect").assertIsFocused()

        keys.leave(exit)

        assertEquals("$exit never reconnects", 0, reconnects)
        assertTrue("$exit ran Close, which ends the screen", rule.activity.isFinishing)
        assertTrue(NovaSurfaces.of(rule.activity).states.value.isEmpty())
    }
}
