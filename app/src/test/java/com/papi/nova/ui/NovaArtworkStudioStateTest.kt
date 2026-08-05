package com.papi.nova.ui

import androidx.lifecycle.Lifecycle
import com.papi.nova.api.PolarisArtworkChoice
import com.papi.nova.api.PolarisArtworkMatchCandidate
import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaArtworkStudioStateTest {
    private val portal = candidate("123", "Portal")
    private val portalTwo = candidate("456", "Portal 2")

    @Test
    fun selectingIdentityStartsASeparateLazyPerKindDraft() {
        val oldHero = choice(NovaArtworkKinds.HERO, "00000000000000000000000000000001")
        val state = NovaArtworkStudioState(
            candidates = listOf(portal, portalTwo),
            selectedCandidate = portal,
            activeKind = NovaArtworkKinds.HERO,
            choicesByKind = mapOf(NovaArtworkKinds.HERO to listOf(oldHero)),
            loadedKinds = setOf(NovaArtworkKinds.HERO),
            selections = mapOf(NovaArtworkKinds.HERO to oldHero),
        )

        val selected = state.reduce(NovaArtworkStudioAction.IdentitySelected(portalTwo))

        assertEquals(portalTwo, selected.selectedCandidate)
        assertEquals(NovaArtworkKinds.POSTER, selected.activeKind)
        assertTrue(selected.choicesByKind.isEmpty())
        assertTrue(selected.loadedKinds.isEmpty())
        assertTrue(selected.selections.isEmpty())
        assertTrue(selected.needsChoiceLoad(NovaArtworkKinds.POSTER))
        assertFalse(selected.needsChoiceLoad(NovaArtworkKinds.HERO))
    }

    @Test
    fun choicesAreCandidateBoundAndOnlyOneSelectionIsKeptPerKind() {
        val first = choice(NovaArtworkKinds.POSTER, "00000000000000000000000000000001")
        val second = choice(NovaArtworkKinds.POSTER, "00000000000000000000000000000002")
        val selected = NovaArtworkStudioState(selectedCandidate = portal)
            .reduce(NovaArtworkStudioAction.ChoicesLoading(portal, NovaArtworkKinds.POSTER))
            .reduce(NovaArtworkStudioAction.ChoicesLoaded(portal, NovaArtworkKinds.POSTER, listOf(first, second)))
            .reduce(NovaArtworkStudioAction.ChoiceSelected(first))
            .reduce(NovaArtworkStudioAction.ChoiceSelected(second))

        assertEquals(listOf(first, second), selected.choicesByKind[NovaArtworkKinds.POSTER])
        assertEquals(mapOf(NovaArtworkKinds.POSTER to second), selected.selections)
        assertTrue(selected.canApply)

        val stale = selected.reduce(
            NovaArtworkStudioAction.ChoicesLoaded(
                portalTwo,
                NovaArtworkKinds.HERO,
                listOf(choice(NovaArtworkKinds.HERO, "00000000000000000000000000000003")),
            ),
        )
        assertEquals(selected, stale)
    }

    @Test
    fun resetAndCancelRejectResponsesFromAnEarlierChoiceGeneration() {
        val poster = choice(NovaArtworkKinds.POSTER, "00000000000000000000000000000001")
        val loading = NovaArtworkStudioState(currentCandidate = portal, selectedCandidate = portal)
            .reduce(NovaArtworkStudioAction.ChoicesLoading(portal, NovaArtworkKinds.POSTER, 0))
        val stale = NovaArtworkStudioAction.ChoicesLoaded(
            portal, NovaArtworkKinds.POSTER, listOf(poster), generation = 0,
        )
        val reset = loading.reduce(NovaArtworkStudioAction.EditingReset)
        val cancelled = loading.reduce(NovaArtworkStudioAction.EditingCancelled)
        assertEquals(reset, reset.reduce(stale))
        assertEquals(cancelled, cancelled.reduce(stale))
    }

    @Test
    fun applyRequiresACoherentNonEmptyDraftAndNoWorkInFlight() {
        val poster = choice(NovaArtworkKinds.POSTER, "00000000000000000000000000000001")
        val ready = NovaArtworkStudioState(
            selectedCandidate = portal,
            choicesByKind = mapOf(NovaArtworkKinds.POSTER to listOf(poster)),
            loadedKinds = setOf(NovaArtworkKinds.POSTER),
            selections = mapOf(NovaArtworkKinds.POSTER to poster),
        )

        assertTrue(ready.canApply)
        assertFalse(ready.copy(selections = emptyMap()).canApply)
        assertFalse(ready.copy(working = true).canApply)
        assertFalse(ready.copy(loadingKinds = setOf(NovaArtworkKinds.HERO)).canApply)
        assertFalse(
            ready.copy(
                selections = mapOf(
                    NovaArtworkKinds.POSTER to choice(
                        NovaArtworkKinds.HERO,
                        "00000000000000000000000000000004",
                    ),
                ),
            ).canApply,
        )
    }

    @Test
    fun cancellingTheStudioDiscardsOnlyTheDraftWithoutMutatingCurrentComposition() {
        val poster = choice(NovaArtworkKinds.POSTER, "00000000000000000000000000000001")
        val state = NovaArtworkStudioState(
            selectedCandidate = portal,
            choicesByKind = mapOf(NovaArtworkKinds.POSTER to listOf(poster)),
            loadedKinds = setOf(NovaArtworkKinds.POSTER),
            selections = mapOf(NovaArtworkKinds.POSTER to poster),
            overrideActive = true,
            currentMatchTitle = "Current Portal",
            currentMatchSource = "steamgriddb",
            currentMatchManual = true,
            currentKinds = setOf(NovaArtworkKinds.POSTER, NovaArtworkKinds.LOGO),
            logoScale = 1.25f,
            logoX = 0.3f,
            logoY = 0.7f,
        )

        val cancelled = state.reduce(NovaArtworkStudioAction.EditingCancelled)

        assertEquals(null, cancelled.selectedCandidate)
        assertTrue(cancelled.choicesByKind.isEmpty())
        assertTrue(cancelled.selections.isEmpty())
        assertTrue(cancelled.overrideActive)
        assertEquals("Current Portal", cancelled.currentMatchTitle)
        assertEquals(setOf(NovaArtworkKinds.POSTER, NovaArtworkKinds.LOGO), cancelled.currentKinds)
        assertEquals(1.25f, cancelled.logoScale)
        assertEquals(0.3f, cancelled.logoX)
        assertEquals(0.7f, cancelled.logoY)
    }

    @Test
    fun manifestSeedsCurrentMatchAndOnlyCachedOptionalCompositionKinds() {
        val game = PolarisGame(
            id = "game-1",
            name = "Portal",
            artwork = PolarisGame.ArtworkManifest(
                match = PolarisGame.ArtworkMatch(
                    source = "steamgriddb",
                    providerGameId = "123",
                    title = "Portal",
                    confidence = 0.97,
                    manual = true,
                ),
                assets = PolarisGame.ArtworkAssets(
                    poster = asset("/poster", cached = true),
                    hero = asset("/hero", cached = false),
                    icon = asset("/icon", cached = true),
                ),
                override = PolarisGame.ArtworkOverride(
                    active = true,
                    kinds = listOf(NovaArtworkKinds.POSTER, NovaArtworkKinds.ICON),
                    logoTransform = PolarisGame.ArtworkLogoTransform(x = 0.2, y = 0.8, scale = 1.4),
                ),
            ),
        )

        val state = NovaArtworkStudioState.from(game)

        assertEquals("Portal", state.currentMatchTitle)
        assertEquals("steamgriddb", state.currentMatchSource)
        assertTrue(state.currentMatchManual)
        assertEquals(setOf(NovaArtworkKinds.POSTER, NovaArtworkKinds.ICON), state.currentKinds)
        assertEquals("123", state.currentCandidate?.providerGameId)
        assertEquals("Portal", state.currentCandidate?.title)
        assertEquals(state.currentCandidate, state.selectedCandidate)
        assertTrue(state.needsChoiceLoad(NovaArtworkKinds.POSTER))
        assertEquals(1.4f, state.logoScale)
        assertEquals(0.2f, state.logoX)
        assertEquals(0.8f, state.logoY)
    }

    @Test
    fun mixedOrUnlistedChoicesCannotEnterTheSelectedSet() {
        val poster = choice(NovaArtworkKinds.POSTER, "00000000000000000000000000000001")
        val hero = choice(NovaArtworkKinds.HERO, "00000000000000000000000000000002")
        val unlistedPoster = choice(NovaArtworkKinds.POSTER, "00000000000000000000000000000003")
        val loaded = NovaArtworkStudioState(selectedCandidate = portal)
            .reduce(NovaArtworkStudioAction.ChoicesLoading(portal, NovaArtworkKinds.POSTER))
            .reduce(NovaArtworkStudioAction.ChoicesLoaded(portal, NovaArtworkKinds.POSTER, listOf(poster, hero)))

        assertEquals(listOf(poster), loaded.choicesByKind[NovaArtworkKinds.POSTER])
        assertEquals(loaded, loaded.reduce(NovaArtworkStudioAction.ChoiceSelected(hero)))
        assertEquals(loaded, loaded.reduce(NovaArtworkStudioAction.ChoiceSelected(unlistedPoster)))
        assertEquals(
            mapOf(NovaArtworkKinds.POSTER to poster),
            loaded.reduce(NovaArtworkStudioAction.ChoiceSelected(poster)).selections,
        )
    }

    @Test
    fun resetDiscardsTheEntireDraftAndRestoresCurrentIdentityWithoutChangingPublishedState() {
        val poster = choice(NovaArtworkKinds.POSTER, "00000000000000000000000000000001")
        val state = NovaArtworkStudioState(
            candidates = listOf(portal, portalTwo),
            currentCandidate = portal,
            selectedCandidate = portalTwo,
            activeKind = NovaArtworkKinds.HERO,
            choicesByKind = mapOf(NovaArtworkKinds.POSTER to listOf(poster)),
            loadedKinds = setOf(NovaArtworkKinds.POSTER),
            loadingKinds = setOf(NovaArtworkKinds.HERO),
            selections = mapOf(NovaArtworkKinds.POSTER to poster),
            working = true,
            error = "temporary",
            overrideActive = true,
            currentMatchTitle = "Portal",
            currentKinds = setOf(NovaArtworkKinds.POSTER, NovaArtworkKinds.LOGO),
            logoScale = 1.25f,
            logoX = 0.3f,
            logoY = 0.7f,
        )

        val reset = state.reduce(NovaArtworkStudioAction.EditingReset)

        assertEquals(portal, reset.selectedCandidate)
        assertEquals(listOf(portal, portalTwo), reset.candidates)
        assertEquals(NovaArtworkKinds.POSTER, reset.activeKind)
        assertTrue(reset.choicesByKind.isEmpty())
        assertTrue(reset.loadedKinds.isEmpty())
        assertTrue(reset.loadingKinds.isEmpty())
        assertTrue(reset.selections.isEmpty())
        assertFalse(reset.working)
        assertEquals(null, reset.error)
        assertTrue(reset.overrideActive)
        assertEquals("Portal", reset.currentMatchTitle)
        assertEquals(setOf(NovaArtworkKinds.POSTER, NovaArtworkKinds.LOGO), reset.currentKinds)
        assertEquals(1.25f, reset.logoScale)
        assertEquals(0.3f, reset.logoX)
        assertEquals(0.7f, reset.logoY)
    }

    @Test
    fun atomicApplyFailureDiscardsConsumedCapabilitiesButPreservesIdentityAndPublishedComposition() {
        val poster = choice(NovaArtworkKinds.POSTER, "00000000000000000000000000000001")
        val hero = choice(NovaArtworkKinds.HERO, "00000000000000000000000000000002")
        val applying = NovaArtworkStudioState(
            currentCandidate = portal,
            selectedCandidate = portalTwo,
            choicesByKind = mapOf(
                NovaArtworkKinds.POSTER to listOf(poster),
                NovaArtworkKinds.HERO to listOf(hero),
            ),
            loadedKinds = setOf(NovaArtworkKinds.POSTER, NovaArtworkKinds.HERO),
            selections = mapOf(
                NovaArtworkKinds.POSTER to poster,
                NovaArtworkKinds.HERO to hero,
            ),
            working = true,
            currentMatchTitle = "Portal",
            currentKinds = setOf(NovaArtworkKinds.POSTER, NovaArtworkKinds.LOGO),
            logoScale = 1.2f,
            logoX = 0.4f,
            logoY = 0.6f,
        )

        val failed = applying.reduce(NovaArtworkStudioAction.ApplyFailed("Artwork set was not applied"))

        assertFalse(failed.working)
        assertEquals("Artwork set was not applied", failed.error)
        assertEquals(applying.selectedCandidate, failed.selectedCandidate)
        assertTrue(failed.choicesByKind.isEmpty())
        assertTrue(failed.loadedKinds.isEmpty())
        assertTrue(failed.loadingKinds.isEmpty())
        assertTrue(failed.selections.isEmpty())
        assertFalse(failed.canApply)
        assertTrue(failed.needsChoiceLoad(NovaArtworkKinds.POSTER))
        assertEquals(applying.currentMatchTitle, failed.currentMatchTitle)
        assertEquals(applying.currentKinds, failed.currentKinds)
        assertEquals(applying.logoScale, failed.logoScale)
        assertEquals(applying.logoX, failed.logoX)
        assertEquals(applying.logoY, failed.logoY)
    }

    @Test
    fun artworkMutationCompletionCanPublishWhileStoppedButNotAfterViewDestruction() {
        assertFalse(canPublishArtworkMutationUiForState(null))
        assertFalse(canPublishArtworkMutationUiForState(Lifecycle.State.DESTROYED))
        assertFalse(canPublishArtworkMutationUiForState(Lifecycle.State.INITIALIZED))
        assertTrue(canPublishArtworkMutationUiForState(Lifecycle.State.CREATED))
        assertTrue(canPublishArtworkMutationUiForState(Lifecycle.State.STARTED))
        assertTrue(canPublishArtworkMutationUiForState(Lifecycle.State.RESUMED))
    }

    private fun asset(url: String, cached: Boolean) = PolarisGame.ArtworkAsset(
        url = url,
        cached = cached,
    )

    private fun candidate(id: String, title: String) = PolarisArtworkMatchCandidate(
        provider = "steamgriddb",
        providerGameId = id,
        title = title,
    )

    private fun choice(kind: String, token: String) = PolarisArtworkChoice(
        kind = kind,
        selectionToken = token,
        previewUrl = "https://polaris.invalid/candidate/$token/$kind",
        expiresAt = 1L,
    )
}
