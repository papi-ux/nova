package com.papi.nova.binding.input.virtual_controller.keyboard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-screen keys editor over the stream: Clear All confirms in its own slot, and Add Keys is a
 * page of toggles in the stream's panel, never a platform alert over the game.
 */
class KeyBoardControllerEditorSourceGuardTest {
    private val source = File("src/main/java/com/papi/nova/binding/input/virtual_controller/keyboard/KeyBoardController.kt").readText()

    @Test
    fun clearAllSplitsInItsOwnSlotAndSaysWhatItDoes() {
        assertFalse("no platform alert is raised over the stream", source.contains("AlertDialog"))
        assertTrue(
            "Clear All is a split: armed first, confirmed after its guard, with the consequence under it",
            source.contains("NovaSplitConfirm(") &&
                source.contains("state = clearAllSplit") &&
                source.contains("R.string.nova_stream_keys_clear_consequence") &&
                source.contains("onConfirm = ::clearAllKeys")
        )
        assertTrue(
            "leaving the enable and disable mode takes an armed Clear All back",
            source.contains("if (!show) clearAllSplit.disarm(restoreFocus = false)")
        )
        assertFalse("the grey stock buttons are gone", source.contains("Color.DKGRAY"))
    }

    @Test
    fun theEditorIsTouchOnlyAndLeavesThePadToTheStream() {
        assertTrue(
            "the editor sits on the stream window, where the stream container's focus is how the pad " +
                "reaches the host: neither the view nor its controls may take focus from it",
            source.contains("descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS") &&
                source.contains(".focusProperties { canFocus = false }")
        )
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        assertTrue(
            "the Command Center takes an armed Clear All back as it opens",
            game.section("fun showGameMenuFromDisplay(", "val companionPresentation")
                .contains("keyBoardController?.disarmEditControls()") &&
                source.contains("fun disarmEditControls()")
        )
    }

    private fun String.section(start: String, end: String): String {
        val from = indexOf(start)
        require(from >= 0) { "Missing start marker: $start" }
        val to = indexOf(end, from)
        require(to >= 0) { "Missing end marker: $end" }
        return substring(from, to)
    }

    @Test
    fun addKeysIsAPageOfTogglesThatStartsWithNothingChosen() {
        assertTrue(
            "Add Keys presents a MultiChoice page in the stream's panel, every key starting off",
            source.contains("NovaSurfaces.of(activity).present(") &&
                source.contains("NovaCommonPage.MultiChoice(") &&
                source.contains("selected = emptySet()") &&
                source.contains("doneLabel = context.getString(R.string.keyboard_add)")
        )
        assertTrue(
            "the page's choices are the list positions the old dialog checked, so the same keys are added",
            source.contains("addSelectedKeys(BooleanArray(keyNamesList.size) { it in chosen }, allItemsList)")
        )
        assertTrue(
            "custom keys are read from the special keys' store, whose names did not move",
            source.contains("NovaSpecialKeyPrefs.PREF_NAME") && source.contains("NovaSpecialKeyPrefs.KEY_NAME")
        )
    }
}
