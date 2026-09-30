package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.width
import com.papi.nova.NovaAppMenuActions
import com.papi.nova.R
import com.papi.nova.novaAppMenuHeader
import com.papi.nova.novaAppMenuItems
import com.papi.nova.novaPairingCodePage
import com.papi.nova.novaThemePickerPage
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.ui.panel.LocalNovaPanelDensity
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelDensity
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaPanelWidth
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.advance
import com.papi.nova.ui.panel.frames
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
        override fun hostConsolePage(): NovaPage =
            NovaCommonPage.Notice(key = "host-console", title = "Host Console", message = "", closeLabel = "Close")
        override fun openLibrary() { ran += "open_library" }
        override fun checkLibrary() { ran += "checking_library" }
        override fun watch() { ran += "watch" }
        override fun resume() { ran += "resume" }
        override fun endSession() { ran += "end_session" }
        override fun sleep() { ran += "sleep" }
        override fun appList() { ran += "app_list" }
        override fun testNetwork() { ran += "test_network" }
        override fun editWakeAddress() { ran += "wake_address" }
        override fun delete() { ran += "delete" }
    }

    private fun string(id: Int) = rule.activity.getString(id)

    private fun host() = ComputerDetails().apply {
        name = "pc-papi"
        state = ComputerDetails.State.ONLINE
        pairState = PairingManager.PairState.PAIRED
        libraryState = ComputerDetails.LibraryState.AVAILABLE
    }

    private fun openHostMenu(compact: Boolean = false): NovaTestKeys {
        val details = host()
        state.open(
            NovaCommonPage.Menu(
                key = "host",
                title = string(R.string.hosts_panel_host_title),
                items = novaHostMenuItems(rule.activity, details, false, false, hostActions, closePanel = state::close),
                header = novaHostMenuHeader(rule.activity, details),
                width = NovaPanelWidth.Grid,
            ),
        )
        return rule.setPanelContent { atDensity(compact) { NovaPageStackHost(state = state) { } } }
    }

    /** A landscape handheld's panel ([NovaPanelDensity.Compact]) when [compact], else the regular one. */
    @Composable
    private fun atDensity(compact: Boolean, content: @Composable () -> Unit) {
        val density = if (compact) NovaPanelDensity.Compact else NovaPanelDensity.Regular
        CompositionLocalProvider(LocalNovaPanelDensity provides density, content = content)
    }

    private fun bounds(label: Int) = rule.onNodeWithText(string(label)).getUnclippedBoundsInRoot()

    @Test
    fun onALandscapeHandheldTheHostMenuGoesTwoToALineInReadingOrder() {
        val keys = openHostMenu(compact = true)
        rule.onNodeWithText(string(R.string.pcview_menu_nova_library)).assertIsFocused()

        // Open Library, the primary, keeps a line of its own, and the tiles share theirs.
        val library = bounds(R.string.pcview_menu_nova_library)
        val appList = bounds(R.string.pcview_menu_app_list)
        val serverConfig = bounds(R.string.pcview_menu_open_management_page)
        val testNetwork = bounds(R.string.pcview_menu_test_network)
        assertTrue("a tile is half a line: $appList in $library", appList.width < library.width * 0.55f)
        assertEquals("App List and Server Config share a line", appList.top.value, serverConfig.top.value, 0.5f)
        assertTrue(serverConfig.left > appList.right)
        assertEquals("the next pair starts under the first", appList.left.value, testNetwork.left.value, 0.5f)
        assertTrue(testNetwork.top > appList.bottom)

        // The D-pad walks it line by line.
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText(string(R.string.pcview_menu_app_list)).assertIsFocused()
        keys.press(NovaTestKeys.RIGHT)
        rule.onNodeWithText(string(R.string.pcview_menu_open_management_page)).assertIsFocused()
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText(string(R.string.wol_address_title)).assertIsFocused()
        keys.press(NovaTestKeys.LEFT)
        rule.onNodeWithText(string(R.string.pcview_menu_test_network)).assertIsFocused()
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText(string(R.string.pcview_menu_details)).assertIsFocused()

        // Behind the tile is the same page the row opened, and B comes back to the tile.
        keys.press(NovaTestKeys.CENTER)
        assertTrue(state.top is NovaCommonPage.Notice)
        keys.back()
        rule.onNodeWithText(string(R.string.pcview_menu_details)).assertIsFocused()

        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText(string(R.string.pcview_menu_delete_pc)).assertIsFocused()
        // Delete PC splits in place, so it keeps a whole line for its pair and its warning (R3).
        val delete = bounds(R.string.pcview_menu_delete_pc)
        assertEquals(library.width.value, delete.width.value, 0.5f)
    }

    @Test
    fun onATallerScreenTheHostMenuStaysOneColumn() {
        openHostMenu(compact = false)
        val appList = bounds(R.string.pcview_menu_app_list)
        val serverConfig = bounds(R.string.pcview_menu_open_management_page)
        assertEquals(appList.left.value, serverConfig.left.value, 0.5f)
        assertTrue(serverConfig.top > appList.bottom)
    }

    @Test
    fun onALandscapeHandheldTheThemesGoTwoToALineAndOneAApplies() {
        var chosen: String? = null
        val themes = listOf(
            NovaThemeManager.THEME_POLARIS,
            NovaThemeManager.THEME_PORTABLE_CHROME,
            NovaThemeManager.THEME_OLED,
            NovaThemeManager.THEME_MIAMI,
        )
        state.open(novaThemePickerPage(rule.activity, themes, NovaThemeManager.THEME_OLED) { chosen = it })
        val keys = rule.setPanelContent { atDensity(compact = true) { NovaPageStackHost(state = state) { } } }
        fun label(theme: String) = NovaThemeManager.getThemeLabel(rule.activity, theme)
        rule.onNodeWithText(label(NovaThemeManager.THEME_OLED)).assertIsFocused()

        val polaris = rule.onNodeWithText(label(NovaThemeManager.THEME_POLARIS)).getUnclippedBoundsInRoot()
        val chrome = rule.onNodeWithText(label(NovaThemeManager.THEME_PORTABLE_CHROME)).getUnclippedBoundsInRoot()
        val oled = rule.onNodeWithText(label(NovaThemeManager.THEME_OLED)).getUnclippedBoundsInRoot()
        assertEquals(polaris.top.value, chrome.top.value, 0.5f)
        assertEquals("OLED starts the second line", polaris.left.value, oled.left.value, 0.5f)
        assertTrue(oled.top > polaris.bottom)

        keys.press(NovaTestKeys.RIGHT)
        rule.onNodeWithText(label(NovaThemeManager.THEME_MIAMI)).assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals(NovaThemeManager.THEME_MIAMI, chosen)
    }

    @Test
    fun theHostMenuOpensOnItsPrimaryAndDeleteSplitsInPlaceWithKeepFocused() {
        val keys = openHostMenu()
        rule.onNodeWithText(string(R.string.pcview_menu_nova_library)).assertIsFocused()

        // Open Library, App List, Server Config, Test Network, Wake address, Details, then Delete.
        repeat(6) { keys.press(NovaTestKeys.DOWN) }
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
    fun deletePcIsConfirmedByARightAAfterTheGuardAndDeletesTheHost() {
        val keys = openHostMenu()
        rule.mainClock.autoAdvance = false
        repeat(6) { keys.press(NovaTestKeys.DOWN) }
        rule.frames(4)
        rule.onNodeWithText(string(R.string.pcview_menu_delete_pc)).assertIsFocused()

        keys.press(NovaTestKeys.CENTER)
        rule.frames(8)
        rule.onNodeWithText(string(R.string.nova_panel_keep)).assertIsFocused()
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(4)
        assertTrue("inside the guard the Delete half ignores A", ran.isEmpty())

        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(4)
        assertEquals("the confirmed half deletes the host, and only that", listOf("delete"), ran)
    }

    @Test
    fun detailsPushesANoticeAndBReturnsToDetails() {
        val keys = openHostMenu()
        repeat(5) { keys.press(NovaTestKeys.DOWN) }
        rule.onNodeWithText(string(R.string.pcview_menu_details)).assertIsFocused()

        keys.press(NovaTestKeys.CENTER)
        assertEquals(2, state.depth)
        assertTrue(state.top is NovaCommonPage.Notice)
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
