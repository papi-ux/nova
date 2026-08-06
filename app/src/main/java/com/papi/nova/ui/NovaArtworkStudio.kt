package com.papi.nova.ui

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papi.nova.R
import com.papi.nova.api.PolarisArtworkChoice
import com.papi.nova.api.PolarisArtworkMatchCandidate
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaBadge

object NovaArtworkKinds {
    const val POSTER = PolarisGame.ARTWORK_KIND_POSTER
    const val HERO = PolarisGame.ARTWORK_KIND_HERO
    const val LOGO = PolarisGame.ARTWORK_KIND_LOGO
    const val ICON = PolarisGame.ARTWORK_KIND_ICON
    val ALL = listOf(POSTER, HERO, LOGO, ICON)
}

data class NovaArtworkStudioState(
    val working: Boolean = false,
    val error: String? = null,
    val candidates: List<PolarisArtworkMatchCandidate> = emptyList(),
    val currentCandidate: PolarisArtworkMatchCandidate? = null,
    val selectedCandidate: PolarisArtworkMatchCandidate? = null,
    val activeKind: String = NovaArtworkKinds.POSTER,
    val choicesByKind: Map<String, List<PolarisArtworkChoice>> = emptyMap(),
    val loadingKinds: Set<String> = emptySet(),
    val loadedKinds: Set<String> = emptySet(),
    val choiceGeneration: Long = 0,
    val selections: Map<String, PolarisArtworkChoice> = emptyMap(),
    val overrideActive: Boolean = false,
    val currentMatchTitle: String = "",
    val currentMatchSource: String = "",
    val currentMatchManual: Boolean = false,
    val currentKinds: Set<String> = emptySet(),
    val logoScale: Float = 1f,
    val logoX: Float = 0.5f,
    val logoY: Float = 0.5f,
) {
    val canApply: Boolean
        get() = !working &&
            loadingKinds.isEmpty() &&
            selectedCandidate != null &&
            selections.isNotEmpty() &&
            selections.all { (kind, choice) ->
                kind in NovaArtworkKinds.ALL &&
                    choice.kind == kind &&
                    choicesByKind[kind]?.contains(choice) == true
            }

    fun needsChoiceLoad(kind: String): Boolean =
        selectedCandidate != null &&
            kind == activeKind &&
            kind in NovaArtworkKinds.ALL &&
            kind !in loadedKinds &&
            kind !in loadingKinds &&
            !working

    fun reduce(action: NovaArtworkStudioAction): NovaArtworkStudioState = when (action) {
        NovaArtworkStudioAction.SearchLoading -> copy(working = true, error = null)
        is NovaArtworkStudioAction.SearchLoaded -> copy(
            working = false,
            error = action.emptyMessage.takeIf { action.candidates.isEmpty() },
            candidates = action.candidates,
            selectedCandidate = null,
            choiceGeneration = choiceGeneration + 1,
            activeKind = NovaArtworkKinds.POSTER,
            choicesByKind = emptyMap(),
            loadingKinds = emptySet(),
            loadedKinds = emptySet(),
            selections = emptyMap(),
        )
        is NovaArtworkStudioAction.Failed -> copy(
            working = false,
            loadingKinds = if (action.kind == null) emptySet() else loadingKinds - action.kind,
            error = action.message,
        )

        is NovaArtworkStudioAction.IdentitySelected -> copy(
            error = null,
            selectedCandidate = action.candidate,
            choiceGeneration = choiceGeneration + 1,
            activeKind = NovaArtworkKinds.POSTER,
            choicesByKind = emptyMap(),
            loadingKinds = emptySet(),
            loadedKinds = emptySet(),
            selections = emptyMap(),
        )
        NovaArtworkStudioAction.IdentityChangeRequested -> copy(
            error = null,
            selectedCandidate = null,
            choiceGeneration = choiceGeneration + 1,
            activeKind = NovaArtworkKinds.POSTER,
            choicesByKind = emptyMap(),
            loadingKinds = emptySet(),
            loadedKinds = emptySet(),
            selections = emptyMap(),
        )
        is NovaArtworkStudioAction.KindSelected -> if (
            selectedCandidate != null && action.kind in NovaArtworkKinds.ALL
        ) copy(activeKind = action.kind, error = null) else this
        is NovaArtworkStudioAction.ChoicesLoading -> if (
            selectedCandidate == action.candidate &&
                action.generation == choiceGeneration &&
                action.kind == activeKind && action.kind in NovaArtworkKinds.ALL
        ) copy(loadingKinds = loadingKinds + action.kind, error = null) else this
        is NovaArtworkStudioAction.ChoicesLoaded -> if (
            selectedCandidate == action.candidate && action.generation == choiceGeneration
        ) {
            copy(
                loadingKinds = loadingKinds - action.kind,
                loadedKinds = loadedKinds + action.kind,
                choicesByKind = choicesByKind + (action.kind to action.choices.filter { it.kind == action.kind }),
                error = action.emptyMessage.takeIf { action.choices.isEmpty() },
            )
        } else {
            this
        }
        is NovaArtworkStudioAction.ChoicesFailed -> if (
            selectedCandidate == action.candidate && action.generation == choiceGeneration
        ) copy(
            loadingKinds = loadingKinds - action.kind,
            error = action.message,
        ) else this
        is NovaArtworkStudioAction.ChoiceSelected -> {
            val choice = action.choice
            if (
                choice.kind in loadedKinds &&
                choicesByKind[choice.kind]?.contains(choice) == true
            ) copy(selections = selections + (choice.kind to choice), error = null) else this
        }
        NovaArtworkStudioAction.MutationLoading -> copy(working = true, error = null)
        is NovaArtworkStudioAction.ApplyFailed -> copy(
            working = false,
            error = action.message,
            choicesByKind = emptyMap(),
            loadingKinds = emptySet(),
            loadedKinds = emptySet(),
            selections = emptyMap(),
        )

        NovaArtworkStudioAction.EditingReset -> copy(
            working = false,
            choiceGeneration = choiceGeneration + 1,
            error = null,
            selectedCandidate = currentCandidate,
            activeKind = NovaArtworkKinds.POSTER,
            choicesByKind = emptyMap(),
            loadingKinds = emptySet(),
            loadedKinds = emptySet(),
            selections = emptyMap(),
        )
        NovaArtworkStudioAction.EditingCancelled -> copy(
            working = false,
            choiceGeneration = choiceGeneration + 1,
            error = null,
            candidates = emptyList(),
            selectedCandidate = currentCandidate,
            activeKind = NovaArtworkKinds.POSTER,
            choicesByKind = emptyMap(),
            loadingKinds = emptySet(),
            loadedKinds = emptySet(),
            selections = emptyMap(),
        )
    }

    companion object {
        fun from(game: PolarisGame): NovaArtworkStudioState {
            val manifest = game.artwork
            val transform = manifest?.override?.logoTransform
            val currentCandidate = manifest?.match?.takeIf {
                it.source.isNotBlank() && it.providerGameId.isNotBlank()
            }?.let {
                PolarisArtworkMatchCandidate(
                    provider = it.source,
                    providerGameId = it.providerGameId,
                    title = it.title.takeIf(String::isNotBlank) ?: game.name,
                )
            }
            return NovaArtworkStudioState(
                currentCandidate = currentCandidate,
                selectedCandidate = currentCandidate,
                overrideActive = manifest?.override?.active == true,
                currentMatchTitle = manifest?.match?.title?.takeIf { it.isNotBlank() } ?: game.name,
                currentMatchSource = manifest?.match?.source.orEmpty(),
                currentMatchManual = manifest?.match?.manual == true,
                currentKinds = NovaArtworkKinds.ALL
                    .filterTo(linkedSetOf()) { game.artworkAsset(it)?.cached == true },
                logoScale = transform?.scale?.toFloat() ?: 1f,
                logoX = transform?.x?.toFloat() ?: 0.5f,
                logoY = transform?.y?.toFloat() ?: 0.5f,
            )
        }
    }
}

sealed interface NovaArtworkStudioAction {
    data object SearchLoading : NovaArtworkStudioAction
    data class SearchLoaded(
        val candidates: List<PolarisArtworkMatchCandidate>,
        val emptyMessage: String,
    ) : NovaArtworkStudioAction
    data class Failed(val message: String, val kind: String? = null) : NovaArtworkStudioAction
    data class IdentitySelected(val candidate: PolarisArtworkMatchCandidate) : NovaArtworkStudioAction
    data object IdentityChangeRequested : NovaArtworkStudioAction
    data class KindSelected(val kind: String) : NovaArtworkStudioAction
    data class ChoicesLoading(
        val candidate: PolarisArtworkMatchCandidate,
        val kind: String,
        val generation: Long = 0,
    ) : NovaArtworkStudioAction
    data class ChoicesLoaded(
        val candidate: PolarisArtworkMatchCandidate,
        val kind: String,
        val choices: List<PolarisArtworkChoice>,
        val emptyMessage: String = "",
        val generation: Long = 0,
    ) : NovaArtworkStudioAction
    data class ChoicesFailed(
        val message: String,
        val candidate: PolarisArtworkMatchCandidate,
        val kind: String,
        val generation: Long,
    ) : NovaArtworkStudioAction
    data class ChoiceSelected(val choice: PolarisArtworkChoice) : NovaArtworkStudioAction
    data object MutationLoading : NovaArtworkStudioAction
    data class ApplyFailed(val message: String) : NovaArtworkStudioAction
    data object EditingReset : NovaArtworkStudioAction
    data object EditingCancelled : NovaArtworkStudioAction
}

@Composable
fun NovaArtworkStudio(
    state: NovaArtworkStudioState,
    initialQuery: String,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onIdentitySelected: (PolarisArtworkMatchCandidate) -> Unit,
    onChangeIdentity: () -> Unit,
    onKindSelected: (String) -> Unit,
    onChoiceSelected: (PolarisArtworkChoice) -> Unit,
    onReset: (NovaArtworkStudioAction) -> Unit,
    onApply: (PolarisArtworkMatchCandidate, Map<String, PolarisArtworkChoice>) -> Unit,
    onCancel: (NovaArtworkStudioAction) -> Unit,
    onClear: () -> Unit,
    onTransform: (Float, Float, Float) -> Unit,
    candidatePreviewLoader: (ImageView, PolarisArtworkMatchCandidate) -> Unit,
    choicePreviewLoader: (ImageView, PolarisArtworkChoice) -> Unit,
    currentArtworkPresentationKey: (String) -> String,
    currentArtworkLoader: (ImageView, String) -> Unit,
    /** True when the studio is the destination rather than a row inside one. */
    initiallyExpanded: Boolean = false,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    var expanded by remember(initialQuery) { mutableStateOf(initiallyExpanded) }
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    val title = stringResource(R.string.nova_artwork_studio_title)
    val summary = stringResource(R.string.nova_artwork_studio_summary)
    val toggleDescription = stringResource(
        if (expanded) R.string.nova_artwork_studio_collapse else R.string.nova_artwork_studio_expand,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(surfaces.panel)
            .border(1.dp, surfaces.tileBorder, RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = toggleDescription }
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (NovaArtworkKinds.ICON in state.currentKinds) {
                StudioArtworkImage(
                    presentationKey = currentArtworkPresentationKey(NovaArtworkKinds.ICON),
                    contentDescription = stringResource(
                        R.string.nova_artwork_icon_content_description,
                        initialQuery,
                    ),
                    loader = { currentArtworkLoader(it, NovaArtworkKinds.ICON) },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.window),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    summary,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(if (expanded) "▴" else "▾", color = colors.textSecondary, fontSize = 18.sp)
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            ) {
                NovaArtworkStudioMatchSummary(state)
                NovaArtworkStudioComparison(
                    state = state,
                    currentArtworkPresentationKey = currentArtworkPresentationKey,
                    currentArtworkLoader = currentArtworkLoader,
                    choicePreviewLoader = choicePreviewLoader,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NovaActionButton(
                        text = stringResource(R.string.nova_artwork_refresh),
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                        enabled = !state.working && state.loadingKinds.isEmpty(),
                        contentDescription = stringResource(R.string.nova_artwork_refresh_description),
                    )
                    if (state.overrideActive) {
                        NovaActionButton(
                            text = stringResource(R.string.nova_artwork_clear_match),
                            onClick = onClear,
                            modifier = Modifier.weight(1f),
                            enabled = !state.working && state.loadingKinds.isEmpty(),
                            contentDescription = stringResource(R.string.nova_artwork_clear_match_description),
                        )
                    }
                }

                if (NovaArtworkKinds.LOGO in state.currentKinds) {
                    NovaArtworkLogoTransformControls(state, onTransform)
                }

                state.error?.let {
                    Text(
                        text = it,
                        color = colors.warning,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (state.selectedCandidate == null) {
                    NovaArtworkIdentityPicker(
                        state = state,
                        query = query,
                        onQueryChanged = { candidate ->
                            if (
                                candidate.toByteArray(Charsets.UTF_8).size <= 160 &&
                                candidate.none { it.code < 0x20 || it.code in 0x7f..0x9f }
                            ) query = candidate
                        },
                        onSearch = { onSearch(query.trim()) },
                        onIdentitySelected = onIdentitySelected,
                        candidatePreviewLoader = candidatePreviewLoader,
                    )
                } else {
                    NovaArtworkChoicePicker(
                        state = state,
                        onChangeIdentity = onChangeIdentity,
                        onKindSelected = onKindSelected,
                        onChoiceSelected = onChoiceSelected,
                        choicePreviewLoader = choicePreviewLoader,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NovaActionButton(
                            text = stringResource(R.string.nova_artwork_studio_reset),
                            onClick = { onReset(NovaArtworkStudioAction.EditingReset) },
                            modifier = Modifier.weight(1f),
                            enabled = !state.working,
                        )
                        NovaActionButton(
                            text = stringResource(R.string.nova_artwork_studio_apply),
                            onClick = { onApply(state.selectedCandidate, state.selections) },
                            modifier = Modifier.weight(1f),
                            enabled = state.canApply,
                            primary = true,
                            contentDescription = stringResource(R.string.nova_artwork_studio_apply_description),
                        )
                        NovaActionButton(
                            text = stringResource(R.string.cancel),
                            onClick = {
                                onCancel(NovaArtworkStudioAction.EditingCancelled)
                                expanded = false
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !state.working,
                            contentDescription = stringResource(R.string.nova_artwork_studio_cancel_description),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NovaArtworkStudioMatchSummary(state: NovaArtworkStudioState) {
    val colors = LocalNovaComposeColors.current
    Text(
        text = stringResource(R.string.nova_artwork_current_match),
        color = colors.textMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = state.currentMatchTitle,
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.currentMatchSource.isNotBlank()) {
            NovaBadge(text = state.currentMatchSource, fontSize = 10.sp)
        }
        NovaBadge(
            text = stringResource(
                if (state.currentMatchManual) {
                    R.string.nova_artwork_match_manual
                } else {
                    R.string.nova_artwork_match_automatic
                },
            ),
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun NovaArtworkStudioComparison(
    state: NovaArtworkStudioState,
    currentArtworkPresentationKey: (String) -> String,
    currentArtworkLoader: (ImageView, String) -> Unit,
    choicePreviewLoader: (ImageView, PolarisArtworkChoice) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        val wide = maxWidth >= 620.dp
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NovaArtworkComposition(
                    title = stringResource(R.string.nova_artwork_current_composition),
                    state = state,
                    useDraft = false,
                    currentArtworkPresentationKey = currentArtworkPresentationKey,
                    currentArtworkLoader = currentArtworkLoader,
                    choicePreviewLoader = choicePreviewLoader,
                    modifier = Modifier.weight(1f),
                )
                NovaArtworkComposition(
                    title = stringResource(R.string.nova_artwork_live_preview),
                    state = state,
                    useDraft = true,
                    currentArtworkPresentationKey = currentArtworkPresentationKey,
                    currentArtworkLoader = currentArtworkLoader,
                    choicePreviewLoader = choicePreviewLoader,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NovaArtworkComposition(
                    title = stringResource(R.string.nova_artwork_current_composition),
                    state = state,
                    useDraft = false,
                    currentArtworkPresentationKey = currentArtworkPresentationKey,
                    currentArtworkLoader = currentArtworkLoader,
                    choicePreviewLoader = choicePreviewLoader,
                    modifier = Modifier.fillMaxWidth(),
                )
                NovaArtworkComposition(
                    title = stringResource(R.string.nova_artwork_live_preview),
                    state = state,
                    useDraft = true,
                    currentArtworkPresentationKey = currentArtworkPresentationKey,
                    currentArtworkLoader = currentArtworkLoader,
                    choicePreviewLoader = choicePreviewLoader,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun NovaArtworkComposition(
    title: String,
    state: NovaArtworkStudioState,
    useDraft: Boolean,
    currentArtworkPresentationKey: (String) -> String,
    currentArtworkLoader: (ImageView, String) -> Unit,
    choicePreviewLoader: (ImageView, PolarisArtworkChoice) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    Column(modifier) {
        Text(title, color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.window)
                .border(1.dp, surfaces.tileBorder, RoundedCornerShape(12.dp))
                .semantics { contentDescription = title },
        ) {
            StudioCompositionAsset(
                kind = NovaArtworkKinds.HERO,
                state = state,
                useDraft = useDraft,
                currentArtworkPresentationKey = currentArtworkPresentationKey,
                currentArtworkLoader = currentArtworkLoader,
                choicePreviewLoader = choicePreviewLoader,
                modifier = Modifier.matchParentSize(),
                scaleType = ImageView.ScaleType.CENTER_CROP,
                contentDescription = stringResource(R.string.nova_artwork_preview_hero),
            )
            StudioCompositionAsset(
                kind = NovaArtworkKinds.POSTER,
                state = state,
                useDraft = useDraft,
                currentArtworkPresentationKey = currentArtworkPresentationKey,
                currentArtworkLoader = currentArtworkLoader,
                choicePreviewLoader = choicePreviewLoader,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(10.dp)
                    .size(width = 68.dp, height = 96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, colors.divider, RoundedCornerShape(8.dp)),
                scaleType = ImageView.ScaleType.CENTER_CROP,
                contentDescription = stringResource(R.string.nova_artwork_preview_poster),
            )
            StudioCompositionAsset(
                kind = NovaArtworkKinds.LOGO,
                state = state,
                useDraft = useDraft,
                currentArtworkPresentationKey = currentArtworkPresentationKey,
                currentArtworkLoader = currentArtworkLoader,
                choicePreviewLoader = choicePreviewLoader,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 18.dp, end = 14.dp)
                    .width(126.dp)
                    .height(54.dp)
                    .graphicsLayer {
                        scaleX = state.logoScale
                        scaleY = state.logoScale
                        translationX = (state.logoX - 0.5f) * 80f
                        translationY = (state.logoY - 0.5f) * 40f
                    },
                scaleType = ImageView.ScaleType.FIT_CENTER,
                contentDescription = stringResource(R.string.nova_artwork_preview_logo),
            )
            StudioCompositionAsset(
                kind = NovaArtworkKinds.ICON,
                state = state,
                useDraft = useDraft,
                currentArtworkPresentationKey = currentArtworkPresentationKey,
                currentArtworkLoader = currentArtworkLoader,
                choicePreviewLoader = choicePreviewLoader,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp)),
                scaleType = ImageView.ScaleType.FIT_CENTER,
                contentDescription = stringResource(R.string.nova_artwork_preview_icon),
            )
        }
    }
}

@Composable
private fun StudioCompositionAsset(
    kind: String,
    state: NovaArtworkStudioState,
    useDraft: Boolean,
    currentArtworkPresentationKey: (String) -> String,
    currentArtworkLoader: (ImageView, String) -> Unit,
    choicePreviewLoader: (ImageView, PolarisArtworkChoice) -> Unit,
    modifier: Modifier,
    scaleType: ImageView.ScaleType,
    contentDescription: String,
) {
    val selected = state.selections[kind].takeIf { useDraft }
    when {
        selected != null -> StudioArtworkImage(
            presentationKey = "draft:$kind:${System.identityHashCode(selected)}",
            contentDescription = contentDescription,
            loader = { choicePreviewLoader(it, selected) },
            modifier = modifier,
            scaleType = scaleType,
        )
        kind in state.currentKinds -> StudioArtworkImage(
            presentationKey = currentArtworkPresentationKey(kind),
            contentDescription = contentDescription,
            loader = { currentArtworkLoader(it, kind) },
            modifier = modifier,
            scaleType = scaleType,
        )
    }
}

@Composable
private fun NovaArtworkIdentityPicker(
    state: NovaArtworkStudioState,
    query: String,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onIdentitySelected: (PolarisArtworkMatchCandidate) -> Unit,
    candidatePreviewLoader: (ImageView, PolarisArtworkMatchCandidate) -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    Text(
        text = stringResource(R.string.nova_artwork_change_match),
        color = colors.textPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        text = stringResource(R.string.nova_artwork_change_match_summary),
        color = colors.textMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        label = { Text(stringResource(R.string.nova_artwork_search_title)) },
        singleLine = true,
        enabled = !state.working,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    NovaActionButton(
        text = stringResource(
            if (state.working) R.string.nova_artwork_searching else R.string.nova_artwork_search,
        ),
        onClick = onSearch,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !state.working && query.isNotBlank(),
        contentDescription = stringResource(R.string.nova_artwork_search_description),
    )
    state.candidates.forEach { candidate ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StudioArtworkImage(
                presentationKey = "candidate:${candidate.provider}:${candidate.providerGameId}",
                contentDescription = stringResource(R.string.nova_artwork_preview_candidate, candidate.title),
                loader = { candidatePreviewLoader(it, candidate) },
                modifier = Modifier
                    .size(width = 64.dp, height = 82.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.window),
                scaleType = ImageView.ScaleType.CENTER_CROP,
            )
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(
                    candidate.title,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val metadata = listOfNotNull(
                    candidate.releaseYear?.takeIf { it > 0 }?.toString(),
                    candidate.provider.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Text(metadata, color = colors.textMuted, fontSize = 10.sp, maxLines = 1)
                }
                NovaActionButton(
                    text = stringResource(R.string.nova_artwork_select_identity),
                    onClick = { onIdentitySelected(candidate) },
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                    enabled = !state.working,
                    contentDescription = stringResource(R.string.nova_artwork_select_identity_description, candidate.title),
                    minHeight = 36.dp,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun NovaArtworkChoicePicker(
    state: NovaArtworkStudioState,
    onChangeIdentity: () -> Unit,
    onKindSelected: (String) -> Unit,
    onChoiceSelected: (PolarisArtworkChoice) -> Unit,
    choicePreviewLoader: (ImageView, PolarisArtworkChoice) -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val candidate = state.selectedCandidate ?: return
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.nova_artwork_selected_match),
                color = colors.textMuted,
                fontSize = 10.sp,
            )
            Text(
                candidate.title,
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NovaActionButton(
            text = stringResource(R.string.nova_artwork_change_match),
            onClick = onChangeIdentity,
            enabled = !state.working && state.loadingKinds.isEmpty(),
            contentDescription = stringResource(R.string.nova_artwork_change_match_description),
            minHeight = 38.dp,
            fontSize = 12.sp,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NovaArtworkKinds.ALL.forEach { kind ->
            NovaActionButton(
                text = stringResource(artworkKindLabel(kind)),
                onClick = { onKindSelected(kind) },
                modifier = Modifier.weight(1f),
                enabled = !state.working,
                primary = kind == state.activeKind,
                contentDescription = stringResource(
                    R.string.nova_artwork_kind_tab_description,
                    stringResource(artworkKindLabel(kind)),
                ),
                minHeight = 38.dp,
                fontSize = 11.sp,
            )
        }
    }

    when {
        state.activeKind in state.loadingKinds -> Text(
            text = stringResource(R.string.nova_artwork_loading_choices),
            color = colors.textMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        state.activeKind in state.loadedKinds && state.choicesByKind[state.activeKind].isNullOrEmpty() -> Text(
            text = stringResource(R.string.nova_artwork_no_choices),
            color = colors.textMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        else -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.choicesByKind[state.activeKind].orEmpty().forEachIndexed { index, choice ->
                val selected = state.selections[state.activeKind] == choice
                Column(Modifier.width(112.dp)) {
                    StudioArtworkImage(
                        presentationKey = "choice:${choice.kind}:${System.identityHashCode(choice)}",
                        contentDescription = stringResource(
                            R.string.nova_artwork_choice_description,
                            stringResource(artworkKindLabel(choice.kind)),
                            index + 1,
                        ),
                        loader = { choicePreviewLoader(it, choice) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (choice.kind == NovaArtworkKinds.POSTER) 132.dp else 76.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.window)
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) colors.accent else colors.divider,
                                RoundedCornerShape(9.dp),
                            ),
                        scaleType = if (choice.kind == NovaArtworkKinds.LOGO || choice.kind == NovaArtworkKinds.ICON) {
                            ImageView.ScaleType.FIT_CENTER
                        } else {
                            ImageView.ScaleType.CENTER_CROP
                        },
                    )
                    NovaActionButton(
                        text = stringResource(
                            if (selected) R.string.nova_artwork_selected else R.string.nova_artwork_select,
                        ),
                        onClick = { onChoiceSelected(choice) },
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                        enabled = !state.working && state.loadingKinds.isEmpty(),
                        primary = selected,
                        contentDescription = stringResource(
                            R.string.nova_artwork_choice_select_description,
                            stringResource(artworkKindLabel(choice.kind)),
                            index + 1,
                        ),
                        minHeight = 34.dp,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun NovaArtworkLogoTransformControls(
    state: NovaArtworkStudioState,
    onTransform: (Float, Float, Float) -> Unit,
) {
    Text(
        text = stringResource(R.string.nova_artwork_logo_controls),
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NovaActionButton(
            stringResource(R.string.nova_artwork_smaller),
            { onTransform((state.logoScale - 0.1f).coerceAtLeast(0.25f), state.logoX, state.logoY) },
            Modifier.weight(1f),
        )
        NovaActionButton(
            stringResource(R.string.nova_artwork_reset),
            { onTransform(1f, 0.5f, 0.5f) },
            Modifier.weight(1f),
        )
        NovaActionButton(
            stringResource(R.string.nova_artwork_larger),
            { onTransform((state.logoScale + 0.1f).coerceAtMost(4f), state.logoX, state.logoY) },
            Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        NovaActionButton(
            stringResource(R.string.nova_artwork_left),
            { onTransform(state.logoScale, (state.logoX - 0.05f).coerceAtLeast(0f), state.logoY) },
            Modifier.weight(1f),
        )
        NovaActionButton(
            stringResource(R.string.nova_artwork_up),
            { onTransform(state.logoScale, state.logoX, (state.logoY - 0.05f).coerceAtLeast(0f)) },
            Modifier.weight(1f),
        )
        NovaActionButton(
            stringResource(R.string.nova_artwork_down),
            { onTransform(state.logoScale, state.logoX, (state.logoY + 0.05f).coerceAtMost(1f)) },
            Modifier.weight(1f),
        )
        NovaActionButton(
            stringResource(R.string.nova_artwork_right),
            { onTransform(state.logoScale, (state.logoX + 0.05f).coerceAtMost(1f), state.logoY) },
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StudioArtworkImage(
    presentationKey: String,
    contentDescription: String,
    loader: (ImageView) -> Unit,
    modifier: Modifier,
    scaleType: ImageView.ScaleType = ImageView.ScaleType.FIT_CENTER,
) {
    key(presentationKey) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    this.scaleType = scaleType
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    this.contentDescription = contentDescription
                    loader(this)
                }
            },
            modifier = modifier.semantics { this.contentDescription = contentDescription },
        )
    }
}

private fun artworkKindLabel(kind: String): Int = when (kind) {
    NovaArtworkKinds.POSTER -> R.string.nova_artwork_kind_poster
    NovaArtworkKinds.HERO -> R.string.nova_artwork_kind_hero
    NovaArtworkKinds.LOGO -> R.string.nova_artwork_kind_logo
    else -> R.string.nova_artwork_kind_icon
}
