package com.papi.nova.ui

import android.graphics.Color
import android.widget.ImageView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaFocusMotionSpec
import com.papi.nova.ui.compose.novaFocusMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NovaLibrarySpotlightRow(
    games: List<PolarisGame>,
    apiClient: PolarisApiClient,
    isLandscape: Boolean,
    restoreFocusGameId: String?,
    showPosterTitles: Boolean,
    onGameFocused: (PolarisGame) -> Unit,
    onOpenDetail: (PolarisGame) -> Unit,
    coverLoader: (ImageView, PolarisGame) -> Unit = { view, game ->
        apiClient.loadCoverInto(view, game)
    }
) {
    val gameIds = remember(games) { games.map { it.id } }
    val initialIndex = remember(gameIds, restoreFocusGameId) {
        NovaLibraryUiStateMapper.spotlightRestoreIndex(gameIds, restoreFocusGameId)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val focusRequesters = remember(gameIds) { List(games.size) { FocusRequester() } }
    val scope = rememberCoroutineScope()
    val largeText = LocalDensity.current.fontScale >= 1.5f

    LaunchedEffect(gameIds, initialIndex) {
        if (games.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(initialIndex)
        repeat(SPOTLIGHT_FOCUS_REQUEST_ATTEMPTS) {
            withFrameNanos { }
            val accepted = runCatching {
                focusRequesters[initialIndex].requestFocus()
            }.getOrDefault(false)
            if (accepted) return@LaunchedEffect
            delay(SPOTLIGHT_FOCUS_RETRY_DELAY_MS)
        }
    }

    LaunchedEffect(gameIds, listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .drop(1)
            .filter { scrolling -> !scrolling }
            .collect {
                games.getOrNull(listState.firstVisibleItemIndex)?.let(onGameFocused)
            }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableWidthDp = maxWidth.value.toInt()
        val cardWidthDp = NovaLibraryUiStateMapper.spotlightCardWidthDp(
            availableWidthDp = availableWidthDp,
            isLandscape = isLandscape,
            largeText = largeText
        )
        val desiredCardHeightDp = NovaLibraryUiStateMapper.spotlightCardHeightDp(
            cardWidthDp = cardWidthDp,
            isLandscape = isLandscape,
            largeText = largeText
        )
        val cardHeightDp = NovaLibraryUiStateMapper.spotlightConstrainedCardHeightDp(
            desiredHeightDp = desiredCardHeightDp,
            availableHeightDp = maxHeight.value.toInt()
        )
        val horizontalPaddingDp = NovaLibraryUiStateMapper.spotlightHorizontalContentPaddingDp(
            availableWidthDp = availableWidthDp,
            cardWidthDp = cardWidthDp
        )

        LazyRow(
            state = listState,
            flingBehavior = snapFlingBehavior,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPaddingDp.dp,
                end = horizontalPaddingDp.dp,
                top = 12.dp,
                bottom = 14.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(
                items = games,
                key = { _, game -> game.id },
                contentType = { _, _ -> "spotlight-game" }
            ) { index, game ->
                NovaLibrarySpotlightCard(
                    game = game,
                    cardWidthDp = cardWidthDp,
                    cardHeightDp = cardHeightDp,
                    largeText = largeText,
                    showPosterTitles = showPosterTitles,
                    focusRequester = focusRequesters[index],
                    onFocused = {
                        onGameFocused(game)
                        scope.launch { listState.animateScrollToItem(index) }
                    },
                    onNavigate = { delta ->
                        val nextIndex = NovaLibraryUiStateMapper.spotlightAdjacentIndex(
                            currentIndex = index,
                            delta = delta,
                            itemCount = games.size
                        )
                        if (nextIndex != index) {
                            scope.launch {
                                listState.animateScrollToItem(nextIndex)
                                withFrameNanos { }
                                runCatching { focusRequesters[nextIndex].requestFocus() }
                            }
                        }
                        true
                    },
                    coverLoader = coverLoader,
                    onOpenDetail = {
                        onGameFocused(game)
                        scope.launch { listState.animateScrollToItem(index) }
                        onOpenDetail(game)
                    }
                )
            }
        }
    }
}

@Composable
private fun NovaLibrarySpotlightCard(
    game: PolarisGame,
    cardWidthDp: Int,
    cardHeightDp: Int,
    largeText: Boolean,
    showPosterTitles: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onNavigate: (Int) -> Boolean,
    coverLoader: (ImageView, PolarisGame) -> Unit,
    onOpenDetail: () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(22.dp)
    val title = game.name.ifBlank { stringResource(R.string.nova_library_unknown_game) }
    val displayTitle = spotlightDisplayTitle(title, largeText)
    val detailsLabel = stringResource(R.string.nova_library_card_action_details)
    val recentLabel = stringResource(R.string.nova_library_filter_recent)
    val source = spotlightLabel(game.source)
    val category = spotlightLabel(game.category)
    val metadata = buildList {
        add(source)
        add(category)
    }.filter { it.isNotBlank() }.distinct().joinToString(" · ")
    var focused by remember(game.id) { mutableStateOf(false) }
    val cardAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0.68f,
        animationSpec = tween(durationMillis = 150),
        label = "NovaSpotlightCardAlpha"
    )
    val accessibilityLabel = buildString {
        append(title)
        if (metadata.isNotBlank()) append(". ").append(metadata)
        if (game.hdrSupported) append(". HDR")
        if (game.lastLaunched > 0) append(". ").append(recentLabel)
        append(". ").append(detailsLabel)
    }

    Box(
        modifier = Modifier
            .width(cardWidthDp.dp)
            .height(cardHeightDp.dp)
            .alpha(cardAlpha)
            .novaFocusMotion(
                focused = focused,
                focusedScale = NovaFocusMotionSpec.CardFocusedScale
            )
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> onNavigate(-1)
                    Key.DirectionRight -> onNavigate(1)
                    else -> false
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = accessibilityLabel
            }
            .combinedClickable(
                role = Role.Button,
                onClick = onOpenDetail,
                onLongClick = onOpenDetail
            )
            .clip(shape)
            .background(if (focused) colors.accent else surfaces.tileBorder)
            .padding(if (focused) 3.dp else 1.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(surfaces.mediaPlaceholder)
        ) {
            Text(
                text = title.take(1).uppercase(Locale.US),
                color = colors.accent.copy(alpha = 0.40f),
                fontSize = if (largeText) 72.sp else 58.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.TRANSPARENT)
                    isFocusable = false
                    isClickable = false
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            },
            update = { view ->
                view.scaleType = ImageView.ScaleType.CENTER_CROP
                view.setBackgroundColor(Color.TRANSPARENT)
                view.alpha = 1f
                view.contentDescription = null
                coverLoader(view, game)
            },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(if (largeText) 8.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (largeText) 4.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (game.hdrSupported) {
                NovaSpotlightPill(text = "HDR", emphasized = focused)
            }
            if (game.lastLaunched > 0) {
                NovaSpotlightPill(text = recentLabel, emphasized = false)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (largeText) 216.dp else 128.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to surfaces.mediaScrimBottom.copy(alpha = 0f),
                        0.30f to surfaces.mediaScrimBottom.copy(alpha = 0.66f),
                        1f to surfaces.mediaScrimBottom.copy(alpha = 0.98f)
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    horizontal = if (largeText) 14.dp else 18.dp,
                    vertical = if (largeText) 4.dp else 14.dp
                ),
            verticalArrangement = Arrangement.spacedBy(if (largeText) 2.dp else 3.dp)
        ) {
            if (showPosterTitles || focused) {
                Text(
                    text = displayTitle,
                    color = surfaces.onMedia,
                    fontSize = if (focused) 21.sp else 17.sp,
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
                    minLines = if (largeText) 2 else 1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    color = surfaces.onMediaSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (largeText) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

    }
}

@Composable
private fun NovaSpotlightPill(text: String, emphasized: Boolean) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(999.dp)
    Text(
        text = text,
        color = if (emphasized) colors.onAccent else surfaces.onMedia,
        fontSize = 10.sp,
        lineHeight = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(
                if (emphasized) colors.accent.copy(alpha = 0.94f)
                else surfaces.mediaScrimBottom.copy(alpha = 0.64f)
            )
            .border(
                BorderStroke(
                    1.dp,
                    if (emphasized) colors.accent else surfaces.onMedia.copy(alpha = 0.24f)
                ),
                shape
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

internal fun spotlightDisplayTitle(title: String, largeText: Boolean): String {
    if (!largeText || title.length <= 20 || '\n' in title) return title
    val midpoint = title.length / 2
    val breakIndex = title.indices
        .filter { index -> title[index] == ' ' }
        .minByOrNull { index -> kotlin.math.abs(index - midpoint) }
        ?: return title
    return title.substring(0, breakIndex) + "\n" + title.substring(breakIndex + 1)
}

private fun spotlightLabel(value: String?): String {
    return value
        ?.substringAfterLast('/')
        ?.substringAfterLast(':')
        ?.replace('_', ' ')
        ?.replace('-', ' ')
        ?.trim()
        ?.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
        }
        .orEmpty()
}

private const val SPOTLIGHT_FOCUS_REQUEST_ATTEMPTS = 6
private const val SPOTLIGHT_FOCUS_RETRY_DELAY_MS = 32L
