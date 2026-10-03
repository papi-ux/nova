package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.R
import com.papi.nova.novaOtpPairingWaitPage
import com.papi.nova.preferences.NovaUpdateInstaller
import com.papi.nova.preferences.novaPairLinkConfirmPage
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaStatePages
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.advance
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Three sites that had only the foundation's tests behind them: the OTP pairing wait, the
 * pairing link's question and the explainer before Android's unknown sources screen, each built
 * by the site and drawn by the foundation as a player meets it.
 */
@RunWith(RobolectricTestRunner::class)
// A handheld in landscape, where the question's two answers stand side by side.
@Config(sdk = [33], qualifiers = "w640dp-h360dp")
class NovaPairingAndUpdatePagesComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    @Test
    fun theOtpWaitIsAFullScreenBusyPageWhoseCloseHidesItPastTheGuard() {
        var closed = 0
        val page = novaOtpPairingWaitPage(rule.activity, "pairing") { closed++ }
        assertEquals(string(R.string.pair_pairing_title), page.title)
        assertEquals(string(R.string.pair_otp_pairing_help), page.message.value)
        assertEquals(string(R.string.nova_panel_close), page.cancel?.label)

        val keys = rule.setPanelContent { NovaStatePages(pages = listOf(page), onShowingChange = {}) }
        rule.mainClock.autoAdvance = false
        rule.advance(NovaPanelMetrics.BusyShowDelayMillis + 50)
        rule.frames(4)
        rule.onNodeWithText(string(R.string.pair_otp_pairing_help)).assertExists()
        rule.onNodeWithText(string(R.string.nova_panel_close)).assertIsFocused()

        // A press still held from the menu that started pairing does not hide it.
        keys.press(NovaTestKeys.CENTER)
        rule.frames(2)
        assertEquals(0, closed)

        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(2)
        assertEquals("Close hides the wait; pairing goes on", 1, closed)
    }

    @Test
    fun aPairingLinkAsksWithCancelFocusedAndPairsOnlyFromPair() {
        val paired = mutableListOf<String>()
        val page = novaPairLinkConfirmPage(rule.activity, "Living room (192.0.2.10)") { paired += "pair" }
        assertEquals(string(R.string.pair_pc_confirm_title), page.title)
        assertEquals(string(R.string.pair_pc_confirm_message, "Living room (192.0.2.10)"), page.message.text)
        assertFalse("pairing is not destructive", page.destructive)

        val state = NovaPanelState()
        var closes = 0
        state.open(page)
        val keys = rule.setPanelContent { NovaPageStackHost(state = state, onCloseRequest = { closes++ }) { } }
        rule.onNodeWithText(string(R.string.nova_panel_cancel)).assertIsFocused()

        keys.press(NovaTestKeys.RIGHT)
        rule.onNodeWithText(string(R.string.hosts_pair)).assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals(listOf("pair"), paired)
        assertEquals("the question leaves once answered", 1, closes)
    }

    @Test
    fun cancellingAPairingLinkPairsNothing() {
        val paired = mutableListOf<String>()
        val state = NovaPanelState()
        var closes = 0
        state.open(novaPairLinkConfirmPage(rule.activity, "test-pc") { paired += "pair" })
        val keys = rule.setPanelContent { NovaPageStackHost(state = state, onCloseRequest = { closes++ }) { } }

        keys.press(NovaTestKeys.CENTER)
        assertEquals("A on the focused Cancel pairs nothing", emptyList<String>(), paired)
        assertEquals(1, closes)
    }

    @Test
    fun theUnknownSourcesExplainerLeadsToAndroidsSettingAndCancelLeavesIt() {
        var opened = 0
        val page = NovaUpdateInstaller.unknownSourcesNotice(rule.activity) { opened++ }
        assertEquals(NovaUpdateInstaller.UNKNOWN_SOURCES_NOTICE_KEY, page.key)
        assertEquals(string(R.string.nova_update_permission_title), page.title)
        assertEquals(string(R.string.nova_update_permission_message), page.message)
        assertEquals(string(R.string.nova_update_open_android_settings), page.primary?.label)
        assertEquals("its close reads Cancel", string(R.string.nova_panel_cancel), page.closeLabel)

        val state = NovaPanelState()
        var closes = 0
        state.open(page)
        val keys = rule.setPanelContent { NovaPageStackHost(state = state, onCloseRequest = { closes++ }) { } }
        rule.onNodeWithText(string(R.string.nova_update_open_android_settings)).assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals("A opens Android's own screen, which stays Android's", 1, opened)
        assertEquals(1, closes)
    }

    @Test
    fun bLeavesTheUnknownSourcesExplainerWithoutOpeningAnything() {
        var opened = 0
        val state = NovaPanelState()
        var closes = 0
        state.open(NovaUpdateInstaller.unknownSourcesNotice(rule.activity) { opened++ })
        val keys = rule.setPanelContent { NovaPageStackHost(state = state, onCloseRequest = { closes++ }) { } }
        keys.back()
        assertEquals(0, opened)
        assertEquals(1, closes)
    }
}
