package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaArtworkStudioSourceGuardTest {
    @Test
    fun productionApiExposesAllStudioMethodsThroughTheBoundedArtworkClient() {
        val api = readSource("src/main/java/com/papi/nova/api/PolarisApiClient.kt")
        val idsPath = Path.of("src/main/res/values/ids.xml")
        val ids = if (Files.exists(idsPath)) {
            String(Files.readAllBytes(idsPath), StandardCharsets.UTF_8)
        } else {
            ""
        }
        val update = api.section(
            "fun updateArtworkForLibrary(",
            "fun searchArtworkCandidates(",
        )
        val list = api.section(
            "fun listArtworkChoices(",
            "fun applyArtworkMatch(",
        )
        val apply = api.section(
            "fun applyArtworkSelections(",
            "fun clearArtworkOverride(",
        )
        val preview = api.section(
            "fun loadArtworkChoicePreviewInto(",
            "fun loadCoverInto(",
        )
        val mutation = api.section(
            "private fun executeArtworkManifestMutation(",
            "fun loadArtworkCandidatePreviewInto(",
        )

        assertTrue(update.contains("buildArtworkLibraryUpdateBody()"))
        assertTrue(update.contains(".url(\"\$baseUrl/games/\$gameId/artwork/resolve\")"))
        assertTrue(update.contains("parseArtworkLibraryUpdateResponse(json)"))
        assertTrue(update.contains("executeArtwork(request)"))
        assertTrue(update.propagatesCancellation())

        assertTrue(list.contains("buildArtworkChoiceBody(candidate)"))
        assertTrue(list.contains(".url(\"\$baseUrl/games/\$gameId/artwork/choices/\$normalizedKind\")"))
        assertTrue(list.contains("parseArtworkChoices(json, gameId, normalizedKind, serverAddress, resolvedHttpsPort)"))
        assertTrue(list.contains("executeArtwork(request)"))
        assertTrue(list.propagatesCancellation())

        assertTrue(apply.contains("buildArtworkSelectionBody(candidate, selections)"))
        assertTrue(apply.contains(".url(\"\$baseUrl/games/\$gameId/artwork/match\")"))
        assertEquals(1, apply.occurrences("executeArtworkManifestMutation("))
        assertTrue(mutation.propagatesCancellation())

        assertTrue(preview.contains("choice.kind !in ARTWORK_KINDS"))
        assertTrue(preview.contains("loadTrustedArtworkPreviewInto(view, choice.previewUrl)"))
        assertTrue(preview.contains("isTrustedCandidatePreviewUrl(url, serverAddress, resolvedHttpsPort)"))
        assertTrue(preview.contains("val requestMarker = Any()"))
        assertTrue(ids.contains("name=\"nova_artwork_request_key\""))
        assertTrue(preview.contains("view.setTag(R.id.nova_artwork_request_key, requestMarker)"))
        assertTrue(preview.contains("view.getTag(R.id.nova_artwork_request_key) === requestMarker"))
        assertFalse(preview.contains("view.tag = requestMarker"))
        assertFalse(preview.contains("polaris-artwork-preview:\$url"))
        assertFalse(preview.contains("view.tag = cacheKey"))
        assertTrue(api.contains("view.setTag(R.id.nova_artwork_request_key, spec.cacheKey)"))
        assertTrue(api.contains("view.getTag(R.id.nova_artwork_request_key) != spec.cacheKey"))
        assertFalse(api.contains("view.tag = cacheKey"))
        assertFalse(preview.contains("candidate.posterPreviewUrl"))

        assertTrue(api.contains("private fun executeArtwork(request: Request) = artworkClient.newCall(request).execute()"))
        assertTrue(api.contains(".followRedirects(false)"))
        assertTrue(api.contains(".followSslRedirects(false)"))
        assertTrue(api.contains("private const val ARTWORK_REQUEST_TIMEOUT_SECONDS = 120L"))
        assertTrue(api.contains("private val artworkClient: OkHttpClient by lazy"))
    }

    @Test
    fun detailSheetUsesArtworkStudioInsteadOfThePosterCentricFixMatchFlow() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")
        val studio = readSource("src/main/java/com/papi/nova/ui/NovaArtworkStudio.kt")
        val library = readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val updater = readSource("src/main/java/com/papi/nova/ui/NovaArtworkLibraryUpdater.kt")
        val gameUpdated = library.section("private fun onGameDetailResult(", "private fun launchGame(")

        assertTrue(detail.contains("NovaArtworkStudioState.from(game)"))
        assertTrue(detail.contains("NovaArtworkStudio("))
        assertTrue(detail.contains("fun acceptArtwork(manifest: PolarisGame.ArtworkManifest)"))
        assertTrue(detail.contains("currentGame = currentGame.copy(artwork = manifest)"))
        assertTrue(detail.contains("onGameUpdated?.invoke(currentGame)"))
        assertFalse(
            "Activity must not bypass the retained mutation owner with a second unguarded publication",
            gameUpdated.contains("artworkLibraryUpdateViewModel.publishCommittedArtwork("),
        )
        assertTrue(gameUpdated.contains("allGames = allGames.map"))
        assertFalse(updater.contains("fun publishCommittedArtwork(game: PolarisGame)"))
        assertTrue(updater.contains("internal suspend fun <T> withArtworkMutation("))
        assertTrue(updater.contains("private fun beginArtworkMutation("))
        assertFalse(detail.contains("private fun ArtworkCorrectionPanel("))
        assertFalse(detail.contains("apiClient.applyArtworkMatch("))

        assertTrue(studio.contains("R.string.nova_artwork_current_match"))
        assertTrue(studio.occurrences("R.string.nova_artwork_current_composition") >= 1)
        assertTrue(studio.occurrences("R.string.nova_artwork_live_preview") >= 1)
        assertTrue(studio.contains("state.currentKinds"))
        assertTrue(studio.contains("NovaArtworkIdentityPicker("))
        assertTrue(studio.contains("StudioCompositionAsset("))
        assertTrue(studio.contains("kind = NovaArtworkKinds.ICON"))
        assertTrue(detail.contains("apiClient.searchArtworkCandidates(currentGame.id, query)"))
        assertTrue(detail.contains("apiClient.listArtworkChoices(currentGame.id, candidate, normalizedKind)"))
        assertTrue(detail.contains("choicePreviewLoader = apiClient::loadArtworkChoicePreviewInto"))
        assertFalse(studio.contains(".selectionToken"))
        assertFalse(detail.contains(".selectionToken"))
    }

    @Test
    fun studioMutationsAreOwnedByRetainedViewModelAndUiCallbacksAreLifecycleGated() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")
        val library = readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val updater = readSource("src/main/java/com/papi/nova/ui/NovaArtworkLibraryUpdater.kt")

        assertFalse(detail.contains("apiClient.applyArtworkSelections("))
        assertFalse(detail.contains("apiClient.clearArtworkOverride("))
        assertTrue(detail.contains("onApplyArtwork?.invoke("))
        assertTrue(detail.contains("onClearArtwork?.invoke("))
        assertTrue(detail.contains("canPublishArtworkMutationUiForState(lifecycle.currentState)"))
        assertTrue(detail.contains("canPublishArtworkMutationUiForState("))
        assertTrue(detail.contains("Lifecycle.State.CREATED"))
        assertFalse(detail.contains("isAtLeast(Lifecycle.State.STARTED)"))
        assertTrue(updater.contains("fun applyArtworkSelections("))
        assertTrue(updater.contains("fun clearArtworkOverride("))
        assertTrue(updater.contains("apiClient.applyArtworkSelections("))
        assertTrue(updater.contains("apiClient.clearArtworkOverride("))
        assertTrue(updater.contains("withContext(NonCancellable)"))
        assertTrue(
            updater.contains(
                "coordinator.withArtworkMutation(game.id, NovaArtworkMutationOwner.STUDIO) { mutation ->",
            ),
        )
        assertTrue(updater.contains("coordinator.publishCommittedArtwork(mutation, manifest)"))
        assertTrue(detail.contains("artworkViewModel.applyArtworkSelections("))
        assertTrue(detail.contains("artworkViewModel.clearArtworkOverride("))
        assertTrue(detail.contains("NovaArtworkLibraryUpdateViewModel.Factory("))
        assertTrue(library.contains("NovaArtworkLibraryUpdateViewModel.Factory("))
    }

    @Test
    fun batchAndStudioShareOnePerGamePriorityProtocol() {
        val updater = readSource("src/main/java/com/papi/nova/ui/NovaArtworkLibraryUpdater.kt")
        val library = readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val studioMutation = updater.section(
            "private fun launchArtworkMutation(",
            "internal class Factory(",
        )
        val publisher = updater.section(
            "internal fun publishCommittedArtwork(",
            "private fun mergeArtwork(",
        )

        assertTrue(updater.contains("artworkMutationLocks.getOrPut(gameId) { Mutex() }"))
        assertTrue(
            updater.contains(
                "withArtworkMutation(game.id, NovaArtworkMutationOwner.BATCH) { mutation ->",
            ),
        )
        assertTrue(
            studioMutation.contains(
                "coordinator.withArtworkMutation(game.id, NovaArtworkMutationOwner.STUDIO) { mutation ->",
            ),
        )
        assertTrue(updater.contains("studioProtectedArtwork(game.id)?.let"))
        assertTrue(updater.contains("status = PolarisArtworkUpdateStatus.CUSTOM_PRESERVED"))
        assertTrue(updater.contains("latestStudioAdmissionByGameId"))
        assertTrue(updater.contains("isBatchSuperseded(mutation)"))
        assertTrue(updater.contains("reconcileStudioProtection(merged)"))
        assertTrue(updater.contains("studioOverrideProtectedArtworkByGameId[mutation.gameId] = manifest"))
        assertTrue(updater.contains("studioOverrideProtectedArtworkByGameId.remove(mutation.gameId)"))
        assertTrue(publisher.contains("latestArtworkMutationByGameId[mutation.gameId] != mutation.generation"))
        assertTrue(publisher.contains("mutation.owner == NovaArtworkMutationOwner.BATCH"))
        assertFalse(updater.contains("publishCommittedArtwork(game.id,"))
        assertFalse(library.contains("artworkLibraryUpdateViewModel.publishCommittedArtwork("))
    }

    @Test
    fun gameDetailsPreservesCachedHeroAvailabilityAndStablePresentationIdentity() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")
        val contentCall = detail.section(
            "NovaGameDetailContent(",
            "\n        loadOptimization(profilePreference)",
        )

        assertTrue(contentCall.contains("heroAvailable = currentGame.heroArtwork?.cached == true"))
        assertTrue(
            contentCall.contains(
                "heroPresentationKey = PolarisApiClient.artworkPresentationKey(currentGame, PolarisGame.ARTWORK_KIND_HERO)",
            ),
        )
        assertTrue(detail.contains("if (heroAvailable)"))
    }

    @Test
    fun mutationOperationGuardRejectsMissingOrLatePriorityAdmission() {
        val fenced = """
            coordinator.withArtworkMutation(game.id, NovaArtworkMutationOwner.STUDIO) { mutation ->
                val manifest = mutate()
                coordinator.publishCommittedArtwork(mutation, manifest)
            }
        """.trimIndent()
        val lateAdmission = """
            val manifest = mutate()
            coordinator.withArtworkMutation(game.id, NovaArtworkMutationOwner.STUDIO) { mutation ->
                coordinator.publishCommittedArtwork(mutation, manifest)
            }
        """.trimIndent()
        val unguarded = """
            val manifest = mutate()
            coordinator.publishCommittedArtwork(game.id, manifest)
        """.trimIndent()

        assertTrue(
            hasRequiredCallBefore(
                source = fenced,
                requiredCall = "withArtworkMutation(game.id, NovaArtworkMutationOwner.STUDIO)",
                beforeCall = "mutate()",
            ),
        )
        assertFalse(
            hasRequiredCallBefore(
                source = lateAdmission,
                requiredCall = "withArtworkMutation(game.id, NovaArtworkMutationOwner.STUDIO)",
                beforeCall = "mutate()",
            ),
        )
        assertFalse(unguarded.contains("publishCommittedArtwork(mutation, manifest)"))
    }

    @Test
    fun applyIsOneExplicitAtomicMutationAndFailureRequiresRefetch() {
        val studio = readSource("src/main/java/com/papi/nova/ui/NovaArtworkStudio.kt")
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")
        val updater = readSource("src/main/java/com/papi/nova/ui/NovaArtworkLibraryUpdater.kt")
        val stateReducer = studio.section(
            "fun reduce(action: NovaArtworkStudioAction)",
            "companion object {",
        )
        val applyHandler = detail.section(
            "onApplyArtwork = { candidate, selections ->",
            "onClearArtwork = {",
        )
        val clearHandler = detail.section(
            "onClearArtwork = {",
            "onLogoTransform = {",
        )
        val mutationOwner = updater.section(
            "private fun launchArtworkMutation(",
            "internal class Factory(",
        )

        assertEquals(1, updater.occurrences("apiClient.applyArtworkSelections("))
        assertFalse(detail.contains("apiClient.applyArtworkSelections("))
        assertFalse(detail.contains("apiClient.applyArtworkMatch("))
        assertTrue(applyHandler.contains("NovaArtworkStudioAction.MutationLoading"))
        assertTrue(applyHandler.contains("onApplyArtwork?.invoke("))
        assertTrue(clearHandler.contains("onClearArtwork?.invoke("))
        assertTrue(applyHandler.contains("NovaArtworkMutationResult.Committed"))
        assertTrue(clearHandler.contains("NovaArtworkMutationResult.Committed"))
        assertTrue(applyHandler.contains("acceptArtwork(manifest)"))
        assertTrue(clearHandler.contains("acceptArtwork(manifest)"))
        assertTrue(
            mutationOwner.contains(
                "coordinator.withArtworkMutation(game.id, NovaArtworkMutationOwner.STUDIO) { mutation ->",
            ),
        )
        assertTrue(mutationOwner.contains("withContext(Dispatchers.IO) { mutate() }"))
        assertTrue(mutationOwner.contains("coordinator.publishCommittedArtwork(mutation, manifest)"))
        assertTrue(mutationOwner.contains("withContext(NonCancellable)"))
        assertTrue(mutationOwner.propagatesCancellation())
        assertTrue(applyHandler.contains("NovaArtworkStudioAction.ApplyFailed("))
        assertTrue(stateReducer.contains("is NovaArtworkStudioAction.ApplyFailed -> copy("))
        assertTrue(stateReducer.contains("choicesByKind = emptyMap()"))
        assertTrue(stateReducer.contains("selections = emptyMap()"))
    }

    @Test
    fun mutationAcceptanceGuardRejectsDisconnectedApplyAndClearHandlers() {
        listOf(
            "apiClient.applyArtworkSelections(currentGame.id, candidate, selections)",
            "apiClient.clearArtworkOverride(currentGame.id)",
        ).forEach { requestCall ->
            val wired = "$requestCall\nif (manifest != null) acceptArtwork(manifest)"
            val disconnected = "$requestCall\nif (manifest != null) Unit"

            assertTrue(acceptsSuccessfulArtworkMutation(wired, requestCall))
            assertFalse(
                "$requestCall must fail its guard when acceptArtwork is disconnected",
                acceptsSuccessfulArtworkMutation(disconnected, requestCall),
            )
        }
    }

    @Test
    fun resetAndCancelDiscardDraftLocallyWithoutServerMutation() {
        val studio = readSource("src/main/java/com/papi/nova/ui/NovaArtworkStudio.kt")
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")
        val resetButton = studio.section(
            "text = stringResource(R.string.nova_artwork_studio_reset)",
            "text = stringResource(R.string.nova_artwork_studio_apply)",
        )
        val cancelButton = studio.section(
            "text = stringResource(R.string.cancel)",
            "@Composable\nprivate fun NovaArtworkStudioMatchSummary",
        )

        assertTrue(resetButton.contains("NovaArtworkStudioAction.EditingReset"))
        assertFalse(resetButton.contains("apiClient."))
        assertTrue(cancelButton.contains("NovaArtworkStudioAction.EditingCancelled"))
        assertFalse(cancelButton.contains("apiClient."))
        assertFalse(studio.contains("updateArtworkForLibrary("))
        assertTrue(studio.contains("choiceGeneration = choiceGeneration + 1"))
        assertTrue(studio.contains("action.generation == choiceGeneration"))
        assertTrue(detail.contains("val generation = artworkState.choiceGeneration"))
        assertTrue(detail.contains("generation = generation"))
        assertTrue(detail.contains("NovaArtworkStudioAction.ChoicesFailed("))
    }

    @Test
    fun ordinaryDetailAndLibraryLoadingCannotStartChoiceOrProviderMutationTraffic() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")
        val library = readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val detailLoad = detail.section(
            "private fun loadArtworkState(",
            "private fun saveArtworkTransform(",
        )

        listOf("listArtworkChoices(", "applyArtworkSelections(", "updateArtworkForLibrary(").forEach { call ->
            assertFalse("ordinary detail state loading must not call $call", detailLoad.contains("apiClient.$call"))
        }
        assertFalse(library.contains("apiClient.listArtworkChoices("))
        assertFalse(library.contains("apiClient.applyArtworkSelections("))
    }

    @Test
    fun studioRefreshUsesTheRetainedPerGameMutationCoordinator() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")
        val library = readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val updater = readSource("src/main/java/com/papi/nova/ui/NovaArtworkLibraryUpdater.kt")

        assertFalse(detail.contains("apiClient.resolveArtwork("))
        assertEquals(1, updater.occurrences("apiClient.resolveArtwork("))
        assertTrue(updater.contains("fun refreshArtwork("))
        assertTrue(updater.contains("apiClient.resolveArtwork(game.id, force = true)"))
        assertTrue(detail.contains("this@NovaGameDetailActivity.onRefreshArtwork?.invoke(currentGame)"))
        assertTrue(detail.contains("artworkViewModel.refreshArtwork("))
    }

    @Test
    fun englishResourcesCoverTheArtworkStudioSurface() {
        val strings = readSource("src/main/res/values/strings.xml")
        val required = listOf(
            "nova_artwork_studio_title",
            "nova_artwork_studio_summary",
            "nova_artwork_studio_expand",
            "nova_artwork_studio_collapse",
            "nova_artwork_current_match",
            "nova_artwork_current_composition",
            "nova_artwork_live_preview",
            "nova_artwork_change_match",
            "nova_artwork_search",
            "nova_artwork_select_identity",
            "nova_artwork_selected_match",
            "nova_artwork_loading_choices",
            "nova_artwork_no_choices",
            "nova_artwork_studio_reset",
            "nova_artwork_studio_apply",
            "nova_artwork_studio_cancel_description",
        )

        required.forEach { name ->
            assertTrue("missing Studio string $name", strings.contains("name=\"$name\""))
        }
    }

    @Test
    fun studioLaysOutAsTwoColumnsWithItsActionsPinnedBelowThem() {
        val studio = readSource("src/main/java/com/papi/nova/ui/NovaArtworkStudio.kt")

        // Nothing guarded the studio's layout until now -- this file's other test covers
        // the API client, so eight stages could be rearranged into two columns without a
        // single assertion noticing.
        assertTrue(
            "the studio splits by what each half is for: what the entry is, and what that " +
                "looks like. It is one Row of two weighted Columns above the threshold",
            studio.contains("val identityColumn: @Composable ColumnScope.() -> Unit") &&
                studio.contains("val previewColumn: @Composable ColumnScope.() -> Unit") &&
                studio.contains("Column(modifier = Modifier.weight(1f), content = identityColumn)") &&
                studio.contains("Column(modifier = Modifier.weight(1f), content = previewColumn)")
        )
        assertTrue(
            "and stacks in the same reading order where there is no room for two",
            studio.contains("val twoColumn = maxWidth >= NOVA_STUDIO_TWO_COLUMN_MIN") &&
                studio.contains("if (twoColumn) {")
        )
        assertTrue(
            "the preview sits beside the picker that changes it, not above it, so choosing " +
                "an image does not scroll the result away",
            studio.indexOf("val identityColumn") in
                0 until studio.indexOf("val previewColumn") &&
                studio.section("val previewColumn", "Column(modifier = Modifier.fillMaxWidth())")
                    .contains("NovaArtworkStudioComparison(")
        )
        assertTrue(
            "refresh belongs to the column it refreshes. The needle carries `text =` because " +
                "the bare id is also a prefix of nova_artwork_refresh_description, which sits " +
                "in the same column -- so the loose version held even after refresh moved out",
            studio.section("val identityColumn", "val previewColumn")
                .contains("text = stringResource(R.string.nova_artwork_refresh),")
        )
        assertFalse(
            "apply/reset/cancel are pinned below both columns rather than nested in the " +
                "branch that draws choices, which is what made the apply target move as " +
                "candidates loaded in above it",
            studio.section("val identityColumn", "val previewColumn")
                .contains("R.string.nova_artwork_studio_apply")
        )
        assertTrue(
            studio.contains("if (state.selectedCandidate != null) {") &&
                studio.section("Column(modifier = Modifier.fillMaxWidth())", "private fun NovaArtworkStudioMatchSummary")
                    .contains("R.string.nova_artwork_studio_apply")
        )
    }

    private fun String.propagatesCancellation(): Boolean =
        contains("catch (e: CancellationException)") && contains("throw e")

    private fun String.occurrences(needle: String): Int = split(needle).size - 1

    private fun hasRequiredCallBefore(
        source: String,
        requiredCall: String,
        beforeCall: String,
    ): Boolean {
        val requiredIndex = source.indexOf(requiredCall)
        val beforeIndex = source.indexOf(beforeCall)
        return requiredIndex >= 0 && beforeIndex >= 0 && requiredIndex < beforeIndex
    }

    private fun acceptsSuccessfulArtworkMutation(handler: String, requestCall: String): Boolean =
        handler.contains(requestCall) && handler.contains("if (manifest != null) acceptArtwork(manifest)")

    private fun String.section(start: String, end: String): String {
        val startIndex = indexOf(start)
        check(startIndex >= 0) { "Missing section start: $start" }
        val endIndex = indexOf(end, startIndex + start.length)
        check(endIndex >= 0) { "Missing section end: $end" }
        return substring(startIndex, endIndex)
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)
}
