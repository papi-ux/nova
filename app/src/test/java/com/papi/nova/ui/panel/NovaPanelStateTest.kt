package com.papi.nova.ui.panel

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaPanelStateTest {
    private val root = TestPage("root")
    private val second = TestPage("second")
    private val third = TestPage("third")

    @Test
    fun openShowsTheRootAtTheGivenEdge() {
        val state = NovaPanelState()
        assertFalse(state.isOpen)
        assertNull(state.top)

        state.open(root, NovaEdge.Start)

        assertTrue(state.isOpen)
        assertSame(root, state.top)
        assertEquals(1, state.depth)
        assertEquals(NovaEdge.Start, state.edge)
    }

    @Test
    fun pushAndPopMoveOnePageAtATimeAndPopStopsAtTheRoot() {
        val state = NovaPanelState()
        state.open(root)
        state.push(second)
        state.push(third)
        assertEquals(3, state.depth)
        assertSame(third, state.top)

        assertTrue(state.pop())
        assertSame(second, state.top)
        assertTrue(state.pop())
        assertSame(root, state.top)
        assertFalse("pop at the root returns false; the host closes instead", state.pop())
        assertEquals(1, state.depth)
    }

    @Test
    fun replaceTopSwapsOnlyTheTopPage() {
        val state = NovaPanelState()
        state.open(root)
        state.push(second)
        val before = state.topEntry!!.id

        state.replaceTop(third)

        assertEquals(2, state.depth)
        assertSame(third, state.top)
        assertTrue("the replacement is a fresh entry", state.topEntry!!.id > before)
        assertTrue(state.pop())
        assertSame(root, state.top)
    }

    @Test
    fun switchRootReplacesTheStackAndKeepsWhereFocusReturns() {
        val state = NovaPanelState()
        val back = NovaFocusReturn.Compose(FocusRequester())
        state.open(root, NovaEdge.Start, back)
        state.push(second)

        state.switchRoot(third, NovaEdge.End)

        assertEquals(1, state.depth)
        assertSame(third, state.top)
        assertEquals(NovaEdge.End, state.edge)
        assertSame(back, state.returnFocus)
    }

    @Test
    fun closeEmptiesThePanel() {
        val state = NovaPanelState()
        state.open(root)
        state.push(second)

        state.close()

        assertFalse(state.isOpen)
        assertNull(state.top)
        assertEquals(0, state.depth)
    }

    @Test
    fun closingHandsOutWhereFocusReturnsOnlyOnce() {
        val state = NovaPanelState()
        val card = NovaFocusReturn.Compose(FocusRequester())
        state.open(root, returnFocus = card)
        assertSame("nothing to hand out while the panel is open", NovaFocusReturn.None, state.takeReturnFocus())

        state.close()

        assertSame(NovaFocusReturn.None, state.returnFocus)
        assertSame(card, state.takeReturnFocus())
        assertSame(
            "a later window that only held state pages sends focus nowhere",
            NovaFocusReturn.None,
            state.takeReturnFocus(),
        )
        state.close()
        assertSame("closing a closed panel keeps nothing to return to", NovaFocusReturn.None, state.takeReturnFocus())
    }

    @Test
    fun pushOnAClosedPanelOpensItAtTheEnd() {
        val state = NovaPanelState()
        state.push(second)
        assertTrue(state.isOpen)
        assertEquals(NovaEdge.End, state.edge)
        assertSame(second, state.top)
    }

    @Test
    fun entryIdsAreMonotonic() {
        val state = NovaPanelState()
        val ids = mutableListOf<Long>()
        state.open(root)
        ids += state.topEntry!!.id
        state.push(second)
        ids += state.topEntry!!.id
        state.replaceTop(third)
        ids += state.topEntry!!.id
        state.pop()
        state.push(second)
        ids += state.topEntry!!.id
        state.switchRoot(root, NovaEdge.Start)
        ids += state.topEntry!!.id
        state.open(root)
        ids += state.topEntry!!.id

        assertEquals(ids.sorted().distinct(), ids)
    }

    @Test
    fun savedFocusKeyAndIndexSurviveAPush() {
        val state = NovaPanelState()
        state.open(root)
        val entry = state.topEntry!!
        entry.rememberFocus("row-30", 30)

        state.push(second)
        state.topEntry!!.rememberFocus("other", 1)
        state.pop()

        assertSame(entry, state.topEntry)
        assertEquals("row-30", state.topEntry!!.focusKey)
        assertEquals(30, state.topEntry!!.focusIndex)
    }

    @Test
    fun removeWhereTakesPagesOutOfTheMiddleOfTheStack() {
        val state = NovaPanelState()
        state.open(root)
        state.push(second)
        state.push(third)

        state.removeWhere { it.key == "second" }

        assertEquals(2, state.depth)
        assertSame(third, state.top)
        assertTrue(state.contains("root"))
        assertFalse(state.contains("second"))
    }
}

/** A plain page for the panel tests. */
internal data class TestPage(override val key: String, override val title: String = key) : NovaPage
