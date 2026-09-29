package com.papi.nova.ui

import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the fast_action category and a launcher's Action genre both stay in More Filters, because
 * each holds games the other lacks, their row titles say which is which: two rows read "Action",
 * told apart only by their captions (N16). A name no other entry shares keeps its plain title.
 */
class NovaLibraryMoreFilterNamesTest {
    private fun categoryLabel(id: String) = if (id == "fast_action") "Action" else id.replaceFirstChar { it.uppercase() }
    private fun genreLabel(name: String) = name.replaceFirstChar { it.uppercase() }

    private fun game(id: String, category: String = "", genres: List<String> = emptyList()) =
        PolarisGame(id = id, name = id, source = "steam", launcherSource = "steam", category = category, genres = genres)

    @Test
    fun aCategoryAndAGenreThatBothStayUnderOneNameAreNamedApart() {
        val games = listOf(
            game("doom", category = "fast_action"),
            game("hades", genres = listOf("action")),
            game("celeste", category = "cinematic", genres = listOf("platformer")),
        )
        val entries = NovaLibraryUiStateMapper.moreFilterEntries(games, ::categoryLabel, ::genreLabel)
        val clashes = NovaLibraryUiStateMapper.moreFilterClashes(entries, ::categoryLabel, ::genreLabel)

        assertTrue("both Action rows stay", NovaLibraryMoreFilter.Category("fast_action") in entries && NovaLibraryMoreFilter.Genre("action") in entries)
        assertEquals(setOf(NovaLibraryMoreFilter.Category("fast_action"), NovaLibraryMoreFilter.Genre("action")), clashes)
    }

    @Test
    fun aNameOnlyOneEntryHasIsNoClash() {
        val games = listOf(
            game("doom", category = "fast_action", genres = listOf("action")),
            game("celeste", category = "cinematic", genres = listOf("platformer")),
        )
        val entries = NovaLibraryUiStateMapper.moreFilterEntries(games, ::categoryLabel, ::genreLabel)
        assertTrue(NovaLibraryUiStateMapper.moreFilterClashes(entries, ::categoryLabel, ::genreLabel).isEmpty())
    }
}
