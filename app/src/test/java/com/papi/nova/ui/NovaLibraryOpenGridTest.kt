package com.papi.nova.ui

import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library's poster grid with no panel around it (papi, 2026-09-16: "i dont think we need a
 * box to contain the posters, that way we also have more room on the screen"). The panel's
 * 10dp inner padding was also the only thing between a focused first-row poster and the top
 * bar, and a focused poster rises further than that, so it was clipped against the panel's
 * top edge. The grid's inset is now derived from the focus spec instead.
 */
class NovaLibraryOpenGridTest {
    private val rp6GridBoxWidthDp = 815
    private val rp6GridBoxHeightDp = 304

    @Test
    fun focusRiseIsTheLiftPlusHalfWhatTheScaleAdds() {
        assertEquals(10, NovaLibraryUiStateMapper.posterFocusLiftDp())
        // 1.06 on a 123dp poster adds 7.38dp, half above the centre: 10 + 3.69, rounded up.
        assertEquals(14, NovaLibraryUiStateMapper.posterFocusRiseDp(NovaLibraryLayoutMode.COMPACT, 123))
        // 1.08 on 210dp: 10 + 8.4.
        assertEquals(19, NovaLibraryUiStateMapper.posterFocusRiseDp(NovaLibraryLayoutMode.GRID, 210))
        // 1.10 on 100dp is exactly 15; float residue must not make it 16.
        assertEquals(15, NovaLibraryUiStateMapper.posterFocusRiseDp(NovaLibraryLayoutMode.STAGE, 100))
        assertEquals(10, NovaLibraryUiStateMapper.posterFocusRiseDp(NovaLibraryLayoutMode.GRID, 0))
    }

    @Test
    fun aFocusedFirstRowPosterNeverReachesAboveTheGrid() {
        NovaLibraryLayoutMode.entries.forEach { mode ->
            val scale = NovaLibraryUiStateMapper.posterPresentationSpec(mode).focusedScale
            for (posterHeightDp in 60..480 step 3) {
                val inset = NovaLibraryUiStateMapper.posterFocusRiseDp(mode, posterHeightDp)
                val focusedTopDp = inset - NovaLibraryUiStateMapper.posterFocusLiftDp() -
                    (scale - 1f) * posterHeightDp / 2f
                assertTrue(
                    "$mode at ${posterHeightDp}dp: a focused poster would be clipped by ${-focusedTopDp}dp",
                    focusedTopDp >= -0.001f,
                )
            }
        }
    }

    @Test
    fun theGridSpecCountsRowsBelowTheFocusRiseOfItsOwnPoster() {
        listOf(NovaLibraryLayoutMode.COMPACT, NovaLibraryLayoutMode.GRID).forEach { mode ->
            val spec = rp6Spec(mode)
            assertEquals(
                NovaLibraryUiStateMapper.posterFocusRiseDp(mode, spec.posterHeightDp),
                spec.topInsetDp,
            )
            assertEquals(
                "$mode: inset, whole rows and peek should add up to the grid's height",
                rp6GridBoxHeightDp,
                spec.topInsetDp + spec.fullRows * spec.rowPitchDp - 6 + spec.peekDp,
            )
        }
    }

    @Test
    fun rp6KeepsItsRowsAndGridPostersGrowWithThePanelGone() {
        // Before: the panel's 10dp padding on every side left 795 by 294 for the rows,
        // giving COMPACT 8 columns of 82x123 in two rows and GRID 5 columns of 138x207.
        val compact = rp6Spec(NovaLibraryLayoutMode.COMPACT)
        assertEquals(8, compact.columns)
        assertEquals(82, compact.posterWidthDp)
        assertEquals(123, compact.posterHeightDp)
        assertEquals(2, compact.fullRows)
        assertTrue("compact still shows it scrolls, peek ${compact.peekDp}", compact.peekDp >= 16)

        val grid = rp6Spec(NovaLibraryLayoutMode.GRID)
        assertEquals(5, grid.columns)
        assertEquals(140, grid.posterWidthDp)
        assertEquals(210, grid.posterHeightDp)
        assertTrue("grid still peeks at the next row, peek ${grid.peekDp}", grid.peekDp >= 48)
    }

    @Test
    fun posterArtworkLinesUpWithTheBarsContent() {
        NovaLibraryLayoutMode.entries.forEach { mode ->
            val gutter = NovaLibraryUiStateMapper.posterPresentationSpec(mode).focusGutterDp
            assertEquals(
                "$mode artwork edge",
                NovaLibraryUiStateMapper.libraryBarContentInsetDp(),
                NovaLibraryUiStateMapper.gridSidePaddingDp(mode) + gutter,
            )
        }
    }

    @Test
    fun focusScrollKeepsTheRiseClearAtTheTopAndOnlyInsideAtTheBottom() {
        val margin = 14f
        // A poster scrolled 5px from the top edge moves down to the margin.
        assertEquals(-9f, NovaLibraryUiStateMapper.gridFocusScrollDistance(5f, 123f, 304f, margin), 0.001f)
        // Clear of the margin and inside: nothing to do.
        assertEquals(0f, NovaLibraryUiStateMapper.gridFocusScrollDistance(20f, 123f, 304f, margin), 0.001f)
        // Past the bottom: scroll just far enough to bring it inside.
        assertEquals(19f, NovaLibraryUiStateMapper.gridFocusScrollDistance(200f, 123f, 304f, margin), 0.001f)
        // With only 4px to spare, the margin shrinks to what room there is.
        assertEquals(-9f, NovaLibraryUiStateMapper.gridFocusScrollDistance(-5f, 300f, 304f, margin), 0.001f)
    }

    @Test
    fun aPosterTallerThanTheGridSettlesInsteadOfFlippingBetweenEdges() {
        val margin = 14f
        val container = 100f
        val size = 150f
        // Spanning both edges: nothing to do, as Compose's default rule says.
        assertEquals(0f, NovaLibraryUiStateMapper.gridFocusScrollDistance(-10f, size, container, margin), 0.001f)
        // Top edge inside: align it, then stay.
        assertEquals(20f, NovaLibraryUiStateMapper.gridFocusScrollDistance(20f, size, container, margin), 0.001f)
        assertEquals(0f, NovaLibraryUiStateMapper.gridFocusScrollDistance(0f, size, container, margin), 0.001f)
        // Bottom edge inside and nearer: align the bottom edge, then stay.
        assertEquals(-10f, NovaLibraryUiStateMapper.gridFocusScrollDistance(-60f, size, container, margin), 0.001f)
        assertEquals(0f, NovaLibraryUiStateMapper.gridFocusScrollDistance(-50f, size, container, margin), 0.001f)
        // From any start, repeated focus passes reach no scroll and keep it. The old rule asked
        // for the top margin and the bottom edge in turn, so the distance never reached zero.
        listOf(-120f, -60f, -10f, 0f, 30f, 200f).forEach { start ->
            var offset = start
            repeat(3) {
                offset -= NovaLibraryUiStateMapper.gridFocusScrollDistance(offset, size, container, margin)
            }
            assertEquals(
                "a poster taller than the grid, starting at $start, settles",
                0f,
                NovaLibraryUiStateMapper.gridFocusScrollDistance(offset, size, container, margin),
                0.001f,
            )
        }
    }

    @Test
    fun theBackdropDrawsOnlyARealHero() {
        val cachedHero = PolarisGame.ArtworkAsset(url = "/hero", cached = true)
        val listedHero = PolarisGame.ArtworkAsset(url = "/hero", cached = false)
        val poster = PolarisGame.ArtworkAsset(url = "/poster", cached = true)
        fun game(
            hero: PolarisGame.ArtworkAsset?,
            space: PolarisGame.SpaceContext? = null,
        ) = PolarisGame(
            id = "g",
            space = space,
            artwork = PolarisGame.ArtworkManifest(
                revision = "r",
                assets = PolarisGame.ArtworkAssets(poster = poster, hero = hero),
            ),
        )
        val space = PolarisGame.SpaceContext("p", "papi - steam", "813230")
        val bigPicture = PolarisGame.SpaceContext("p", "papi - steam", "big-picture-v1")

        assertEquals(PolarisGame.ARTWORK_KIND_HERO, NovaLibraryUiStateMapper.cinematicBackdropArtworkKind(game(cachedHero)))
        assertNull("a desktop hero not cached yet is not drawn", NovaLibraryUiStateMapper.cinematicBackdropArtworkKind(game(listedHero)))
        assertNull("a poster alone never fills the screen", NovaLibraryUiStateMapper.cinematicBackdropArtworkKind(game(null)))
        assertEquals(PolarisGame.ARTWORK_KIND_HERO, NovaLibraryUiStateMapper.cinematicBackdropArtworkKind(game(listedHero, space)))
        assertEquals(
            "the host fetches a Space title's hero the first time it is asked and lists only what it has fetched, so a " +
                "hero that waits to be listed is never asked for: Portal 2's page was a name on an empty screen",
            PolarisGame.ARTWORK_KIND_HERO,
            NovaLibraryUiStateMapper.cinematicBackdropArtworkKind(game(null, space)),
        )
        assertNull(
            "a Space title the host lists with no artwork at all, a Heroic or Lutris one today, has none to ask for",
            NovaLibraryUiStateMapper.cinematicBackdropArtworkKind(PolarisGame(id = "g", space = space)),
        )
        assertNull("Big Picture's bundled mark is no hero", NovaLibraryUiStateMapper.cinematicBackdropArtworkKind(game(cachedHero, bigPicture)))
        assertNull(NovaLibraryUiStateMapper.cinematicBackdropArtworkKind(null))
    }

    private fun rp6Spec(mode: NovaLibraryLayoutMode) = NovaLibraryUiStateMapper.gridViewportSpec(
        contentWidthDp = rp6GridBoxWidthDp - NovaLibraryUiStateMapper.gridSidePaddingDp(mode) * 2,
        viewportHeightDp = rp6GridBoxHeightDp,
        layoutMode = mode,
        windowClass = NovaLibraryWindowClass.HANDHELD_LANDSCAPE,
    )
}
