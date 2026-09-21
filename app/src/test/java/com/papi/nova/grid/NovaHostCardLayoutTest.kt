package com.papi.nova.grid

import com.papi.nova.nvstream.http.ComputerDetails.LibraryState
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

    /**
     * papi, 2026-09-21, of the card: "seems kind of bland here". It was an outline icon, an 8dp
     * dot and three lines of grey beside two identical outlines, the same whatever the host was
     * doing. What it says has not changed; how much of it can be read at a glance has.
     */
    @Test
    fun theStateIsALampOnTheHostsMark() {
        val card = views("src/main/res/layout/pc_grid_item.xml")
        val ring = card.getValue("status_dot_ring")
        assertEquals(
            "the lamp stands on the corner of the well, inside a ring that keeps it off the well's colour",
            listOf("status_dot"),
            ring.childElements().map { it.getAttribute("android:id").substringAfter('/') },
        )
        assertEquals("bottom|end", ring.getAttribute("android:layout_gravity"))
        assertEquals(
            "the well is what the adapter tints, and the icon, overlay and spinner all stand in it",
            listOf("grid_image", "grid_overlay", "grid_spinner"),
            card.getValue("grid_image_layout").childElements().map { it.getAttribute("android:id").substringAfter('/') },
        )
        assertEquals(
            "the status is set as a label, so it reads before the sentence under it",
            "true",
            card.getValue("status_text").getAttribute("android:textAllCaps"),
        )

        val adapter = File("src/main/java/com/papi/nova/grid/PcGridAdapter.kt").readText()
        val pairing = adapter.substringAfter("statusDot?.setBackgroundResource(R.drawable.nova_status_online)")
            .substringBefore("} else if (obj.details.runningGameId != 0) {")
        assertEquals(
            "a host that answers but wants pairing is not ready, and a green lamp said it was: both pairing states are amber",
            2,
            pairing.split("statusDot?.setBackgroundResource(R.drawable.nova_status_connecting)").size - 1,
        )
        assertTrue(
            "an online host carries the accent in its card and its well; any other is the plain card",
            adapter.contains("val leading = if (online) ColorUtils.blendARGB(cardColor, accent, NOVA_HOST_CARD_ACCENT_WASH) else cardColor") &&
                adapter.contains("pcHolder.well?.background = GradientDrawable().apply {")
        )
    }

    @Test
    fun aBadgeSaysOnlyWhatIsKnownAndNotAlreadySaid() {
        assertEquals(
            NovaHostBadges(polaris = true, spaces = true),
            novaHostBadges(online = true, paired = true, library = LibraryState.AVAILABLE, spacesAvailable = true),
        )
        assertEquals(
            NovaHostBadges(polaris = true, spaces = false),
            novaHostBadges(online = true, paired = true, library = LibraryState.AVAILABLE, spacesAvailable = false),
        )
        val none = NovaHostBadges(polaris = false, spaces = false)
        assertEquals(
            "a host in compatibility mode says so in its status line, so it wears nothing",
            none,
            novaHostBadges(online = true, paired = true, library = LibraryState.UNAVAILABLE, spacesAvailable = false),
        )
        assertEquals(
            "not asked yet is not known",
            none,
            novaHostBadges(online = true, paired = true, library = LibraryState.UNKNOWN, spacesAvailable = true),
        )
        assertEquals(
            "what a host was the last time it answered is not what it is now",
            none,
            novaHostBadges(online = false, paired = true, library = LibraryState.AVAILABLE, spacesAvailable = true),
        )
        assertEquals(
            none,
            novaHostBadges(online = true, paired = false, library = LibraryState.AVAILABLE, spacesAvailable = true),
        )

        val card = views("src/main/res/layout/pc_grid_item.xml")
        listOf("host_badges", "host_badge_kind", "host_badge_spaces").forEach { id ->
            assertEquals(
                "cards are recycled, so $id is gone until a bind says otherwise",
                "gone",
                card.getValue(id).getAttribute("android:visibility"),
            )
        }
        val adapter = File("src/main/java/com/papi/nova/grid/PcGridAdapter.kt").readText()
        assertTrue(
            "the planet is the Spaces badge's now, and the hint under it is left to be a sentence",
            adapter.contains("val planet = ContextCompat.getDrawable(context, R.drawable.ic_spaces_planet)?.mutate()") &&
                !adapter.contains("statusHint?.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_spaces_planet")
        )
    }

    /**
     * papi, 2026-09-21: "the pills are different sizes - Polaris and Spaces pills within the host
     * card". Spaces carried its planet at the icon's own 24dp, which set that badge's height, so
     * it stood 51px beside Polaris at 36px on a Retroid Pocket 6.
     */
    @Test
    fun theBadgesAreOneHeightWhateverTheyCarry() {
        val style = File("src/main/res/values/styles.xml").readText()
            .substringAfter("<style name=\"NovaHostBadge\">").substringBefore("</style>")
        assertTrue(
            "both badges take the one style, and the style sets the height they share",
            style.contains("<item name=\"android:minHeight\">@dimen/nova_host_badge_min_height</item>") &&
                style.contains("<item name=\"android:includeFontPadding\">false</item>")
        )
        val card = views("src/main/res/layout/pc_grid_item.xml")
        listOf("host_badge_kind", "host_badge_spaces").forEach { id ->
            val badge = card.getValue(id)
            assertEquals("@style/NovaHostBadge", badge.getAttribute("style"))
            listOf("android:layout_height", "android:minHeight", "android:textSize", "android:paddingTop", "android:paddingBottom")
                .forEach { attribute ->
                    assertEquals("$id sets no $attribute of its own, or the two part ways again", "", badge.getAttribute(attribute))
                }
        }
        val adapter = File("src/main/java/com/papi/nova/grid/PcGridAdapter.kt").readText()
        assertTrue(
            "the planet is sized from the badge's type, so it cannot be what sets the badge's height",
            adapter.contains("val planetSize = (badge.textSize * NOVA_HOST_BADGE_ICON_EM).toInt()") &&
                adapter.contains("planet?.setBounds(0, 0, planetSize, planetSize)") &&
                adapter.contains("badge.setCompoundDrawablesRelative(planet, null, null, null)") &&
                !adapter.contains("badge.setCompoundDrawablesRelativeWithIntrinsicBounds(")
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
