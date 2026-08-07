package com.papi.nova.ui

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papi.nova.ui.compose.NovaRadius
import kotlinx.coroutines.Job
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt


@Composable
internal fun NovaLibraryLandscapeStageShell(
    modifier: Modifier = Modifier,
    reserveControllerHintSpace: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bottomPaddingDp = if (reserveControllerHintSpace) {
        NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape = true)
    } else {
        0
    }
    Column(
        modifier = modifier
            .padding(bottom = bottomPaddingDp.dp)
            .testTag("nova-stage-production-shell"),
        verticalArrangement = Arrangement.spacedBy(
            NovaLibraryUiStateMapper.landscapeContentSpacingDp().dp,
        ),
    ) {
        content()
    }
}

@Composable
internal fun NovaLibraryLandscapeToolbarContent(
    hostLabel: String,
    resultCount: Int,
    layoutLabel: String,
    polarisReady: Boolean,
    cinematic: Boolean = false,
    onOpenOptions: () -> Unit,
    onOpenSystemMenu: () -> Unit,
) {
    val surfaces = LocalNovaLibrarySurfaces.current
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val shape = RoundedCornerShape(if (cinematic) NovaRadius.row else NovaRadius.hero)
    val toolbarColor = if (cinematic) {
        surfaces.panel.copy(alpha = 0.34f * LocalNovaMenuOpacityScale.current)
    } else {
        surfaces.panel.copy(alpha = 0.72f * LocalNovaMenuOpacityScale.current)
    }
    val toolbarBorder = if (cinematic) {
        surfaces.tileBorder
    } else {
        surfaces.tileBorder
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NovaLibraryUiStateMapper.landscapeToolbarHeightDp(largeText).dp)
            .clip(shape)
            .background(toolbarColor)
            .border(1.dp, toolbarBorder, shape)
            .padding(horizontal = 10.dp, vertical = 5.5.dp)
            .testTag("nova-library-landscape-toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NovaLibraryToolbarIdentity(
            hostLabel = hostLabel,
            cinematic = cinematic,
            modifier = Modifier.widthIn(min = 132.dp, max = 240.dp),
            statusContent = {
                if (polarisReady) {
                    Text(
                        text = stringResource(R.string.nova_system_menu_status_polaris_ready),
                        color = LocalNovaComposeColors.current.textSecondary,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        )
        Spacer(modifier = Modifier.weight(1f))
        NovaLibraryResultAndLayoutMeta(
            resultCount = resultCount,
            layoutLabel = layoutLabel,
            cinematic = cinematic,
        )
        NovaLibraryToolbarOptionsAction(
            largeText = largeText,
            primary = !cinematic,
            onClick = onOpenOptions,
        )
        NovaLibraryToolbarSystemAction(
            onClick = onOpenSystemMenu,
        )
    }
}

@Composable
internal fun NovaLibraryPortraitToolbarContent(
    hostLabel: String,
    resultCount: Int,
    layoutLabel: String,
    polarisReady: Boolean,
    identityStatus: @Composable () -> Unit = {},
    onOpenOptions: () -> Unit,
    onOpenSystemMenu: () -> Unit,
) {
    val surfaces = LocalNovaLibrarySurfaces.current
    val largeText = LocalDensity.current.fontScale >= 1.5f
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (largeText) 74.dp else 60.dp)
            .testTag("nova-library-portrait-toolbar"),
    ) {
        val showMetadata = !largeText && maxWidth >= 400.dp
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(NovaRadius.hero))
                .background(surfaces.panel.copy(alpha = 0.72f * LocalNovaMenuOpacityScale.current))
                .border(1.dp, surfaces.tileBorder, RoundedCornerShape(NovaRadius.hero))
                .padding(horizontal = 10.dp, vertical = 5.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NovaLibraryToolbarIdentity(
                hostLabel = hostLabel,
                cinematic = false,
                modifier = Modifier.weight(1f),
                statusContent = identityStatus,
            )
            if (showMetadata) {
                NovaLibraryResultAndLayoutMeta(
                    resultCount = resultCount,
                    layoutLabel = layoutLabel,
                    cinematic = false,
                )
            }
            NovaLibraryToolbarOptionsAction(
                largeText = largeText,
                primary = true,
                onClick = onOpenOptions,
            )
            NovaLibraryToolbarSystemAction(
                onClick = onOpenSystemMenu,
            )
        }
    }
}

@Composable
private fun NovaLibraryToolbarIdentity(
    hostLabel: String,
    cinematic: Boolean,
    modifier: Modifier,
    statusContent: @Composable () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    Column(
        modifier = modifier.testTag("nova-library-toolbar-identity"),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The cinematic toolbar leads with the host: "Library" restates what the
            // whole screen already is, and the panel needs the weight back.
            if (!cinematic) {
                Text(
                    text = stringResource(R.string.nova_library_title),
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (cinematic) hostLabel else "· $hostLabel",
                color = colors.textSecondary,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        statusContent()
    }
}

@Composable
private fun NovaLibraryResultAndLayoutMeta(
    resultCount: Int,
    layoutLabel: String,
    cinematic: Boolean,
) {
    val colors = LocalNovaComposeColors.current
    Row(
        modifier = Modifier
            .widthIn(max = 132.dp)
            .testTag("nova-library-toolbar-meta"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.nova_library_results_format, resultCount),
            color = colors.textSecondary,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = layoutLabel,
            color = colors.accent,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NovaLibraryToolbarOptionsAction(
    largeText: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val optionsLabel = stringResource(R.string.nova_library_options_title)
    NovaActionButton(
        text = stringResource(R.string.nova_controller_hint_options),
        modifier = Modifier.testTag("nova-library-toolbar-options"),
        contentDescription = optionsLabel,
        onClick = onClick,
        primary = primary,
        minHeight = 48.dp,
        fontSize = 11.sp,
    )
}

@Composable
private fun NovaLibraryToolbarSystemAction(onClick: () -> Unit) {
    NovaActionButton(
        text = stringResource(R.string.nova_system_menu_title),
        modifier = Modifier.testTag("nova-library-toolbar-system-menu"),
        onClick = onClick,
        minHeight = 48.dp,
        fontSize = 10.sp,
    )
}

@Composable
internal fun NovaLibraryStage(
    games: List<PolarisGame>,
    focusedGame: PolarisGame?,
    restoreFocusGameId: String?,
    primaryActionLabel: String,
    sessionTitle: String? = null,
    sessionSupportingLine: String? = null,
    sessionActionLabel: String? = null,
    secondaryActionLabel: String? = null,
    apiClient: PolarisApiClient,
    showPosterTitles: Boolean,
    onPrimaryAction: () -> Unit,
    onSessionAction: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null,
    onGameFocused: (PolarisGame) -> Unit,
    onOpenDetail: (PolarisGame) -> Unit,
    artworkLoader: (ImageView, PolarisGame, String) -> Unit = { view, game, artworkKind ->
        apiClient.loadArtworkInto(view, game, artworkKind)
    },
    posterLoader: (ImageView, PolarisGame) -> Unit = { view, game ->
        apiClient.loadCoverInto(view, game)
    }
) {
    val selected = if (sessionTitle != null && focusedGame == null) {
        null
    } else {
        focusedGame ?: games.firstOrNull()
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag("nova-library-stage")) {
        val largeText = LocalDensity.current.fontScale >= 1.5f
        val verticalGrid = maxHeight > maxWidth
        val footerHeightDp = if (verticalGrid) {
            0
        } else {
            NovaLibraryUiStateMapper.stageControllerHintFooterHeightDp()
        }
        val spec = NovaLibraryUiStateMapper.stageLayoutSpecForViewport(
            widthDp = maxWidth.value.toInt(),
            heightDp = (maxHeight.value.toInt() - footerHeightDp).coerceAtLeast(0),
            largeText = largeText,
        )

        if (selected != null) {
            NovaLibraryStageHero(
                game = selected,
                heightDp = spec.stageHeroHeightDp,
                largeText = largeText,
                compact = spec.stageUsesCompactHero,
                primaryActionLabel = primaryActionLabel,
                sessionActionLabel = sessionActionLabel,
                secondaryActionLabel = secondaryActionLabel,
                artworkLoader = artworkLoader,
                onPrimaryAction = onPrimaryAction,
                onSessionAction = onSessionAction,
                onSecondaryAction = onSecondaryAction,
            )
        } else if (sessionTitle != null && sessionActionLabel != null && onSessionAction != null) {
            NovaLibraryStageSessionHero(
                title = sessionTitle,
                supportingLine = sessionSupportingLine,
                heightDp = spec.stageHeroHeightDp,
                largeText = largeText,
                compact = spec.stageUsesCompactHero,
                actionLabel = sessionActionLabel,
                secondaryActionLabel = secondaryActionLabel,
                onAction = onSessionAction,
                onSecondaryAction = onSecondaryAction,
            )
        }

        if (spec.stageUsesVerticalGrid) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(spec.stagePosterRailHeightDp.dp),
            ) {
                NovaLibraryStagePosterGrid(
                    games = games,
                    apiClient = apiClient,
                    columns = spec.stagePosterColumns,
                    heightDp = spec.stagePosterRailHeightDp,
                    restoreFocusGameId = restoreFocusGameId,
                    showPosterTitles = showPosterTitles,
                    posterLoader = posterLoader,
                    onGameFocused = onGameFocused,
                    onOpenDetail = onOpenDetail,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height((spec.stagePosterRailHeightDp + footerHeightDp).dp)
                    .padding(bottom = footerHeightDp.dp),
            ) {
                NovaLibraryStageRow(
                    games = games,
                    apiClient = apiClient,
                    isLandscape = true,
                    posterColumns = spec.stagePosterColumns,
                    restoreFocusGameId = restoreFocusGameId,
                    showPosterTitles = showPosterTitles,
                    onGameFocused = onGameFocused,
                    onOpenDetail = onOpenDetail,
                    coverLoader = posterLoader,
                )
            }
        }
    }
}

@Composable
private fun NovaLibraryStageSessionHero(
    title: String,
    supportingLine: String?,
    heightDp: Int,
    largeText: Boolean,
    compact: Boolean,
    actionLabel: String,
    secondaryActionLabel: String?,
    onAction: () -> Unit,
    onSecondaryAction: (() -> Unit)?,
) {
    val surfaces = LocalNovaLibrarySurfaces.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .background(surfaces.mediaPlaceholder)
            .testTag("nova-stage-session-only-hero"),
    ) {
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(if (compact) 8.dp else 14.dp),
        ) {
            Text(
                text = title,
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = when {
                    compact && largeText -> 18.sp
                    compact -> 20.sp
                    else -> 24.sp
                },
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("nova-stage-session-title"),
            )
            if (!compact && !supportingLine.isNullOrBlank()) {
                Text(
                    text = supportingLine,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NovaStageHeroAction(
                    label = actionLabel,
                    emphasized = true,
                    testTag = "nova-stage-session-action",
                    onClick = onAction,
                )
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    NovaStageHeroAction(
                        label = secondaryActionLabel,
                        emphasized = false,
                        testTag = "nova-stage-secondary-action",
                        onClick = onSecondaryAction,
                    )
                }
            }
        }
    }
}

/** Cards sit flat and evenly spaced; the focused card is distinguished by scale,
 *  opacity and lift rather than by crowding its neighbours. Poster width itself
 *  comes from [NovaLibraryUiStateMapper.stageRailPosterWidthDp]. */
private val NovaStageCarouselGapDp = 12.dp

/** How close (in card widths) the focused poster may come to a rail edge before the rail
 *  scrolls. Inside that band the rail holds still and the selection travels across
 *  stationary posters, which is what keeps a sense of place in a long library. */
private const val NovaStageEdgeScrollMarginCards = 1.15f

/**
 * Edge-scrolling policy for the poster rail.
 *
 * Compose already asks the scrollable to bring a newly focused child into view; the default
 * spec scrolls the minimum needed, which re-seats the selection against the viewport edge on
 * every step. Supplying the spec — rather than running a second scroller next to it — keeps a
 * single scroll authority and lets the rail stay put until the selection nears an edge.
 */
@OptIn(ExperimentalFoundationApi::class)
private object NovaStageEdgeScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val margin = (size * NovaStageEdgeScrollMarginCards)
            .coerceAtMost((containerSize - size) / 2f)
            .coerceAtLeast(0f)
        val trailingEdge = offset + size
        return when {
            offset < margin -> offset - margin
            trailingEdge > containerSize - margin -> trailingEdge - (containerSize - margin)
            else -> 0f
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovaLibraryStageHero(
    game: PolarisGame,
    heightDp: Int,
    largeText: Boolean,
    compact: Boolean,
    primaryActionLabel: String,
    sessionActionLabel: String? = null,
    secondaryActionLabel: String? = null,
    artworkLoader: (ImageView, PolarisGame, String) -> Unit,
    onPrimaryAction: () -> Unit,
    onSessionAction: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val heroColors = LocalNovaComposeColors.current
    val hasIcon = game.iconArtwork != null
    val iconKey = PolarisApiClient.artworkPresentationKey(game, PolarisGame.ARTWORK_KIND_ICON)
    // No scrim here: NovaLibraryCinematicBackdrop is the single owner of the stage
    // gradients, and stacking a second one over it crushed the hero artwork.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .testTag("nova-stage-hero"),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(if (compact) 4.dp else 16.dp)
                .testTag("nova-stage-identity"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (hasIcon) {
                    AndroidView(
                        factory = { context ->
                            ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                        },
                        update = { view ->
                            if (view.getTag(R.id.nova_artwork_presentation_key) != iconKey) {
                                view.setTag(R.id.nova_artwork_presentation_key, iconKey)
                                view.setImageDrawable(null)
                                artworkLoader(view, game, PolarisGame.ARTWORK_KIND_ICON)
                            }
                        },
                        modifier = Modifier
                            .size(if (compact) 32.dp else 40.dp)
                            .clip(RoundedCornerShape(NovaRadius.row))
                            .testTag("nova-stage-icon"),
                    )
                }
                Text(
                    text = game.name,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = when {
                        compact -> 20.sp
                        largeText -> 26.sp
                        else -> 24.sp
                    },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("nova-stage-title"),
                )
            }
            val heroMetadata = stageHeroMetadata(game)
            if (heroMetadata.isNotBlank() && !largeText) {
                Text(
                    text = heroMetadata,
                    color = heroColors.textSecondary,
                    fontSize = if (compact) 9.sp else 10.sp,
                    lineHeight = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.16.em,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = if (compact) 2.dp else 6.dp)
                        .testTag("nova-stage-metadata"),
                )
            }
            Row(
                modifier = Modifier.padding(top = if (compact) 4.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NovaStageHeroAction(
                    label = primaryActionLabel,
                    emphasized = true,
                    testTag = "nova-stage-primary-action",
                    onClick = onPrimaryAction,
                )
                if (sessionActionLabel != null && onSessionAction != null) {
                    NovaStageHeroAction(
                        label = sessionActionLabel,
                        emphasized = false,
                        testTag = "nova-stage-session-action",
                        onClick = onSessionAction,
                    )
                }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    NovaStageHeroAction(
                        label = secondaryActionLabel,
                        emphasized = false,
                        testTag = "nova-stage-secondary-action",
                        onClick = onSecondaryAction,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovaStageHeroAction(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val largeText = density.fontScale >= 1.5f
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val opacityScale = LocalNovaMenuOpacityScale.current
    val shape = RoundedCornerShape(NovaRadius.hero)
    val focusedScale = if (focused) 1.02f else 1f
    val baseColor = if (emphasized) colors.accent else surfaces.focusedArtworkScrim
    // The hero's primary action is not menu chrome. Folding the menu-opacity preference
    // (64% by default) into its fill composited the accent down against the backdrop until
    // the on-accent label sat at 2:1 against it, which is below the large-text floor.
    val surfaceAlpha = if (emphasized) {
        1f
    } else {
        (if (focused) 0.98f else 0.72f) * opacityScale
    }
    // Derive the label from the fill it actually lands on rather than trusting a fixed
    // on-accent colour: themes set accents of very different lightness.
    // 0.179 is the relative-luminance crossover where black and white text give equal
    // contrast against a fill; above it dark type wins, below it light type does.
    val emphasizedLabelColor = if (baseColor.luminance() > 0.179f) {
        Color(0xFF11121C)
    } else {
        Color.White
    }
    val visualFontSize = when {
        density.fontScale >= 1.9f -> 9.sp
        largeText -> 11.sp
        else -> 13.sp
    }
    val visualLineHeight = when {
        density.fontScale >= 1.9f -> 12.sp
        largeText -> 14.sp
        else -> 16.sp
    }

    // The glyph is dropped at large font scales so the label keeps the width it needs.
    val showGlyph = emphasized && !largeText
    Box(
        modifier = modifier
            .width(
                when {
                    largeText -> 140.dp
                    showGlyph -> 138.dp
                    else -> 116.dp
                },
            )
            .height(if (largeText) 42.dp else 40.dp)
            .onFocusChanged { focusState ->
                focused = focusState.isFocused || focusState.hasFocus
            }
            .combinedClickable(onClick = onClick, onLongClick = onClick)
            .focusable()
            .semantics { role = Role.Button; contentDescription = label }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(
                    when {
                        largeText -> 132.dp
                        showGlyph -> 130.dp
                        else -> 108.dp
                    },
                )
                .height(if (largeText) 34.dp else 28.dp)
                .graphicsLayer {
                    scaleX = focusedScale
                    scaleY = 1f
                }
                .clip(shape)
                .background(baseColor.copy(alpha = surfaceAlpha))
                .testTag("${testTag}-surface"),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showGlyph) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(emphasizedLabelColor.copy(alpha = 0.86f))
                            .testTag("${testTag}-glyph"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.nova_controller_hint_a),
                            color = baseColor,
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = label,
                    color = if (emphasized) emphasizedLabelColor else colors.textPrimary,
                    fontSize = visualFontSize,
                    lineHeight = visualLineHeight,
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("${testTag}-label"),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovaLibraryStagePosterGrid(
    games: List<PolarisGame>,
    apiClient: PolarisApiClient,
    columns: Int,
    heightDp: Int,
    restoreFocusGameId: String?,
    showPosterTitles: Boolean,
    posterLoader: (ImageView, PolarisGame) -> Unit,
    onGameFocused: (PolarisGame) -> Unit,
    onOpenDetail: (PolarisGame) -> Unit
) {
    val gameIds = remember(games) { games.map { it.id } }
    val initialIndex = remember(gameIds, restoreFocusGameId) {
        NovaLibraryUiStateMapper.stageRestoreIndex(gameIds, restoreFocusGameId)
    }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialIndex)
    val focusRequesters = remember(gameIds) { List(games.size) { FocusRequester() } }
    LaunchedEffect(gameIds, initialIndex) {
        if (games.isEmpty()) return@LaunchedEffect
        gridState.scrollToItem(initialIndex)
        repeat(STAGE_FOCUS_REQUEST_ATTEMPTS) {
            withFrameNanos { }
            val composed = gridState.layoutInfo.visibleItemsInfo.any { it.index == initialIndex }
            if (composed &&
                runCatching { focusRequesters[initialIndex].requestFocus() }.getOrDefault(false)
            ) {
                return@LaunchedEffect
            }
            delay(STAGE_FOCUS_RETRY_DELAY_MS)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxWidth().height(heightDp.dp).testTag("nova-stage-portrait-grid"),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        gridItemsIndexed(items = games, key = { _, game -> game.id }) { index, game ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("nova-stage-poster-${game.id}"),
            ) {
                NovaLibraryPosterCard(
                    game = game,
                    layoutMode = NovaLibraryLayoutMode.STAGE,
                    apiClient = apiClient,
                    showPosterTitle = showPosterTitles,
                    onOpenDetail = { onOpenDetail(game) },
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = focusRequesters[index],
                    onFocused = { onGameFocused(game) },
                    posterLoader = posterLoader,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NovaLibraryStageRow(
    games: List<PolarisGame>,
    apiClient: PolarisApiClient,
    isLandscape: Boolean,
    posterColumns: Int,
    restoreFocusGameId: String?,
    showPosterTitles: Boolean,
    onGameFocused: (PolarisGame) -> Unit,
    onOpenDetail: (PolarisGame) -> Unit,
    coverLoader: (ImageView, PolarisGame) -> Unit = { view, game ->
        apiClient.loadCoverInto(view, game)
    }
) {
    // The rail is a lazy list and the cinematic posters are ~10% of the viewport, so the
    // whole library scrolls here. It used to be capped to a handful of items back when a
    // focused card took a quarter of the screen and only a few could ever be reached.
    val effectiveGames = games
    val gameIds = remember(effectiveGames) { effectiveGames.map { it.id } }
    val initialIndex = remember(gameIds, restoreFocusGameId) {
        NovaLibraryUiStateMapper.stageRestoreIndex(gameIds, restoreFocusGameId)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val focusRequesters = remember(gameIds) { List(effectiveGames.size) { FocusRequester() } }
    val scope = rememberCoroutineScope()
    var focusedCardId by remember(gameIds) { mutableStateOf<String?>(null) }
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val inputModeManager = LocalInputModeManager.current

    // A FocusRequester bound to a lazy item that has not been composed yet silently does
    // nothing, so wait for the target to actually appear in the layout before asking. Without
    // this the rail never takes focus on a cold start and the first D-pad press walks the
    // toolbar instead of the library.
    LaunchedEffect(gameIds, initialIndex) {
        if (effectiveGames.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(initialIndex)
        repeat(STAGE_FOCUS_REQUEST_ATTEMPTS) {
            withFrameNanos { }
            val composed = listState.layoutInfo.visibleItemsInfo.any { it.index == initialIndex }
            if (composed) {
                // Compose refuses focus while the window is in touch mode, so a cold start
                // would otherwise leave the stage unfocused and hand the first D-pad press
                // to the toolbar. This surface is controller-first, so declare that intent.
                inputModeManager.requestInputMode(InputMode.Keyboard)
                val accepted = runCatching {
                    focusRequesters[initialIndex].requestFocus()
                }.getOrDefault(false)
                if (accepted) return@LaunchedEffect
            }
            delay(STAGE_FOCUS_RETRY_DELAY_MS)
        }
    }

    // Removed aggressive auto-snap: allows smooth free-form scrolling
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableWidthDp = maxWidth.value.toInt()
        val presentationSpec = NovaLibraryUiStateMapper.posterPresentationSpec(
            NovaLibraryLayoutMode.STAGE,
        )
        val railHeightDp = maxHeight.value.toInt()
        val captionBudgetDp = stagePosterCaptionBudgetDp(
            showPosterTitles = showPosterTitles,
            largeText = largeText,
        )
        val minimumRailHeightDp =
            NovaLibraryUiStateMapper.minimumPortraitPosterRailHeightDp(presentationSpec) + captionBudgetDp
        if (railHeightDp < minimumRailHeightDp) return@BoxWithConstraints
        val artworkRailHeightDp = (railHeightDp - captionBudgetDp).coerceAtLeast(0)
        val fitSize = NovaLibraryUiStateMapper.portraitPosterSizeForRail(
            artworkRailHeightDp,
            presentationSpec,
        )
        // Pin card width to a fraction of the viewport (GameNative-style) rather
        // than deriving it from leftover rail height, then clamp to what the rail
        // can actually show. Without this the cards collapse whenever the hero
        // takes vertical budget.
        val carouselTargetWidthDp =
            NovaLibraryUiStateMapper.stageRailPosterWidthDp(availableWidthDp)
        // The cinematic proportion decides the poster size. The rail-fit size is a
        // ceiling, not a floor: it only shrinks the card when the rail genuinely cannot
        // host the proportional size. Using it as a floor let cards inflate to fill
        // whatever rail height happened to be reserved, which silently overrode the
        // proportion this layout is supposed to hold.
        val widthFirstDp = carouselTargetWidthDp.coerceAtMost(fitSize.widthDp)
        val posterSize = NovaLibraryUiStateMapper.portraitPosterSizeForWidth(
            widthFirstDp.coerceAtLeast(2),
        )
        val artworkWidthDp = posterSize.widthDp
        val artworkHeightDp = posterSize.heightDp
        val cellWidthDp = artworkWidthDp + 2 * presentationSpec.focusGutterDp
        val cellHeightDp = artworkHeightDp + captionBudgetDp
        val verticalContentPaddingPerEdgeDp = NovaLibraryUiStateMapper.stageRailVerticalContentPaddingDp()
        val horizontalPaddingDp = NovaLibraryUiStateMapper.stageHorizontalContentPaddingDp(
            availableWidthDp = availableWidthDp,
            cardWidthDp = cellWidthDp,
        )

        CompositionLocalProvider(LocalBringIntoViewSpec provides NovaStageEdgeScrollSpec) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("nova-stage-landscape-rail"),
            contentPadding = PaddingValues(
                start = horizontalPaddingDp.dp,
                top = verticalContentPaddingPerEdgeDp.dp,
                end = horizontalPaddingDp.dp,
                bottom = verticalContentPaddingPerEdgeDp.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(NovaStageCarouselGapDp),
            verticalAlignment = Alignment.Bottom
        ) {
            itemsIndexed(
                items = effectiveGames,
                key = { _, game -> game.id },
                contentType = { _, _ -> "stage-game" }
            ) { index, game ->
                val isFocusedCard = focusedCardId == game.id
                Box(
                    modifier = Modifier
                        .width(cellWidthDp.dp)
                        .height(cellHeightDp.dp)
                        .zIndex(if (isFocusedCard) 1f else 0f)
                        .testTag("nova-stage-poster-${game.id}"),
                ) {
                    NovaLibraryPosterCard(
                        game = game,
                        layoutMode = NovaLibraryLayoutMode.STAGE,
                        apiClient = apiClient,
                        showPosterTitle = showPosterTitles,
                        onOpenDetail = {
                            onGameFocused(game)
                            onOpenDetail(game)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = focusRequesters[index],
                        onFocusChanged = { isFocused ->
                            focusedCardId = NovaLibraryUiStateMapper.stageFocusOwnerAfterChange(
                                currentOwnerId = focusedCardId,
                                gameId = game.id,
                                isFocused = isFocused,
                            )
                        },
                        onFocused = { onGameFocused(game) },
                        onNavigate = { delta ->
                            val nextIndex = NovaLibraryUiStateMapper.stageAdjacentIndex(
                                currentIndex = index,
                                delta = delta,
                                itemCount = effectiveGames.size,
                            )
                            if (nextIndex != index) {
                                scope.launch {
                                    repeat(STAGE_FOCUS_REQUEST_ATTEMPTS) {
                                        withFrameNanos { }
                                        if (runCatching {
                                            focusRequesters[nextIndex].requestFocus()
                                        }.getOrDefault(false)
                                        ) {
                                            return@launch
                                        }
                                        delay(STAGE_FOCUS_RETRY_DELAY_MS)
                                    }
                                }
                            }
                            true
                        },
                        posterLoader = coverLoader,
                    )
                }
            }
        }
        }
    }
}

private fun stagePosterCaptionBudgetDp(showPosterTitles: Boolean, largeText: Boolean): Int = when {
    !showPosterTitles -> 0
    largeText -> STAGE_LARGE_TEXT_POSTER_CAPTION_BUDGET_DP
    else -> STAGE_POSTER_CAPTION_BUDGET_DP
}

private const val STAGE_POSTER_CAPTION_BUDGET_DP = 36
private const val STAGE_LARGE_TEXT_POSTER_CAPTION_BUDGET_DP = 64
private const val STAGE_FOCUS_REQUEST_ATTEMPTS = 24
private const val STAGE_FOCUS_RETRY_DELAY_MS = 32L

/**
 * Supporting line under the hero title: where the game came from, what it is, and the
 * capabilities worth knowing before launching. Uppercased and letterspaced so it reads as
 * a caption against the title rather than competing with it.
 */
@Composable
private fun stageHeroMetadata(game: PolarisGame): String {
    val hdrLabel = stringResource(R.string.badge_hdr)
    val recentLabel = stringResource(R.string.nova_library_filter_recent)
    return remember(
        game.id,
        game.sourceLabel,
        game.categoryLabel,
        game.hdrSupported,
        game.lastLaunched,
        hdrLabel,
        recentLabel,
    ) {
        buildList {
            add(game.sourceLabel)
            add(game.categoryLabel)
            if (game.hdrSupported) add(hdrLabel)
            if (game.lastLaunched > 0L) add(recentLabel)
        }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ") { it.uppercase(Locale.US) }
    }
}

internal fun stageDisplayTitle(title: String, largeText: Boolean): String {
    if (!largeText || title.length <= 20 || '\n' in title) return title
    val midpoint = title.length / 2
    val breakIndex = title.indices
        .filter { index -> title[index] == ' ' }
        .minByOrNull { index -> kotlin.math.abs(index - midpoint) }
        ?: return title
    return title.substring(0, breakIndex) + "\n" + title.substring(breakIndex + 1)
}