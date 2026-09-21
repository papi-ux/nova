package com.papi.nova.grid

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * A host's card says who the host is and what state it is in, and none of that may be cut.
 *
 * papi, 2026-09-21: "if theres a description and its running off, theres no point if the user cant
 * view the detail". On a 4:3 handheld the card's advice read "Spaces availab…", on a phone held
 * upright "Change Space fr…", and a Sunshine host's advice is a hundred characters long and was cut
 * on every screen Nova runs on. Nothing on the card opens to show the rest.
 */
class NovaHostCardLayoutTest {

    @Test
    fun theActionsDropUnderTheTextWhenTheCardIsNarrow() {
        // Measured on a Retroid Pocket 6 at 369dpi.
        assertFalse("16:9, 555dp card: text keeps 310dp beside the actions", novaHostCardStacks(555f, fontScale = 0.85f))
        assertTrue("4:3, 347dp card: beside the actions the text had 215dp", novaHostCardStacks(347f, fontScale = 0.85f))
        assertTrue("3:4 upright, 428dp card", novaHostCardStacks(428f, fontScale = 0.85f))
        assertTrue("a 412dp phone held upright", novaHostCardStacks(372f, fontScale = 1f))
        assertFalse("the same phone on its side", novaHostCardStacks(660f, fontScale = 1f))
        // Larger type needs the room sooner; smaller type does not buy the actions their place back.
        assertTrue(novaHostCardStacks(555f, fontScale = 1.3f))
        assertTrue(novaHostCardStacks(499f, fontScale = 0.85f))
        assertFalse(novaHostCardStacks(NOVA_HOST_CARD_SIDE_BY_SIDE_MIN_DP, fontScale = 1f))
    }

    @Test
    fun theCardIsBuiltSoTheAdapterCanTurnIt() {
        val card = views("src/main/res/layout/pc_grid_item.xml")
        val body = card.getValue("server_card_body")
        val identity = card.getValue("server_card_identity")
        val actions = card.getValue("server_card_actions")

        assertEquals("horizontal", body.getAttribute("android:orientation"))
        assertEquals("the text takes what the actions leave", "1", identity.getAttribute("android:layout_weight"))
        assertEquals("0dp", identity.getAttribute("android:layout_width"))
        assertEquals("wrap_content", actions.getAttribute("android:layout_width"))
        assertEquals(
            "both actions move together, so they are one group",
            listOf("primary_action_text", "server_actions_button"),
            actions.childElements().map { it.getAttribute("android:id").substringAfter('/') },
        )
        assertTrue(
            "the icon and the three lines stay together when the card turns",
            identity.descendantIds().containsAll(listOf("grid_image", "grid_text", "status_text", "status_hint_text"))
        )
    }

    @Test
    fun noLineOfTheCardIsCutToOne() {
        val card = views("src/main/res/layout/pc_grid_item.xml")
        assertEquals(
            "the advice is a sentence, and a Sunshine host's runs to a hundred characters",
            "3",
            card.getValue("status_hint_text").getAttribute("android:maxLines"),
        )
        assertEquals(
            "a host is known by its name, and a long one keeps a second line",
            "2",
            card.getValue("grid_text").getAttribute("android:maxLines"),
        )
        assertEquals(
            "the status wraps where it must, so it sets no limit of its own",
            "",
            card.getValue("status_text").getAttribute("android:maxLines"),
        )
    }

    @Test
    fun theAdapterArrangesTheCardAndBreaksItsStatusAtTheDot() {
        val adapter = File("src/main/java/com/papi/nova/grid/PcGridAdapter.kt").readText()
        assertTrue(
            "every bind arranges the card for the width it has",
            adapter.contains("        fitCardToWidth(parentView, pcHolder)\n")
        )
        assertTrue(
            "and a change of width arranges it again, after the layout pass that reported it",
            adapter.contains("view.post { applyCardArrangement(holder, right - left) }")
        )
        assertTrue(
            "the width is the list's when the card has not been laid out yet, so the first frame is already right",
            adapter.contains("(parentView.parent as? View)?.let { it.width - it.paddingLeft - it.paddingRight }")
        )
        assertEquals(
            "\"Library ready ·\" over \"10.0.0.232\" read as damage: both status formats break only after their dot",
            2,
            adapter.split("statusText.text = novaBreakAtDots(context.getString(").size - 1,
        )
    }

    private fun views(path: String): Map<String, Element> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val found = LinkedHashMap<String, Element>()
        fun walk(element: Element) {
            val id = element.getAttribute("android:id")
            if (id.isNotEmpty()) found[id.substringAfter('/')] = element
            element.childElements().forEach(::walk)
        }
        walk(document.documentElement)
        return found
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Element.descendantIds(): List<String> =
        childElements().flatMap { child ->
            listOfNotNull(child.getAttribute("android:id").substringAfter('/').takeIf { it.isNotEmpty() }) +
                child.descendantIds()
        }
}
