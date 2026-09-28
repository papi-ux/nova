package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The host menu's rows for each state a host can be in (spec 9.1, group 2): which rows it offers,
 * in what order, and which kind of row each is. Destructive rows split in place (R3), rows that
 * push a page keep the panel open (R4), and everything else closes it before it acts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHostMenuTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ran = mutableListOf<String>()

    private val actions = object : NovaHostMenuActions {
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

    private fun host(edit: ComputerDetails.() -> Unit = {}) = ComputerDetails().apply {
        name = "pc-papi"
        state = ComputerDetails.State.ONLINE
        pairState = PairingManager.PairState.PAIRED
        libraryState = ComputerDetails.LibraryState.AVAILABLE
        edit()
    }

    private fun menu(details: ComputerDetails, needsPairing: Boolean = false, sleepOffered: Boolean = false) =
        novaHostMenuItems(context, details, needsPairing, sleepOffered, actions)

    private fun List<NovaMenuItem>.keys() = map { it.key }

    @Test
    fun anOfflineHostOffersWakingItFirst() {
        val items = menu(host { state = ComputerDetails.State.OFFLINE })
        assertEquals(listOf("wake", "send_wol", "test_network", "details", "delete"), items.keys())
        assertTrue((items[0] as NovaMenuItem.Action).emphasis)
        assertEquals(context.getString(R.string.pcview_menu_start_polaris), (items[0] as NovaMenuItem.Action).label)
    }

    @Test
    fun anOfflineHostThatWantsPairingCanOnlyBeSentAWakePacket() {
        val items = menu(host { state = ComputerDetails.State.OFFLINE }, needsPairing = true)
        assertEquals(listOf("send_wol", "test_network", "details", "delete"), items.keys())
        assertTrue("the wake packet is the primary when it is all there is", (items[0] as NovaMenuItem.Action).emphasis)
    }

    @Test
    fun anUnpairedHostOffersPairingAndOtpPairingIsAFormPage() {
        val items = menu(host { pairState = PairingManager.PairState.NOT_PAIRED }, needsPairing = true)
        assertEquals(listOf("pair", "pair_otp", "scan_qr", "server_config", "test_network", "details", "delete"), items.keys())
        val otp = items[1] as NovaMenuItem.Opens
        assertTrue("OTP pairing pushes its form in the host panel", otp.page() is NovaCommonPage.Form)
    }

    @Test
    fun anNvidiaHostHasNoServerConfigRow() {
        val items = menu(host { pairState = PairingManager.PairState.NOT_PAIRED; nvidiaServer = true }, needsPairing = true)
        assertFalse(items.keys().contains("server_config"))
    }

    @Test
    fun anIdleHostWithALibraryLeadsWithTheLibrary() {
        val items = menu(host())
        assertEquals(listOf("open_library", "app_list", "server_config", "test_network", "details", "delete"), items.keys())
        assertTrue((items[0] as NovaMenuItem.Action).emphasis)
    }

    @Test
    fun aHostStillCheckingItsLibraryOffersToAskAgain() {
        val items = menu(host { libraryState = ComputerDetails.LibraryState.UNKNOWN })
        assertEquals("checking_library", items.first().key)
    }

    @Test
    fun aStreamThisDeviceLeftRunningLeadsWithResumeAndEndSplits() {
        val items = menu(
            host {
                runningGameId = 42
                currentGameOwnedByClient = true
                libraryState = ComputerDetails.LibraryState.UNAVAILABLE
            },
        )
        assertEquals(listOf("resume", "end_session", "app_list", "server_config", "test_network", "details", "delete"), items.keys())
        val end = items[1] as NovaMenuItem.Destructive
        assertEquals(context.getString(R.string.game_dialog_action_end_session), end.confirmLabel)
        assertEquals("ending a session stays on Stay", null, end.stayLabel)
        assertEquals(context.getString(R.string.nova_panel_end_session_message), end.consequence)
        end.onConfirm()
        assertEquals(listOf("end_session"), ran)
    }

    @Test
    fun anAwakeHostIsOfferedSleepOnlyWhenAHoldWouldWork() {
        assertFalse(menu(host()).keys().contains("sleep"))
        assertTrue(menu(host(), sleepOffered = true).keys().contains("sleep"))
        assertFalse("an awake host is never offered waking", menu(host(), sleepOffered = true).keys().contains("wake"))
    }

    @Test
    fun theNetworkTestStaysInThePanelAndDetailsIsAMonospaceNotice() {
        val items = menu(host())
        val test = items.first { it.key == "test_network" } as NovaMenuItem.Action
        assertFalse(test.closesPanel)
        test.onClick()
        assertEquals(listOf("test_network"), ran)

        val details = (items.first { it.key == "details" } as NovaMenuItem.Opens).page() as NovaCommonPage.Notice
        assertTrue(details.monospace)
        assertEquals(context.getString(R.string.title_details), details.title)
    }

    @Test
    fun deletingSaysWhatItDoesBeyondThisDevice() {
        fun consequence(details: ComputerDetails) =
            (menu(details).last() as NovaMenuItem.Destructive).consequence

        // Without a pinned certificate there is nobody to ask the host to forget this device.
        assertEquals(context.getString(R.string.hosts_delete_consequence), consequence(host { serverCert = null }))
        assertEquals(R.string.hosts_delete_consequence, novaHostDeleteConsequence(host { serverCert = null }))
        val removal = menu(host()).last() as NovaMenuItem.Destructive
        assertEquals("delete", removal.key)
        assertEquals(context.getString(R.string.pcview_menu_delete_pc), removal.confirmLabel)
        assertEquals("deleting keeps the host on Keep (spec 9.3, row 2)", context.getString(R.string.nova_panel_keep), removal.stayLabel)

        // Its labels are not enough: confirming it has to delete the host, and nothing else.
        removal.onConfirm()
        assertEquals(listOf("delete"), ran)
    }

    @Test
    fun theHeaderNamesTheHostAndReadsItsStateInItsTone() {
        val header = novaHostMenuHeader(context, host { state = ComputerDetails.State.OFFLINE; macAddress = "00:11:22:33:44:55" })
        assertEquals("pc-papi", header.title)
        assertEquals(context.getString(R.string.pcview_card_status_offline), header.status)
        assertEquals(NovaTone.Neutral, header.tone)
        assertEquals(context.getString(R.string.pcview_card_hint_wake), header.hint)
        assertEquals(R.drawable.ic_computer, header.icon)
    }
}
