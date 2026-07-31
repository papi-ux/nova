package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NovaLibraryPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun freshPrefs(name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).also { prefs ->
            prefs.edit().clear().commit()
        }

    @Test
    fun defaultsMatchFreshLibraryDrawerState() {
        val prefs = freshPrefs("nova-library-prefs-defaults")

        assertEquals(NovaLibraryOptionsState(), NovaLibraryPreferences.loadOptions(prefs))
        assertEquals(NovaLibraryFilterState(), NovaLibraryPreferences.loadFilterState(prefs))
    }

    @Test
    fun persistsSortLayoutPosterTitlesAndSourceFilterAcrossActivityInstances() {
        val prefs = freshPrefs("nova-library-prefs-source")
        val options = NovaLibraryOptionsState(
            sortMode = NovaLibrarySortMode.HDR_FIRST,
            layoutMode = NovaLibraryLayoutMode.LIST,
            showPosterTitles = false
        )
        val filter = NovaLibraryFilterState(
            primary = NovaLibraryPrimaryFilter.SOURCES,
            source = "steam"
        )

        NovaLibraryPreferences.persistOptions(prefs, options)
        NovaLibraryPreferences.persistFilterState(prefs, filter)

        assertEquals(options, NovaLibraryPreferences.loadOptions(prefs))
        assertEquals(filter, NovaLibraryPreferences.loadFilterState(prefs))
    }

    @Test
    fun persistsSpotlightAsAnOptionalLibraryLayout() {
        val prefs = freshPrefs("nova-library-prefs-spotlight")
        val options = NovaLibraryOptionsState(
            layoutMode = NovaLibraryLayoutMode.SPOTLIGHT_ROW
        )

        NovaLibraryPreferences.persistOptions(prefs, options)

        assertEquals(options, NovaLibraryPreferences.loadOptions(prefs))
    }

    @Test
    fun persistsMoreFiltersAndClearsThemBackToAll() {
        val prefs = freshPrefs("nova-library-prefs-more")
        val category = NovaLibraryFilterState(
            primary = NovaLibraryPrimaryFilter.MORE,
            category = "fast_action"
        )
        val genre = NovaLibraryFilterState(
            primary = NovaLibraryPrimaryFilter.MORE,
            genre = "RPG"
        )

        NovaLibraryPreferences.persistFilterState(prefs, category)
        assertEquals(category, NovaLibraryPreferences.loadFilterState(prefs))

        NovaLibraryPreferences.persistFilterState(prefs, genre)
        assertEquals(genre, NovaLibraryPreferences.loadFilterState(prefs))

        NovaLibraryPreferences.persistFilterState(prefs, NovaLibraryFilterState())
        val cleared = NovaLibraryPreferences.loadFilterState(prefs)
        assertEquals(NovaLibraryFilterState(), cleared)
        assertFalse(cleared.hasActiveConstraint)
    }
}
