package com.papi.nova.ui

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisArtworkChoice
import com.papi.nova.api.PolarisArtworkMatchCandidate
import com.papi.nova.shared.polaris.model.PolarisGame
import kotlinx.coroutines.delay
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaTextField
import com.papi.nova.ui.panel.NovaValueRow
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaBadge
import com.papi.nova.ui.compose.NovaRadius

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
    /**
     * The studio is the whole screen, not a card on one. The window already carries its title
     * and its margins, so the card's own header, border and two layers of padding only took
     * 28dp off each side and a row off the top, and said "Artwork Studio" twice. Without them
     * the two columns run the width the window gives them.
     */
    fillsDestination: Boolean = false,
    /** The height the destination's body has; the two previews size themselves to share it. */
    fitHeight: Dp = Dp.Unspecified,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    var expanded by remember(initialQuery) { mutableStateOf(initiallyExpanded) }
    if (fillsDestination) expanded = true
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    val title = stringResource(R.string.nova_artwork_studio_title)
    val summary = stringResource(R.string.nova_artwork_studio_summary)
    val toggleDescription = stringResource(
        if (expanded) R.string.nova_artwork_studio_collapse else R.string.nova_artwork_studio_expand,
    )

    Column(
        modifier = if (fillsDestination) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp)
                .clip(RoundedCornerShape(NovaRadius.hero))
                .background(surfaces.panel)
                .border(1.dp, surfaces.tileBorder, RoundedCornerShape(NovaRadius.hero))
        },
    ) {
        if (!fillsDestination) Row(
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
                        .clip(RoundedCornerShape(NovaRadius.row))
                        .background(colors.window),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                // Whole, on a second line if it needs one (R13).
                Text(summary, color = colors.textMuted, fontSize = 11.sp)
            }
            Text(if (expanded) "▴" else "▾", color = colors.textSecondary, fontSize = 18.sp)
        }

        // As the destination there is no header to open it with, so it is simply open.
        if (expanded) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (fillsDestination) {
                            // Room for a focus ring, which is drawn outside its control and was
                            // cut off at the edge of the scroll the moment nothing padded it.
                            Modifier.padding(horizontal = NOVA_STUDIO_RING_ROOM)
                        } else {
                            Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                        }
                    ),
            ) {
                val twoColumn = maxWidth >= NOVA_STUDIO_TWO_COLUMN_MIN

                // What the entry is: its identity, and which image is chosen per kind.
                val identityColumn: @Composable ColumnScope.() -> Unit = {
                    NovaArtworkStudioMatchSummary(state)
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
                    }
                    // Refresh belongs at the floor of the column it refreshes, not third
                    // from the top between two things it is not about.
                    NovaStudioButtonCells(modifier = Modifier.padding(top = 10.dp)) {
                        NovaActionButton(
                            text = stringResource(R.string.nova_artwork_refresh),
                            onClick = onRefresh,
                            enabled = !state.working && state.loadingKinds.isEmpty(),
                            contentDescription = stringResource(R.string.nova_artwork_refresh_description),
                        )
                        if (state.overrideActive) {
                            NovaActionButton(
                                text = stringResource(R.string.nova_artwork_clear_match),
                                onClick = onClear,
                                enabled = !state.working && state.loadingKinds.isEmpty(),
                                contentDescription = stringResource(R.string.nova_artwork_clear_match_description),
                            )
                        }
                    }
                }

                // What that looks like: the live preview, and the transform on it.
                val previewColumn: @Composable ColumnScope.() -> Unit = {
                    NovaArtworkStudioComparison(
                        state = state,
                        currentArtworkPresentationKey = currentArtworkPresentationKey,
                        currentArtworkLoader = currentArtworkLoader,
                        choicePreviewLoader = choicePreviewLoader,
                        // Beside the identity column the two start level; under it, a gap.
                        topGap = if (twoColumn) 0.dp else 10.dp,
                        compositionHeight = if (twoColumn) {
                            novaStudioCompositionHeight(fitHeight)
                        } else {
                            NOVA_STUDIO_COMPOSITION_HEIGHT
                        },
                    )
                    if (NovaArtworkKinds.LOGO in state.currentKinds) {
                        NovaArtworkLogoTransformControls(state, onTransform)
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    if (twoColumn) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(NOVA_STUDIO_GUTTER),
                        ) {
                            Column(modifier = Modifier.weight(1f), content = identityColumn)
                            Column(modifier = Modifier.weight(1f), content = previewColumn)
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth(), content = identityColumn)
                        Column(modifier = Modifier.fillMaxWidth(), content = previewColumn)
                    }

                    state.error?.let {
                        // Announced as it appears, as the game page's own notices are: it was
                        // plain text a screen reader never said (C26).
                        Text(
                            text = it,
                            color = colors.warning,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag(NOVA_ARTWORK_STUDIO_ERROR_TAG),
                        )
                    }

                    // Pinned below both columns, so the apply target stops moving as
                    // candidates load in above it.
                    if (state.selectedCandidate != null) {
                        NovaStudioButtonCells(modifier = Modifier.padding(top = 10.dp)) {
                            NovaActionButton(
                                text = stringResource(R.string.nova_artwork_studio_reset),
                                onClick = { onReset(NovaArtworkStudioAction.EditingReset) },
                                enabled = !state.working,
                            )
                            NovaActionButton(
                                text = stringResource(R.string.nova_artwork_studio_apply),
                                onClick = { onApply(state.selectedCandidate, state.selections) },
                                enabled = state.canApply,
                                primary = true,
                                contentDescription = stringResource(R.string.nova_artwork_studio_apply_description),
                            )
                            NovaActionButton(
                                text = stringResource(R.string.cancel),
                                onClick = {
                                    onCancel(NovaArtworkStudioAction.EditingCancelled)
                                    // A card folds away when its edit is dropped. The destination
                                    // has nothing to fold into: it used to leave the window empty.
                                    if (!fillsDestination) expanded = false
                                },
                                enabled = !state.working,
                                contentDescription = stringResource(R.string.nova_artwork_studio_cancel_description),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Below this there is no room for two, and stacking keeps the same reading order. */
private val NOVA_STUDIO_TWO_COLUMN_MIN = 620.dp
private val NOVA_STUDIO_GUTTER = 16.dp
private val NOVA_STUDIO_RING_ROOM = 4.dp
private val NOVA_STUDIO_COMPOSITION_HEIGHT = 156.dp
/** What the pair needs besides its two heights: a label each, the gap between, and slack so it fits rather than just fits. */
private val NOVA_STUDIO_COMPOSITION_CHROME = 68.dp

/**
 * How tall each of the two stacked previews is when the studio knows the height it has.
 *
 * They were a fixed 156dp, which on the Retroid ran the second one a few dp off the bottom of a
 * screen with nothing else below it, and on a tablet or a television left half the column empty.
 * They share what is there: never so short the composition stops reading, never so tall that a
 * hero crop turns into a poster.
 */
internal fun novaStudioCompositionHeight(fitHeight: Dp): Dp {
    if (fitHeight == Dp.Unspecified || fitHeight <= 0.dp) return NOVA_STUDIO_COMPOSITION_HEIGHT
    return ((fitHeight - NOVA_STUDIO_COMPOSITION_CHROME) / 2).coerceIn(132.dp, 240.dp)
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
        // Nothing here takes the cursor, so the whole title is on screen at rest: it wraps onto
        // as many lines as it needs, rather than running past in a marquee or ending in an
        // ellipsis nobody can open (R13).
        Text(
            text = state.currentMatchTitle,
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
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
    topGap: Dp = 10.dp,
    compositionHeight: Dp = NOVA_STUDIO_COMPOSITION_HEIGHT,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = topGap)) {
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
                    height = compositionHeight,
                )
                NovaArtworkComposition(
                    title = stringResource(R.string.nova_artwork_live_preview),
                    state = state,
                    useDraft = true,
                    currentArtworkPresentationKey = currentArtworkPresentationKey,
                    currentArtworkLoader = currentArtworkLoader,
                    choicePreviewLoader = choicePreviewLoader,
                    modifier = Modifier.weight(1f),
                    height = compositionHeight,
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
                    height = compositionHeight,
                )
                NovaArtworkComposition(
                    title = stringResource(R.string.nova_artwork_live_preview),
                    state = state,
                    useDraft = true,
                    currentArtworkPresentationKey = currentArtworkPresentationKey,
                    currentArtworkLoader = currentArtworkLoader,
                    choicePreviewLoader = choicePreviewLoader,
                    modifier = Modifier.fillMaxWidth(),
                    height = compositionHeight,
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
    height: Dp = NOVA_STUDIO_COMPOSITION_HEIGHT,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    Column(modifier) {
        Text(title, color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(NovaRadius.row))
                .background(colors.window)
                .border(1.dp, surfaces.tileBorder, RoundedCornerShape(NovaRadius.row))
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
                    .clip(RoundedCornerShape(NovaRadius.row))
                    .border(1.dp, colors.divider, RoundedCornerShape(NovaRadius.row)),
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
                    .clip(RoundedCornerShape(NovaRadius.row)),
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
    // The field was the studio's first focusable, so opening the studio handed it focus. It sits
    // out the first-focus pass, which then lands on Search below it, so A on a fresh studio
    // searches the game's name, and takes focus like anything else from then on.
    var fieldTakesFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(NOVA_FIRST_FOCUS_SETTLE_MS * 4)
        fieldTakesFocus = true
    }
    // Nova's field: walking the cursor onto it raises nothing, A on release or a tap opens it, B
    // while it is open hides the keyboard and leaves the studio where it is (R4), and the
    // keyboard's search key runs the search. It types in place, with no full screen keyboard.
    NovaTextField(
        value = query,
        onValueChange = onQueryChanged,
        label = stringResource(R.string.nova_artwork_search_title),
        imeAction = ImeAction.Search,
        onImeAction = { if (!state.working && query.isNotBlank()) onSearch() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .focusProperties { canFocus = fieldTakesFocus },
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StudioArtworkImage(
                presentationKey = "candidate:${candidate.provider}:${candidate.providerGameId}",
                contentDescription = stringResource(R.string.nova_artwork_preview_candidate, candidate.title),
                loader = { candidatePreviewLoader(it, candidate) },
                modifier = Modifier
                    .size(width = 64.dp, height = 82.dp)
                    .clip(RoundedCornerShape(NovaRadius.row))
                    .background(colors.window),
                scaleType = ImageView.ScaleType.CENTER_CROP,
            )
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                // The whole title, on as many lines as it takes: the row grows rather than cutting
                // the name the choice is between (R13).
                Text(
                    text = candidate.title,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
                val metadata = listOfNotNull(
                    candidate.releaseYear?.takeIf { it > 0 }?.toString(),
                    candidate.provider.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Text(metadata, color = colors.textMuted, fontSize = 10.sp)
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
            // Wrapping, never a marquee: a title is read at rest (R13).
            Text(
                candidate.title,
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
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
    // Which kind's alternatives are on show, changed in its own row (R1): Left and Right step
    // through the four, a tap takes one, and the kind on show carries the one check (R9). Four
    // buttons across a column cut their own labels short beside the check.
    NovaValueRow(
        title = stringResource(R.string.nova_artwork_title),
        options = NovaArtworkKinds.ALL.map { kind -> NovaOption(kind, stringResource(artworkKindLabel(kind))) },
        current = state.activeKind,
        onChange = onKindSelected,
        enabled = !state.working,
        modifier = Modifier.padding(top = 8.dp).testTag(NOVA_STUDIO_KIND_ROW_TAG),
    )

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
        // The alternatives wrap into rows of equal cells rather than running off the side of the
        // column: every one is whole at rest, and the cursor moves down a row as it moves across
        // one (R13). The screen scrolls with focus, so a long set is a scroll away, not a cut.
        else -> BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .testTag(NOVA_STUDIO_CHOICES_TAG),
        ) {
            val columns = novaStudioChoiceColumns(maxWidth)
            Column(verticalArrangement = Arrangement.spacedBy(NOVA_STUDIO_CHOICE_GAP)) {
                state.choicesByKind[state.activeKind].orEmpty().withIndex().chunked(columns).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NOVA_STUDIO_CHOICE_GAP),
                    ) {
                        row.forEach { (index, choice) ->
                            NovaArtworkChoiceCell(
                                index = index,
                                choice = choice,
                                selected = state.selections[state.activeKind] == choice,
                                enabled = !state.working && state.loadingKinds.isEmpty(),
                                onChoiceSelected = onChoiceSelected,
                                choicePreviewLoader = choicePreviewLoader,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // A short last row keeps the cells the width of the rows above it.
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * How many alternatives share a row of [width]: as many as keep a cell at least
 * [NOVA_STUDIO_CHOICE_MIN_WIDTH] wide, and never fewer than one.
 */
internal fun novaStudioChoiceColumns(width: Dp): Int {
    if (width == Dp.Infinity || width <= 0.dp) return 1
    return ((width + NOVA_STUDIO_CHOICE_GAP) / (NOVA_STUDIO_CHOICE_MIN_WIDTH + NOVA_STUDIO_CHOICE_GAP))
        .toInt()
        .coerceAtLeast(1)
}

/** One alternative: its preview, and the button that takes it, which carries the check once it is taken. */
@Composable
private fun NovaArtworkChoiceCell(
    index: Int,
    choice: PolarisArtworkChoice,
    selected: Boolean,
    enabled: Boolean,
    onChoiceSelected: (PolarisArtworkChoice) -> Unit,
    choicePreviewLoader: (ImageView, PolarisArtworkChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val kindLabel = stringResource(artworkKindLabel(choice.kind))
    Column(modifier) {
        StudioArtworkImage(
            presentationKey = "choice:${choice.kind}:${System.identityHashCode(choice)}",
            contentDescription = stringResource(R.string.nova_artwork_choice_description, kindLabel, index + 1),
            loader = { choicePreviewLoader(it, choice) },
            modifier = Modifier
                .fillMaxWidth()
                // The shapes the fixed 112dp cells had, kept as the cells take the row's width.
                .aspectRatio(
                    if (choice.kind == NovaArtworkKinds.POSTER) NOVA_STUDIO_POSTER_ASPECT else NOVA_STUDIO_WIDE_ASPECT,
                )
                .clip(RoundedCornerShape(NovaRadius.row))
                .background(colors.window)
                // The same hairline whichever is chosen: the button under it carries the
                // check, and an accent border would read as focus (R9).
                .border(1.dp, colors.divider, RoundedCornerShape(NovaRadius.row)),
            scaleType = if (choice.kind == NovaArtworkKinds.LOGO || choice.kind == NovaArtworkKinds.ICON) {
                ImageView.ScaleType.FIT_CENTER
            } else {
                ImageView.ScaleType.CENTER_CROP
            },
        )
        NovaActionButton(
            text = stringResource(if (selected) R.string.nova_artwork_selected else R.string.nova_artwork_select),
            onClick = { onChoiceSelected(choice) },
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            enabled = enabled,
            selected = selected,
            contentDescription = stringResource(R.string.nova_artwork_choice_select_description, kindLabel, index + 1),
            minHeight = 34.dp,
            fontSize = 11.sp,
        )
    }
}

/**
 * Buttons in equal cells: all in one row while every label fits its cell on one line, and
 * otherwise in rows of fewer cells, down to one a row, so no label is ever cut short (R13).
 */
@Composable
private fun NovaStudioButtonCells(
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val width = constraints.maxWidth
        val widest = measurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val perRow = novaStudioCellsPerRow(measurables.size, width, gapPx, widest)
        val cell = ((width - gapPx * (perRow - 1)) / perRow).coerceAtLeast(0)
        val rows = measurables.chunked(perRow).map { row -> row.map { it.measure(Constraints.fixedWidth(cell)) } }
        val heights = rows.map { row -> row.maxOf { it.height } }
        val height = heights.sum() + gapPx * (rows.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            rows.forEachIndexed { index, row ->
                row.forEachIndexed { column, placeable ->
                    placeable.placeRelative(column * (cell + gapPx), y + (heights[index] - placeable.height) / 2)
                }
                y += heights[index] + gapPx
            }
        }
    }
}

/**
 * How many of [count] buttons share a row [width] wide when the widest label needs [widest]: all
 * of them if it fits, then half as many to a row, and so on down to one.
 */
internal fun novaStudioCellsPerRow(count: Int, width: Int, gap: Int, widest: Int): Int {
    if (count <= 1) return 1
    var perRow = count
    while (perRow > 1 && (width - gap * (perRow - 1)) / perRow < widest) {
        perRow = (perRow + 1) / 2
    }
    return perRow
}

/** A cell is never narrower than the strip's fixed cells were, so a preview still reads. */
private val NOVA_STUDIO_CHOICE_MIN_WIDTH = 112.dp
private val NOVA_STUDIO_CHOICE_GAP = 8.dp
private const val NOVA_STUDIO_POSTER_ASPECT = 112f / 132f
private const val NOVA_STUDIO_WIDE_ASPECT = 112f / 76f
internal const val NOVA_STUDIO_CHOICES_TAG = "nova-artwork-choices"

/** The studio's error line, for a test to find it. */
internal const val NOVA_ARTWORK_STUDIO_ERROR_TAG = "nova-artwork-studio-error"
internal const val NOVA_STUDIO_KIND_ROW_TAG = "nova-artwork-kind"

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
    NovaStudioButtonCells(gap = 6.dp) {
        NovaActionButton(
            stringResource(R.string.nova_artwork_smaller),
            { onTransform((state.logoScale - 0.1f).coerceAtLeast(0.25f), state.logoX, state.logoY) },
        )
        NovaActionButton(
            stringResource(R.string.nova_artwork_reset),
            { onTransform(1f, 0.5f, 0.5f) },
        )
        NovaActionButton(
            stringResource(R.string.nova_artwork_larger),
            { onTransform((state.logoScale + 0.1f).coerceAtMost(4f), state.logoX, state.logoY) },
        )
    }
    NovaStudioButtonCells(gap = 6.dp, modifier = Modifier.padding(top = 6.dp)) {
        NovaActionButton(
            stringResource(R.string.nova_artwork_left),
            { onTransform(state.logoScale, (state.logoX - 0.05f).coerceAtLeast(0f), state.logoY) },
        )
        NovaActionButton(
            stringResource(R.string.nova_artwork_up),
            { onTransform(state.logoScale, state.logoX, (state.logoY - 0.05f).coerceAtLeast(0f)) },
        )
        NovaActionButton(
            stringResource(R.string.nova_artwork_down),
            { onTransform(state.logoScale, state.logoX, (state.logoY + 0.05f).coerceAtMost(1f)) },
        )
        NovaActionButton(
            stringResource(R.string.nova_artwork_right),
            { onTransform(state.logoScale, (state.logoX + 0.05f).coerceAtMost(1f), state.logoY) },
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
            onRelease = PolarisApiClient::releaseArtworkView,
        )
    }
}

private fun artworkKindLabel(kind: String): Int = when (kind) {
    NovaArtworkKinds.POSTER -> R.string.nova_artwork_kind_poster
    NovaArtworkKinds.HERO -> R.string.nova_artwork_kind_hero
    NovaArtworkKinds.LOGO -> R.string.nova_artwork_kind_logo
    else -> R.string.nova_artwork_kind_icon
}
