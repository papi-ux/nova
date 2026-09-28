package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.NovaAppMenuActions
import com.papi.nova.R
import com.papi.nova.novaAppMenuHeader
import com.papi.nova.novaAppMenuItems
import com.papi.nova.novaPairingCodePage
import com.papi.nova.novaThemePickerPage
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaStateScreen
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Hosts screen's and the App list's panels, drawn by the foundation from the pages the screens
 * build: each opens on its primary or current value, splits a destructive row in place with Stay
 * focused (R3), pushes and pops a page with B returning to the row that opened it (R4, R7), and
 * changes a switch in place (R1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHostsPanelComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val state = NovaPanelState()
    private val ran = mutableListOf<String>()

    private val hostActions = object : NovaHostMenuActions {
        override fun wake() { ran += "wake" }
        override fun sendWakeOnLan() { ran += "send_wol" }
        override fun pair() { ran += "pair" }
        override fun otpPairPage(): NovaPage =
            NovaCommonPage.Form(key = "pair_otp", title = "OTP Pair", fields = emptyList(), submitLabel = "Pair") { null }
        override fun scanQr() { ran += "scan_qr" }
        override fun openServerConfig() { ran += "server_config" }
        override fun openLibrary() { ran += "open_library" }
        override fun checkLibrary() { ran += "checking_library" }
        override fun watch() { ran += "watch" }
        override fun resume() { ran += "resume" }
        override fun endSession() { ran += "end_session" }
        override fun sleep() { ran += "sleep" }
        override fun appList() { ran += "app_list" }
        override fun testNetwork() { ran += "test_network" }
        override fun delete() { ran += "delete" }
    }

    private fun string(id: Int) = rule.activity.getString(id)

    private fun host() = ComputerDetails().apply {
        name = "pc-papi"
        state = ComputerDetails.State.ONLINE
        pairState = PairingManager.PairState.PAIRED
        libraryState = ComputerDetails.LibraryState.AVAILABLE
    }

    private fun openHostMenu(): NovaTestKeys {
        val details = host()
        state.open(
            NovaCommonPage.Menu(
                key = "host",
                title = string(R.string.hosts_panel_host_title),
                items = novaHostMenuItems(rule.activity, details, false, false, hostActions, closePanel = state::close),
                header = novaHostMenuHeader(rule.activity, details),
            ),
        )
        return rule.setPanelContent { NovaPageStackHost(state = state) { } }
    }

    @Test
    fun theHostMenuOpensOnItsPrimaryAndDeleteSplitsInPlaceWithKeepFocused() {
        val keys = openHostMenu()
        rule.onNodeWithText(string(R.string.pcview_menu_nova_library)).assertIsFocused()

        // Open Library, App List, Server Config, Test Network, Details, then Delete PC.
        repeat(5) { keys.press(NovaTestKeys.DOWN) }
        rule.onNodeWithText(string(R.string.pcview_menu_delete_pc)).assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        rule.onNodeWithText(string(R.string.nova_panel_keep)).assertIsFocused()
        rule.onNodeWithText(string(R.string.hosts_delete_consequence)).assertExists()

        keys.back()
        rule.onNodeWithText(string(R.string.pcview_menu_delete_pc)).assertIsFocused()
        assertTrue("a single A never deletes a host", ran.isEmpty())
        assertTrue("B disarms the split and leaves the panel open", state.isOpen)
    }

    @Test
    fun detailsPushesAMonospaceNoticeAndBReturnsToDetails() {
        val keys = openHostMenu()
        repeat(4) { keys.press(NovaTestKeys.DOWN) }
        rule.onNodeWithText(string(R.string.pcview_menu_details)).assertIsFocused()

        keys.press(NovaTestKeys.CENTER)
        assertEquals(2, state.depth)
        assertTrue((state.top as NovaCommonPage.Notice).monospace)
        rule.onNodeWithText(string(R.string.nova_panel_close)).assertIsFocused()

        keys.back()
        assertEquals(1, state.depth)
        rule.onNodeWithText(string(R.string.pcview_menu_details)).assertIsFocused()
    }

    @Test
    fun theThemePageOpensOnTheCurrentThemeWithItsSwatchAndOneAApplies() {
        var chosen: String? = null
        val themes = listOf(
            NovaThemeManager.THEME_POLARIS,
            NovaThemeManager.THEME_PORTABLE_CHROME,
            NovaThemeManager.THEME_OLED,
            NovaThemeManager.THEME_MIAMI,
        )
        state.open(novaThemePickerPage(rule.activity, themes, NovaThemeManager.THEME_OLED) { chosen = it })
        val keys = rule.setPanelContent { NovaPageStackHost(state = state) { } }
        val oled = NovaThemeManager.getThemeLabel(rule.activity, NovaThemeManager.THEME_OLED)
        rule.onNodeWithText(oled).assertIsFocused()
        rule.onNodeWithText(oled).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onNodeWithText(string(R.string.hosts_theme_caption_oled)).assertExists()

        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.CENTER)
        assertEquals(NovaThemeManager.THEME_MIAMI, chosen)
    }

    @Test
    fun theAppMenuChangesHiddenInPlaceAndEndSplits() {
        val calls = mutableListOf<String>()
        val app = NvApp("Control Ultimate Edition", null, 7, false)
        val actions = object : NovaAppMenuActions {
            override fun start(withVirtualDisplay: Boolean) { calls += "start $withVirtualDisplay" }
            override fun confirmVirtualDisplayThenStart() { calls += "confirm" }
            override fun watch() { calls += "watch" }
            override fun resume() { calls += "resume" }
            override fun endSession() { calls += "end" }
            override fun quitAndStart() { calls += "quit_and_start" }
            override fun setHidden(hidden: Boolean) { calls += "hidden $hidden" }
            override fun setPinned(pinned: Boolean) { calls += "pinned $pinned" }
            override fun exportLauncher() { calls += "export" }
        }
        state.open(
            NovaCommonPage.Menu(
                key = "app",
                title = string(R.string.hosts_panel_app_title),
                items = novaAppMenuItems(
                    context = rule.activity,
                    app = app,
                    runningAppId = 0,
                    ownedByOtherClient = false,
                    useVirtualDisplay = true,
                    virtualDisplayReady = true,
                    hidden = false,
                    pinned = false,
                    actions = actions,
                ),
                header = novaAppMenuHeader(rule.activity, app, 0, false),
            ),
        )
        val keys = rule.setPanelContent { NovaPageStackHost(state = state) { } }
        rule.onNodeWithText(string(R.string.applist_menu_start_primarydisplay)).assertIsFocused()

        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText(string(R.string.applist_menu_hide_app)).assertExists()
        keys.press(NovaTestKeys.RIGHT)
        assertEquals("Right turns the switch on in place", listOf("hidden true"), calls)
        assertTrue("a switch never leaves the panel", state.isOpen)
    }

    @Test
    fun thePairingCodeShowsItsDigitsWithCloseFocused() {
        var closed = 0
        val page = novaPairingCodePage(rule.activity, "pairing", "4821") { closed++ }
        val keys = rule.setPanelContent { NovaStateScreen(page) }
        rule.onNodeWithText("4821").assertExists()
        rule.onNodeWithText(string(R.string.nova_panel_close)).assertIsFocused()

        keys.back()
        assertEquals("B hides the page as Close does", 1, closed)
    }
}
