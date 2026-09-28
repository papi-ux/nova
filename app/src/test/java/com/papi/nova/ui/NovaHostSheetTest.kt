package com.papi.nova.ui

import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaTone
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A host's menu: who it is, the one thing you came for, then the rest, each saying what it does.
 *
 * papi, 2026-09-21: "that drawer needs a total revamp too", meaning this menu. It was a title and
 * a flat list of labels under PLAY and MANAGE, where Open Library stood in a row like Test Network
 * Connection and "Compatibility App List" explained itself to nobody. Since the panel migration
 * (spec R4) it is one column in a right-edge panel, so the D-pad moves up and down only.
 */
class NovaHostSheetTest {

    private fun action(key: String) = NovaHostSheetAction(key, key, "what $key does", iconRes = 0)

    @Test
    fun theFirstThingOfferedToPlayIsThePrimaryAndRemovalIsLast() {
        val ran = mutableListOf<String>()
        val menu = NovaHostSheetMenu()
        menu.play(action("watch")) { ran += "watch" }
        menu.play(action("open_library")) { ran += "open_library" }
        menu.manage(action("app_list")) { ran += "app_list" }
        menu.remove(action("delete"), confirmLabel = "Delete PC", consequence = "gone", stayLabel = "Keep") { ran += "delete" }

        val items = menu.items
        assertEquals(listOf("watch", "open_library", "app_list", "delete"), items.map { it.key })
        assertTrue("the primary is the first row, filled with the accent", (items[0] as NovaMenuItem.Action).emphasis)
        assertFalse(
            "a second thing to play is still offered, as a plain row under the primary",
            (items[1] as NovaMenuItem.Action).emphasis,
        )
        assertTrue("removing the host splits in its own row", items[3] is NovaMenuItem.Destructive)
        assertEquals("and its safe half says what it keeps", "Keep", (items[3] as NovaMenuItem.Destructive).stayLabel)

        (items[1] as NovaMenuItem.Action).onClick()
        (items[3] as NovaMenuItem.Destructive).onConfirm()
        assertEquals(listOf("open_library", "delete"), ran)
    }

    @Test
    fun aRowThatLeavesTheMenuLetsTheScreenResumeFirstAndASplitClosesThePanel() {
        val events = mutableListOf<String>()
        val menu = NovaHostSheetMenu(closePanel = { events += "close" }, leave = { events += "leave" })
        menu.manage(action("app_list")) { events += "app_list" }
        menu.inPlace(action("test_network")) { events += "test_network" }
        menu.destructive(action("end_session"), confirmLabel = "End Session", consequence = "the game closes") {
            events += "end"
        }
        val items = menu.items

        (items[0] as NovaMenuItem.Action).onClick()
        assertEquals("polling comes back before the action, which may pause it again", listOf("leave", "app_list"), events)

        events.clear()
        val inPlace = items[1] as NovaMenuItem.Action
        assertFalse("the network test pushes its own page, so the panel stays", inPlace.closesPanel)
        inPlace.onClick()
        assertEquals(listOf("test_network"), events)

        events.clear()
        (items[2] as NovaMenuItem.Destructive).onConfirm()
        assertEquals(
            "a confirmed split closes the panel before what it starts, so nothing opens under it",
            listOf("close", "leave", "end"),
            events,
        )
    }

    @Test
    fun theHeaderReadsAHostTheWayItsCardDoes() {
        fun host(edit: ComputerDetails.() -> Unit) = ComputerDetails().apply {
            state = ComputerDetails.State.ONLINE
            pairState = PairingManager.PairState.PAIRED
            edit()
        }

        val offline = novaHostSheetCopy(host { state = ComputerDetails.State.OFFLINE; macAddress = "00:11:22:33:44:55" })
        assertEquals(R.string.pcview_card_status_offline, offline.statusRes)
        assertEquals(R.string.pcview_card_hint_wake, offline.hintRes)
        assertEquals(NovaHostSheetTone.QUIET, offline.tone)
        assertEquals(
            R.string.pcview_card_hint_offline_no_wake,
            novaHostSheetCopy(host { state = ComputerDetails.State.OFFLINE }).hintRes,
        )
        assertEquals(
            R.string.pcview_card_status_connecting,
            novaHostSheetCopy(host { state = ComputerDetails.State.UNKNOWN }).statusRes,
        )

        val unpaired = novaHostSheetCopy(host { pairState = PairingManager.PairState.NOT_PAIRED })
        assertEquals(R.string.pcview_card_status_pair_required, unpaired.statusRes)
        assertEquals(R.string.pcview_card_hint_pair, unpaired.hintRes)
        assertEquals("a host that wants pairing is the one state that asks something of you", NovaHostSheetTone.ATTENTION, unpaired.tone)
        assertEquals(
            "paired, but the certificate it was paired with is gone",
            R.string.pcview_card_hint_pair_repair,
            novaHostSheetCopy(host { serverCert = null }).hintRes,
        )
        assertEquals("ready reads in the accent", NovaTone.Active, NovaHostSheetTone.READY.panelTone)
        assertEquals("wanting something reads as a warning", NovaTone.Warning, NovaHostSheetTone.ATTENTION.panelTone)
        assertEquals(NovaTone.Neutral, NovaHostSheetTone.QUIET.panelTone)
    }

    @Test
    fun theMenuIsAPanelPageAndAControllerCanLeaveIt() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val panel = pcView.substringAfter("private fun showHostPanel(").substringBefore("private fun createWatchTargetApp(")
        assertTrue(
            "the host menu is the first page of a right-edge panel, not a sheet of its own",
            panel.contains("NovaCommonPage.Menu(") &&
                panel.contains("surfaces.open(menu, NovaEdge.End,") &&
                !pcView.contains("BottomSheetDialog")
        )
        assertTrue(
            "the panel's gate turns B into Back on release, so the menu needs no key listener of its own",
            !panel.contains("setOnKeyListener") && !panel.contains("KEYCODE_BUTTON_B")
        )
        assertTrue(
            "focus goes back to the card or Manage that opened it",
            panel.contains("currentFocus?.let { NovaFocusReturn.View(it) }")
        )
        assertTrue(
            "polling waits while the menu is open and resumes when it closes, as it did under the sheet",
            panel.contains("stopComputerUpdates(false)") &&
                panel.contains("snapshotFlow { surfaces.panel.isOpen }.first { !it }") &&
                pcView.contains("private fun leaveHostPanel()")
        )
    }

    @Test
    fun anAwakeHostIsOfferedSleepOnlyWhereAHoldWouldWork() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val menu = File("src/main/java/com/papi/nova/ui/NovaHostSheet.kt").readText()
        assertTrue(
            "the same host the dashboard's control answers for, and its own word that this device may",
            pcView.contains("val sleepOffered = details.uuid == preferredHostPowerComputer()?.uuid && currentHostPowerAction() == HostPowerAction.SLEEP") &&
                pcView.contains("override fun sleep() = beginHostSleep()")
        )
        val online = menu.substringAfter("if (details.runningGameId != 0) {").substringBefore("menu.inPlace(action(\"test_network\"")
        assertTrue(online.contains("if (sleepOffered) {"))
        assertFalse(
            "an awake host has nothing to be woken for: the row that said Wake Host here is gone, not renamed",
            online.contains("actions.wake()")
        )
    }

    @Test
    fun everyActionSaysWhatItDoes() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val captions = Regex("<string name=\"(pcview_sheet_caption_[a-z_]+)\">([^<]+)</string>").findAll(strings).toList()
        assertEquals(17, captions.size)
        captions.forEach { caption ->
            val text = caption.groupValues[2].replace("\\'", "'")
            assertTrue("${caption.groupValues[1]} is a sentence: $text", text.endsWith("."))
            // A caption keeps to two lines of a Standard panel (spec R13); about 52 characters does.
            assertTrue("${caption.groupValues[1]} keeps to two lines: $text", text.length <= 52)
        }
    }
}
