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
    fun freshInstallDefaultsToGrid() {
        val prefs = freshPrefs("nova-library-prefs-defaults-grid")

        val options = NovaLibraryPreferences.loadOptions(prefs)
        assertEquals(NovaLibraryLayoutMode.GRID, options.layoutMode)
        assertFalse("fresh installs should use plain poster artwork", options.showPosterTitles)
        assertEquals("GRID", prefs.getString("nova_library_layout_mode", null))
        assertEquals(NovaLibraryFilterState(), NovaLibraryPreferences.loadFilterState(prefs))
    }

    @Test
    fun freshOptionsStateAndLoadedOptionsDefaultToPlainArtwork() {
        assertFalse(NovaLibraryOptionsState().showPosterTitles)

        val prefs = freshPrefs("nova-library-prefs-plain-artwork-default")
        assertFalse(NovaLibraryPreferences.loadOptions(prefs).showPosterTitles)
    }

    @Test
    fun persistedPosterTitleChoicesRoundTripWithoutResettingUsers() {
        val prefs = freshPrefs("nova-library-prefs-poster-title-round-trip")

        NovaLibraryPreferences.persistOptions(
            prefs,
            NovaLibraryOptionsState(showPosterTitles = true),
        )
        assertEquals(true, prefs.getBoolean("nova_library_poster_titles", false))
        assertEquals(true, NovaLibraryPreferences.loadOptions(prefs).showPosterTitles)

        NovaLibraryPreferences.persistOptions(
            prefs,
            NovaLibraryOptionsState(showPosterTitles = false),
        )
        assertEquals(false, prefs.getBoolean("nova_library_poster_titles", true))
        assertEquals(false, NovaLibraryPreferences.loadOptions(prefs).showPosterTitles)
    }

    @Test
    fun migratesEveryRetiredLayoutToTheApprovedProductionMode() {
        val cases = mapOf(
            "COMPACT_GRID" to NovaLibraryLayoutMode.GRID,
            "GRID" to NovaLibraryLayoutMode.GRID,
            "SPOTLIGHT_ROW" to NovaLibraryLayoutMode.STAGE,
            "SPOTLIGHT" to NovaLibraryLayoutMode.STAGE,
            "LIST" to NovaLibraryLayoutMode.GRID,
            "not-a-real-layout" to NovaLibraryLayoutMode.GRID,
        )

        cases.forEach { (persisted, expected) ->
            val prefs = freshPrefs("nova-library-prefs-migration-$persisted")
            prefs.edit().putString("nova_library_layout_mode", persisted).commit()

            assertEquals("migration for $persisted", expected, NovaLibraryPreferences.loadOptions(prefs).layoutMode)
            assertEquals("canonical persistence for $persisted", expected.name, prefs.getString("nova_library_layout_mode", null))
        }
    }

    @Test
    fun persistsOnlyGridCompactAndStageAcrossActivityInstances() {
        NovaLibraryLayoutMode.entries.forEach { layoutMode ->
            val prefs = freshPrefs("nova-library-prefs-production-${layoutMode.name}")
            val options = NovaLibraryOptionsState(
                sortMode = NovaLibrarySortMode.HDR_FIRST,
                layoutMode = layoutMode,
                showPosterTitles = false,
            )
            val filter = NovaLibraryFilterState(
                primary = NovaLibraryPrimaryFilter.SOURCES,
                source = "steam",
            )

            NovaLibraryPreferences.persistOptions(prefs, options)
            NovaLibraryPreferences.persistFilterState(prefs, filter)

            assertEquals(options, NovaLibraryPreferences.loadOptions(prefs))
            assertEquals(filter, NovaLibraryPreferences.loadFilterState(prefs))
        }
        assertEquals(
            listOf(
                NovaLibraryLayoutMode.GRID,
                NovaLibraryLayoutMode.COMPACT,
                NovaLibraryLayoutMode.STAGE,
            ),
            NovaLibraryLayoutMode.entries,
        )
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
