package com.papi.nova.ui

import android.graphics.Color
import android.view.View
import android.widget.ImageView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.novaConfirm
import com.papi.nova.ui.compose.novaFocusTick
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.novaClickable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath

private const val NovaPosterAnimationDurationMillis = 180
/** The mapper owns the number, because the grid's top inset is derived from it. */
internal val NovaPosterFocusedLift = NovaLibraryUiStateMapper.posterFocusLiftDp().dp

/**
 * Box art reads as box art, not as an app tile. The cinematic concept uses a 7px radius on a
 * 200px poster (~3.5% of width); 12dp on a ~90dp stage card was nearly four times that.
 */
private val NovaPosterCornerRadius = NovaRadius.row

@Composable
internal fun NovaLibraryPosterCard(
    game: PolarisGame,
    layoutMode: NovaLibraryLayoutMode,
    apiClient: PolarisApiClient,
    showPosterTitle: Boolean,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    onFocused: () -> Unit = {},
    onNavigate: ((Int) -> Boolean)? = null,
    posterLoader: ((ImageView, PolarisGame) -> Unit)? = null,
    /** Stage keeps focus on one fixed owner while the selected game swaps underneath it. */
    focusedOverride: Boolean? = null,
    running: Boolean = false,
) {
    val presentationSpec = NovaLibraryUiStateMapper.posterPresentationSpec(layoutMode)
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val posterLoaderIdentity: Any = posterLoader ?: apiClient
    var focused by remember(game.id) { mutableStateOf(false) }
    val visualFocused = focusedOverride ?: focused
    val haptics = LocalHapticFeedback.current
    val alpha by animateFloatAsState(
        targetValue = if (visualFocused) 1f else presentationSpec.unfocusedAlpha,
        animationSpec = tween(durationMillis = NovaPosterAnimationDurationMillis),
        label = "NovaPosterAlpha",
    )
    val lift by animateDpAsState(
        targetValue = if (visualFocused && layoutMode != NovaLibraryLayoutMode.STAGE) NovaPosterFocusedLift else 0.dp,
        animationSpec = tween(durationMillis = NovaPosterAnimationDurationMillis),
        label = "NovaPosterLift",
    )
    val title = game.name.ifBlank { androidx.compose.ui.res.stringResource(R.string.nova_library_unknown_game) }
    val metadata = novaLibraryPosterMetadata(game)
    val hdrLabel = androidx.compose.ui.res.stringResource(R.string.badge_hdr)
    val lastPlayedLabel = if (game.lastLaunched > 0) androidx.compose.ui.res.stringResource(
        R.string.nova_library_meta_last_played,
        android.text.format.DateUtils.getRelativeTimeSpanString(
            game.lastLaunched.coerceAtMost(Long.MAX_VALUE / 1000) * 1000,
            System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE),
    ) else null
    val runningLabel = androidx.compose.ui.res.stringResource(R.string.nova_library_stage_running)
    val detailsLabel = androidx.compose.ui.res.stringResource(R.string.nova_library_card_action_details)
    val accessibleLabel = remember(
        title,
        metadata,
        game.hdrSupported,
        game.lastLaunched,
        hdrLabel,
        lastPlayedLabel,
        running,
        runningLabel,
        detailsLabel,
    ) {
        buildList {
            add(title)
            if (metadata.isNotBlank()) add(metadata)
            if (game.hdrSupported) add(hdrLabel)
            if (lastPlayedLabel != null) add(lastPlayedLabel)
            if (running) add(runningLabel)
            add(detailsLabel)
        }.joinToString(". ")
    }
    val focusRequesterModifier = if (focusRequester == null) {
        Modifier
    } else {
        Modifier.focusRequester(focusRequester)
    }

    Column(
        modifier = modifier
            .zIndex(if (visualFocused) 1f else 0f)
            .testTag("nova-poster-${game.id}")
            .then(focusRequesterModifier)
            .onFocusChanged { state ->
                if (state.isFocused && !focused) haptics.novaFocusTick()
                focused = state.isFocused
                onFocusChanged(state.isFocused)
                if (state.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> onNavigate?.invoke(-1) ?: false
                    Key.DirectionRight -> onNavigate?.invoke(1) ?: false
                    else -> false
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = accessibleLabel
            }
            // A on release, and only on the poster it was pressed on.
            .novaClickable(role = Role.Button) {
                haptics.novaConfirm()
                onOpenDetail()
            },
    ) {
        NovaLibraryPosterArtwork(
            game = game,
            focused = visualFocused,
            apiClient = apiClient,
            posterLoader = posterLoader,
            posterLoaderIdentity = posterLoaderIdentity,
            alpha = alpha,
            lift = lift,
            backgroundColor = surfaces.mediaPlaceholder,
            running = running,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = presentationSpec.focusGutterDp.dp),
        )
        if (showPosterTitle) {
            NovaLibraryPosterCaption(
                game = game,
                title = title,
                layoutMode = layoutMode,
                color = colors.textPrimary,
            )
        }
    }
}

@Composable
private fun NovaLibraryPosterArtwork(
    game: PolarisGame,
    focused: Boolean,
    apiClient: PolarisApiClient,
    posterLoader: ((ImageView, PolarisGame) -> Unit)?,
    posterLoaderIdentity: Any,
    alpha: Float,
    lift: androidx.compose.ui.unit.Dp,
    backgroundColor: androidx.compose.ui.graphics.Color,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(NovaPosterCornerRadius)
    val ring = LocalNovaLibrarySurfaces.current.focusRing
    val ringProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = NovaPanelMetrics.FocusMillis),
        label = "NovaPosterRing",
    )
    val artworkRevisionKey = PolarisApiClient.artworkPresentationKey(
        game,
        PolarisGame.ARTWORK_KIND_POSTER,
    )
    val posterPresentationKey = remember(artworkRevisionKey, posterLoaderIdentity) {
        artworkRevisionKey to posterLoaderIdentity
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(NovaLibraryUiStateMapper.posterAspectRatio())
            // Focus lifts the poster and rings it; it no longer grows, so a focused poster
            // never overlaps its neighbours or reaches past the grid's edges.
            .graphicsLayer {
                this.alpha = alpha
                translationY = -lift.toPx()
                this.shape = RoundedCornerShape(NovaPosterCornerRadius)
                clip = true
            }
            .testTag("nova-poster-art-${game.id}")
            .background(backgroundColor),
    ) {
        key(posterPresentationKey) {

            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(Color.TRANSPARENT)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        isFocusable = false
                        isFocusableInTouchMode = false
                        isClickable = false
                        isLongClickable = false
                        contentDescription = null
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    view.isFocusable = false
                    view.isFocusableInTouchMode = false
                    view.isClickable = false
                    view.isLongClickable = false
                    view.contentDescription = null
                    if (view.getTag(R.id.nova_artwork_presentation_key) != posterPresentationKey) {
                        view.setTag(R.id.nova_artwork_presentation_key, posterPresentationKey)
                        view.setImageDrawable(null)
                        posterLoader?.invoke(view, game) ?: apiClient.loadCoverInto(view, game)
                    }
                },
            )
        }
        // The one focus ring, 3dp inside the poster's corners, drawn over the artwork.
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    val path = Path().apply { addOutline(outline) }
                    val stroke = Stroke(NovaPanelMetrics.FocusRingWidth.toPx() * 2f)
                    onDrawBehind {
                        if (ringProgress > 0f) {
                            clipPath(path) { drawPath(path, ring.copy(alpha = ring.alpha * ringProgress), style = stroke) }
                        }
                    }
                },
        )
        if (running) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.nova_library_stage_running),
                color = LocalNovaComposeColors.current.textPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopStart)
                    .padding(6.dp).clip(shape)
                    .background(LocalNovaLibrarySurfaces.current.focusedArtworkScrim)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("nova-poster-running-${game.id}"),
            )
        }
    }
}


@Composable
private fun NovaLibraryPosterCaption(
    game: PolarisGame,
    title: String,
    layoutMode: NovaLibraryLayoutMode,
    color: androidx.compose.ui.graphics.Color,
) {
    Text(
        text = title,
        color = color,
        fontSize = when (layoutMode) {
            NovaLibraryLayoutMode.COMPACT -> 11.sp
            NovaLibraryLayoutMode.STAGE -> NOVA_STAGE_CAPTION_FONT_SIZE_SP.sp
            else -> 12.sp
        },
        // Stage reserves two 17sp lines beside the selected cover. Other layouts retain
        // their inherited body line height and existing row geometry.
        lineHeight = if (layoutMode == NovaLibraryLayoutMode.STAGE) NOVA_STAGE_CAPTION_LINE_HEIGHT_SP.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        fontWeight = FontWeight.SemiBold,
        maxLines = if (layoutMode == NovaLibraryLayoutMode.COMPACT) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(
                start = NovaLibraryUiStateMapper.posterPresentationSpec(layoutMode).focusGutterDp.dp,
                top = if (layoutMode == NovaLibraryLayoutMode.STAGE) NOVA_STAGE_CAPTION_TOP_PADDING_DP.dp else 6.dp,
                end = NovaLibraryUiStateMapper.posterPresentationSpec(layoutMode).focusGutterDp.dp,
            )
            .widthIn(min = 0.dp)
            .testTag("nova-poster-caption-${game.id}"),
    )
}

private fun novaLibraryPosterMetadata(game: PolarisGame): String =
    listOf(game.sourceLabel, game.categoryLabel)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" · ")
