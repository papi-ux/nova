package com.papi.nova.ui

import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A host's sheet: who it is, the one thing you came for, then the rest, each saying what it does.
 *
 * papi, 2026-09-21: "that drawer needs a total revamp too", meaning this sheet. It was a title and
 * a flat list of labels under PLAY and MANAGE, where Open Library stood in a row like Test Network
 * Connection and "Compatibility App List" explained itself to nobody.
 */
class NovaHostSheetTest {

    private fun action(key: String) = NovaHostSheetAction(key, key, "what $key does", iconRes = 0)

    @Test
    fun theFirstThingOfferedToPlayIsThePrimaryAndTheRestAreTiles() {
        val ran = mutableListOf<String>()
        val menu = NovaHostSheetMenu()
        menu.play(action("watch")) { ran += "watch" }
        menu.play(action("open_library")) { ran += "open_library" }
        menu.manage(action("app_list")) { ran += "app_list" }
        menu.remove(action("delete")) { ran += "delete" }

        assertEquals("watch", menu.primary?.key)
        assertEquals(
            "a second thing to play is still offered, as the first tile under the primary",
            listOf("open_library", "app_list"),
            menu.actions.map { it.key },
        )
        assertEquals("removing the host stands apart from what manages it", "delete", menu.destructive?.key)

        menu.run("open_library")
        menu.run("delete")
        menu.run("nothing by this name")
        assertEquals(listOf("open_library", "delete"), ran)
    }

    @Test
    fun aHostWithNothingToPlayHasNoPrimary() {
        val menu = NovaHostSheetMenu()
        menu.manage(action("details")) { }
        assertNull(menu.primary)
        assertEquals(listOf("details"), menu.actions.map { it.key })
    }

    @Test
    fun tilesStandInRowsOfTheSheetsColumns() {
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), novaHostSheetRows(listOf(1, 2, 3, 4, 5), columns = 2))
        assertEquals(listOf(listOf(1), listOf(2)), novaHostSheetRows(listOf(1, 2), columns = 1))
        assertEquals("a sheet is never asked for no columns", listOf(listOf(1), listOf(2)), novaHostSheetRows(listOf(1, 2), columns = 0))
        val sheet = File("src/main/java/com/papi/nova/ui/NovaHostSheet.kt").readText()
        assertTrue(
            "a lone last tile keeps its column's width rather than stretching across the sheet",
            sheet.contains("repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }")
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
    }

    @Test
    fun theSheetIsBuiltInsideTheSharedChromeAndAControllerCanLeaveIt() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val sheet = pcView.substringAfter("private fun showServerBottomSheet(").substringBefore("private fun createWatchTargetApp(")
        assertTrue(
            "one sheet chrome for every sheet in the app, whatever draws inside it",
            sheet.contains("NovaSheetChrome.applyBottomSheetChrome(sheet, content)") &&
                sheet.contains("NovaHostSheet(") && sheet.contains("NovaComposeTheme {")
        )
        assertTrue(
            "dialogs map BACK but not a pad's B, and this sheet has no close control of its own",
            sheet.contains("keyCode == KeyEvent.KEYCODE_BUTTON_B && event.action == KeyEvent.ACTION_UP")
        )
        assertTrue(
            "the sheet leaves before its action runs, so what the action opens is not opened under it",
            sheet.indexOf("sheet.dismiss()\n                            menu.run(key)") > 0
        )
    }

    @Test
    fun anAwakeHostIsOfferedSleepOnlyWhereAHoldWouldWork() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val sheet = pcView.substringAfter("private fun showServerBottomSheet(").substringBefore("private fun createWatchTargetApp(")
        val online = sheet.substringAfter("if (details.runningGameId != 0) {").substringBefore("menu.manage(action(\"test_network\"")
        assertTrue(
            "the same host the dashboard's control answers for, and its own word that this device may",
            online.contains("details.uuid == preferredHostPowerComputer()?.uuid && currentHostPowerAction() == HostPowerAction.SLEEP") &&
                online.contains("beginHostSleep()")
        )
        assertFalse(
            "an awake host has nothing to be woken for: the row that said Wake Host here is gone, not renamed",
            online.contains("startPolarisFromNova(")
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
            // Two columns of a 660dp sheet hold about 48 characters of a caption on one line.
            assertTrue("${caption.groupValues[1]} keeps to a line: $text", text.length <= 52)
        }
    }
}
