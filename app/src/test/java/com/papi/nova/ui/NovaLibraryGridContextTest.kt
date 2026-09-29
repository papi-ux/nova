package com.papi.nova.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Compact from Grid left the focused card under the header; it comes back with a row above it. */
class NovaLibraryGridContextTest {
    @Test
    fun theFocusedCardKeepsOneRowOfContextAboveIt() {
        assertEquals(0, novaLibraryGridContextIndex(focusedIndex = 3, columns = 6))
        assertEquals(0, novaLibraryGridContextIndex(focusedIndex = 8, columns = 6))
        assertEquals(6, novaLibraryGridContextIndex(focusedIndex = 14, columns = 6))
        assertEquals("nothing focused keeps the top", 0, novaLibraryGridContextIndex(focusedIndex = -1, columns = 6))
    }
}
