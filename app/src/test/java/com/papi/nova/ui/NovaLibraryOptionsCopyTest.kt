package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Library Options read "1 games" and developer captions such as "materially denser". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaLibraryOptionsCopyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun oneGameIsOneGame() {
        assertEquals("Category · 1 game", context.resources.getQuantityString(R.plurals.nova_library_panel_category_caption, 1, 1))
        assertEquals("Genre · 3 games", context.resources.getQuantityString(R.plurals.nova_library_panel_genre_caption, 3, 3))
    }

    @Test
    fun theCaptionsArePlainWords() {
        for (id in listOf(
            R.string.nova_library_options_layout_stage_hint,
            R.string.nova_library_options_layout_grid_hint,
            R.string.nova_library_options_layout_compact_hint,
        )) {
            val text = context.getString(id)
            assertFalse(text, Regex("materially|former|hero environment|compact-grid").containsMatchIn(text))
        }
    }
}
