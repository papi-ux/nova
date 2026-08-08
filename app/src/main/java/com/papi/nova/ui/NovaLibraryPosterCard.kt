package com.papi.nova.ui

import android.graphics.Color
import android.view.View
import android.widget.ImageView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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

private const val NovaPosterAnimationDurationMillis = 180
internal val NovaPosterFocusedLift = 10.dp

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
) {
    val presentationSpec = NovaLibraryUiStateMapper.posterPresentationSpec(layoutMode)
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val posterLoaderIdentity: Any = posterLoader ?: apiClient
    var focused by remember(game.id) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (focused) presentationSpec.focusedScale else 1f,
        animationSpec = tween(durationMillis = NovaPosterAnimationDurationMillis),
        label = "NovaPosterScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (focused) 1f else presentationSpec.unfocusedAlpha,
        animationSpec = tween(durationMillis = NovaPosterAnimationDurationMillis),
        label = "NovaPosterAlpha",
    )
    val lift by animateDpAsState(
        targetValue = if (focused) NovaPosterFocusedLift else 0.dp,
        animationSpec = tween(durationMillis = NovaPosterAnimationDurationMillis),
        label = "NovaPosterLift",
    )
    val title = game.name.ifBlank { androidx.compose.ui.res.stringResource(R.string.nova_library_unknown_game) }
    val metadata = novaLibraryPosterMetadata(game)
    val hdrLabel = androidx.compose.ui.res.stringResource(R.string.badge_hdr)
    val recentLabel = androidx.compose.ui.res.stringResource(R.string.nova_library_filter_recent)
    val detailsLabel = androidx.compose.ui.res.stringResource(R.string.nova_library_card_action_details)
    val accessibleLabel = remember(
        title,
        metadata,
        game.hdrSupported,
        game.lastLaunched,
        hdrLabel,
        recentLabel,
        detailsLabel,
    ) {
        buildList {
            add(title)
            if (metadata.isNotBlank()) add(metadata)
            if (game.hdrSupported) add(hdrLabel)
            if (game.lastLaunched > 0L) add(recentLabel)
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
            .zIndex(if (focused) 1f else 0f)
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
            .combinedClickable(
                role = Role.Button,
                onClick = {
                    haptics.novaConfirm()
                    onOpenDetail()
                },
            ),
    ) {
        NovaLibraryPosterArtwork(
            game = game,
            focused = focused,
            apiClient = apiClient,
            posterLoader = posterLoader,
            posterLoaderIdentity = posterLoaderIdentity,
            scale = scale,
            alpha = alpha,
            lift = lift,
            backgroundColor = surfaces.mediaPlaceholder,
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
    scale: Float,
    alpha: Float,
    lift: androidx.compose.ui.unit.Dp,
    backgroundColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(NovaPosterCornerRadius)
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
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
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
        fontSize = if (layoutMode == NovaLibraryLayoutMode.COMPACT) 11.sp else 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = if (layoutMode == NovaLibraryLayoutMode.COMPACT) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(
                start = NovaLibraryUiStateMapper.posterPresentationSpec(layoutMode).focusGutterDp.dp,
                top = 6.dp,
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
