package com.papi.nova

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaFieldKind
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaTone
import com.papi.nova.ui.panel.NovaValueStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pages the Hosts screen and the App list build for the panel foundation: the theme picker, OTP
 * pairing, the pairing PIN and the app menu for each state an app can be in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHostsPagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val calls = mutableListOf<String>()

    private val appActions = object : NovaAppMenuActions {
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

    private val app = NvApp("Control Ultimate Edition", null, 7, false)

    private fun appMenu(
        runningAppId: Int = 0,
        ownedByOtherClient: Boolean = false,
        useVirtualDisplay: Boolean = false,
        virtualDisplayReady: Boolean = true,
        hidden: Boolean = false,
        closePanel: () -> Unit = {},
    ) = novaAppMenuItems(
        context = context,
        app = app,
        runningAppId = runningAppId,
        ownedByOtherClient = ownedByOtherClient,
        useVirtualDisplay = useVirtualDisplay,
        virtualDisplayReady = virtualDisplayReady,
        hidden = hidden,
        pinned = false,
        actions = appActions,
        closePanel = closePanel,
    )

    @Test
    fun theThemePageListsThemesInTheArraysOrderWithTheCurrentOneMarked() {
        val themes = context.resources.getStringArray(R.array.nova_theme_values).toList()
        val page = novaThemePickerPage(context, themes, NovaThemeManager.THEME_MIAMI) { }
        assertEquals(themes, page.options.map { it.value })
        assertEquals(NovaThemeManager.THEME_MIAMI, page.current)
        assertTrue("every theme shows its swatch", page.leading != null)
        page.options.forEach { option ->
            assertEquals(context.getString(novaThemePickerCaption(option.value)), option.caption)
            assertEquals(NovaThemeManager.getThemeLabel(context, option.value), option.label)
        }
    }

    @Test
    fun otpPairingKeepsAShortPinOrPassphraseAndPairsAGoodOne() {
        val paired = mutableListOf<Pair<String, String>>()
        val page = novaOtpPairPage(context) { pin, passphrase -> paired += pin to passphrase }
        assertEquals(listOf(NovaFieldKind.Number, NovaFieldKind.Password), page.fields.map { it.kind })
        assertEquals("the PIN is four digits", 4, page.fields.first().maxLength)

        assertEquals(context.getString(R.string.pair_pin_length_msg), page.onSubmit(mapOf("pin" to "12", "passphrase" to "secret")))
        assertEquals(context.getString(R.string.pair_passphrase_length_msg), page.onSubmit(mapOf("pin" to "1234", "passphrase" to "abc")))
        assertTrue("nothing pairs until both are right", paired.isEmpty())

        assertNull(page.onSubmit(mapOf("pin" to "1234", "passphrase" to "secret")))
        assertEquals(listOf("1234" to "secret"), paired)
    }

    @Test
    fun thePairingPinPageShowsTheCodeAndItsCloseHidesIt() {
        var hidden = 0
        val page = novaPairingCodePage(context, "pairing", "4821") { hidden++ }
        assertEquals("4821", page.code)
        assertEquals(context.getString(R.string.hosts_pairing_code_title), page.title)
        assertEquals(context.getString(R.string.nova_panel_close), page.close.label)
        page.close.run()
        assertEquals(1, hidden)
    }

    @Test
    fun anIdleHostStartsTheAppAndAVirtualDisplayThatIsNotReadyAsksInThePanel() {
        assertEquals(
            listOf("start_host_display", "hidden", "pinned", "details", "export_launcher"),
            appMenu(useVirtualDisplay = true).map { it.key },
        )
        val notReady = appMenu(virtualDisplayReady = false).first() as NovaMenuItem.Action
        assertEquals("start_virtual_display", notReady.key)
        assertFalse("the confirm is pushed in this panel, so the panel stays", notReady.closesPanel)
        notReady.onClick()
        assertEquals(listOf("confirm"), calls)

        val ready = appMenu(virtualDisplayReady = true).first() as NovaMenuItem.Action
        assertTrue(ready.closesPanel)
        ready.onClick()
        assertEquals(listOf("confirm", "start true"), calls)
    }

    @Test
    fun theRunningAppResumesAndItsEndSplitsAndClosesThePanelFirst() {
        val events = mutableListOf<String>()
        val items = appMenu(runningAppId = 7, closePanel = { events += "close" })
        assertEquals(listOf("resume", "end_session", "pinned", "details", "export_launcher"), items.map { it.key })
        val end = items[1] as NovaMenuItem.Destructive
        assertEquals(context.getString(R.string.game_dialog_action_end_session), end.confirmLabel)
        assertEquals(context.getString(R.string.nova_panel_end_session_message), end.consequence)
        end.onConfirm()
        assertEquals(listOf("close"), events)
        assertEquals(listOf("end"), calls)
        assertTrue(
            "a hidden running app can still be shown again",
            appMenu(runningAppId = 7, hidden = true).any { it.key == "hidden" },
        )
    }

    @Test
    fun anotherGameRunningMeansQuitAndStartSplitsAndAnotherDevicesStreamIsWatched() {
        val quit = appMenu(runningAppId = 3).first() as NovaMenuItem.Destructive
        assertEquals("quit_and_start", quit.key)
        assertEquals(context.getString(R.string.hosts_quit_and_start_confirm), quit.confirmLabel)

        assertEquals("watch", appMenu(runningAppId = 7, ownedByOtherClient = true).first().key)
        assertEquals("watch_active", appMenu(runningAppId = 3, ownedByOtherClient = true).first().key)
    }

    @Test
    fun hiddenAndPinnedAreSwitchesAndDetailsIsAMonospaceNotice() {
        val items = appMenu()
        val hidden = items.first { it.key == "hidden" } as NovaMenuItem.Value<*>
        assertEquals(NovaValueStyle.Switch, hidden.style)
        assertEquals(false, hidden.current)
        @Suppress("UNCHECKED_CAST")
        (items.first { it.key == "pinned" } as NovaMenuItem.Value<Boolean>).onChange(true)
        assertEquals(listOf("pinned true"), calls)

        val details = (items.first { it.key == "details" } as NovaMenuItem.Opens).page() as NovaCommonPage.Notice
        assertTrue(details.monospace)
        assertEquals(app.toString(), details.message)
    }

    @Test
    fun theAppHeaderSaysWhoIsPlayingIt() {
        assertNull(novaAppMenuHeader(context, app, 0, false).status)
        val running = novaAppMenuHeader(context, app, 7, false)
        assertEquals(context.getString(R.string.hosts_app_status_running), running.status)
        assertEquals(NovaTone.Active, running.tone)
        assertEquals(context.getString(R.string.hosts_app_status_watchable), novaAppMenuHeader(context, app, 7, true).status)
    }
}
