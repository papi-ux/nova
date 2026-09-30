package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review finding 8: the Quick Keys grid was a constructor default, so a copy with other keys or
 * other pinned keys kept the grid of the state it was copied from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaQuickMenuGridKeysTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aCopyDrawsTheGridOfItsOwnKeys() {
        val state = NovaQuickMenuUiState.preview(context)
        val keys = state.quickKeys
        assertEquals(keys.size - state.pinnedQuickKeys.size, state.gridQuickKeys.size)

        val nothingPinned = state.copy(pinnedQuickKeys = emptyList())
        assertEquals("with nothing pinned every key is in the grid", keys.map { it.id }, nothingPinned.gridQuickKeys.map { it.id })

        val two = state.copy(quickKeys = keys.take(2), pinnedQuickKeys = emptyList())
        assertEquals("fewer keys, a smaller grid", keys.take(2).map { it.id }, two.gridQuickKeys.map { it.id })

        val relabelled = keys.map { it.copy(label = it.label + " (host)") }
        val fresh = state.copy(quickKeys = relabelled, pinnedQuickKeys = NovaQuickMenuUiState.pinnedQuickKeys(relabelled))
        assertTrue("the grid shows the keys it was given", fresh.gridQuickKeys.all { it.label.endsWith(" (host)") })
        assertEquals(state.gridQuickKeys.map { it.id }, fresh.gridQuickKeys.map { it.id })
    }
}
