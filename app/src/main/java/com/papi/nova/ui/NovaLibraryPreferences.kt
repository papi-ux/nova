package com.papi.nova.ui

import android.content.SharedPreferences

object NovaLibraryPreferences {
    private const val SORT_MODE_PREF = "nova_library_sort_mode"
    private const val LAYOUT_MODE_PREF = "nova_library_layout_mode"
    private const val LEGACY_SPOTLIGHT_LAYOUT_MODE = "SPOTLIGHT"
    private const val POSTER_TITLES_PREF = "nova_library_poster_titles"
    private const val FILTER_PRIMARY_PREF = "nova_library_filter_primary"
    private const val FILTER_SOURCE_PREF = "nova_library_filter_source"
    private const val FILTER_CATEGORY_PREF = "nova_library_filter_category"
    private const val FILTER_GENRE_PREF = "nova_library_filter_genre"

    fun loadOptions(prefs: SharedPreferences): NovaLibraryOptionsState {
        return NovaLibraryOptionsState(
            sortMode = prefs.enumValue(SORT_MODE_PREF, NovaLibrarySortMode.LIBRARY_ORDER),
            layoutMode = prefs.libraryLayoutMode(),
            showPosterTitles = prefs.getBoolean(POSTER_TITLES_PREF, true)
        )
    }

    fun persistOptions(
        prefs: SharedPreferences,
        optionsState: NovaLibraryOptionsState
    ) {
        prefs.edit()
            .putString(SORT_MODE_PREF, optionsState.sortMode.name)
            .putString(LAYOUT_MODE_PREF, optionsState.layoutMode.name)
            .putBoolean(POSTER_TITLES_PREF, optionsState.showPosterTitles)
            .apply()
    }

    fun loadFilterState(prefs: SharedPreferences): NovaLibraryFilterState {
        return normalizeFilterState(
            NovaLibraryFilterState(
                primary = prefs.enumValue(FILTER_PRIMARY_PREF, NovaLibraryPrimaryFilter.ALL),
                source = prefs.getString(FILTER_SOURCE_PREF, "").orEmpty(),
                category = prefs.getString(FILTER_CATEGORY_PREF, "").orEmpty(),
                genre = prefs.getString(FILTER_GENRE_PREF, "").orEmpty()
            )
        )
    }

    fun persistFilterState(
        prefs: SharedPreferences,
        filterState: NovaLibraryFilterState
    ) {
        val normalized = normalizeFilterState(filterState)
        prefs.edit()
            .putString(FILTER_PRIMARY_PREF, normalized.primary.name)
            .putString(FILTER_SOURCE_PREF, normalized.source)
            .putString(FILTER_CATEGORY_PREF, normalized.category)
            .putString(FILTER_GENRE_PREF, normalized.genre)
            .apply()
    }

    fun normalizeFilterState(filterState: NovaLibraryFilterState): NovaLibraryFilterState {
        return when (filterState.primary) {
            NovaLibraryPrimaryFilter.ALL -> NovaLibraryFilterState()
            NovaLibraryPrimaryFilter.RECENT -> NovaLibraryFilterState(
                primary = NovaLibraryPrimaryFilter.RECENT
            )
            NovaLibraryPrimaryFilter.SOURCES -> filterState.source.trim()
                .takeIf { it.isNotEmpty() }
                ?.let { source ->
                    NovaLibraryFilterState(
                        primary = NovaLibraryPrimaryFilter.SOURCES,
                        source = source
                    )
                }
                ?: NovaLibraryFilterState()
            NovaLibraryPrimaryFilter.HDR -> NovaLibraryFilterState(
                primary = NovaLibraryPrimaryFilter.HDR
            )
            NovaLibraryPrimaryFilter.MORE -> when {
                filterState.category.isNotBlank() -> NovaLibraryFilterState(
                    primary = NovaLibraryPrimaryFilter.MORE,
                    category = filterState.category.trim()
                )
                filterState.genre.isNotBlank() -> NovaLibraryFilterState(
                    primary = NovaLibraryPrimaryFilter.MORE,
                    genre = filterState.genre.trim()
                )
                else -> NovaLibraryFilterState()
            }
        }
    }

    private fun SharedPreferences.libraryLayoutMode(): NovaLibraryLayoutMode {
        return if (getString(LAYOUT_MODE_PREF, null) == LEGACY_SPOTLIGHT_LAYOUT_MODE) {
            NovaLibraryLayoutMode.SPOTLIGHT_ROW
        } else {
            enumValue(LAYOUT_MODE_PREF, NovaLibraryLayoutMode.GRID)
        }
    }

    private inline fun <reified T : Enum<T>> SharedPreferences.enumValue(
        key: String,
        defaultValue: T
    ): T {
        val persisted = getString(key, null) ?: return defaultValue
        return enumValues<T>().firstOrNull { it.name == persisted } ?: defaultValue
    }
}
