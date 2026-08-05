package com.papi.nova.ui

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaControllerHintBar

/** Where the detail window currently is. Back unwinds one level before leaving. */
internal enum class NovaGameDetailDestination { OVERVIEW, LAUNCH_MODE, TUNE, ARTWORK }

/** Content insets shared by the Overview and the destinations that sit beside it. */
internal val NovaGameDetailInset = 28.dp
internal val NovaGameDetailFloor = 58.dp

/** Every focusable control clears the accessible target floor. */
internal val NovaGameDetailActionHeight = 48.dp

/** Matches the library's surface radius; the sharp edge was a deliberate choice there. */
internal val NovaGameDetailCornerRadius = 8.dp

/**
 * The landing screen of the detail window.
 *
 * The artwork is the subject and nothing sits on it in a card: the content block rests on
 * the scrim floor in one reading order — who the game is, a hairline, what pressing the
 * primary action will do, then what you can do. Layout is complete before the artwork
 * arrives, so a slow or failed load changes the backdrop and moves nothing.
 */
@Composable
internal fun NovaGameDetailOverview(
    uiState: NovaGameDetailUiState,
    apiClient: PolarisApiClient,
    playLabel: String,
    lastPlayedText: String?,
    sourceLabel: String,
    optimizationState: NovaGameDetailOptimizationState,
    reviewExpanded: Boolean,
    showLaunchModeAction: Boolean,
    logoAvailable: Boolean,
    logoPresentationKey: String,
    logoLoader: (ImageView) -> Unit,
    logoContentDescription: String,
    playFocusRequester: FocusRequester,
    onPrimaryLaunch: () -> Unit,
    onRetryHighFps: () -> Unit,
    onResetProfile: () -> Unit,
    onDestination: (NovaGameDetailDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val game = uiState.game

    Box(modifier = modifier.fillMaxSize().testTag("nova-game-detail-overview")) {
        NovaLibraryCinematicBackdrop(game = game, apiClient = apiClient, strength = 1f)

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeContent)
                .padding(start = NovaGameDetailInset, end = NovaGameDetailInset, bottom = 10.dp),
        ) {
            NovaGameDetailTitle(
                game = game,
                logoAvailable = logoAvailable,
                logoPresentationKey = logoPresentationKey,
                logoLoader = logoLoader,
                logoContentDescription = logoContentDescription,
            )

            Text(
                text = novaGameDetailIdentityLine(sourceLabel, lastPlayedText, game),
                color = colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.17.em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 11.dp),
            )

            // The hairline divides names from numbers: identity above, machine state below.
            Box(
                modifier = Modifier
                    .padding(top = 11.dp)
                    .width(330.dp)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to colors.accent,
                                0.30f to colors.accent.copy(alpha = 0.44f),
                                0.62f to colors.textMuted.copy(alpha = 0.16f),
                                1.0f to Color.Transparent,
                            ),
                        ),
                    ),
            )

            NovaGameDetailStatusLine(
                uiState = uiState,
                optimizationState = optimizationState,
                modifier = Modifier.padding(top = 11.dp),
            )

            if (reviewExpanded) {
                LaunchProfileReviewNotice(
                    optimizationState = optimizationState,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            NovaGameDetailActions(
                uiState = uiState,
                optimizationState = optimizationState,
                playLabel = playLabel,
                reviewExpanded = reviewExpanded,
                showLaunchModeAction = showLaunchModeAction,
                playFocusRequester = playFocusRequester,
                onPrimaryLaunch = onPrimaryLaunch,
                onRetryHighFps = onRetryHighFps,
                onResetProfile = onResetProfile,
                onDestination = onDestination,
                modifier = Modifier.padding(top = 16.dp),
            )

            NovaControllerHintBar(
                hints = novaGameDetailOverviewHints(),
                compact = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }

    }
}

/**
 * Curated logo artwork replaces the title outright when it is ready at first composition.
 * A logo that arrives later is ignored: swapping a settled title for one is a visible jump,
 * and the title is never wrong.
 */
@Composable
private fun NovaGameDetailTitle(
    game: PolarisGame,
    logoAvailable: Boolean,
    logoPresentationKey: String,
    logoLoader: (ImageView) -> Unit,
    logoContentDescription: String,
) {
    if (logoAvailable) {
        key(logoPresentationKey) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_START
                        contentDescription = logoContentDescription
                        logoLoader(this)
                    }
                },
                modifier = Modifier
                    .sizeIn(maxWidth = 200.dp, maxHeight = 64.dp)
                    .semantics { contentDescription = logoContentDescription }
                    .testTag("nova-game-detail-logo"),
            )
        }
    } else {
        Text(
            text = game.name,
            color = LocalNovaComposeColors.current.textPrimary,
            fontSize = 38.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.03).em,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("nova-game-detail-title"),
        )
    }
}

/**
 * What pressing the primary action will do, read as an instrument line rather than a chip:
 * an indicator lamp, then mode and profile set with tabular figures.
 */
@Composable
private fun NovaGameDetailStatusLine(
    uiState: NovaGameDetailUiState,
    optimizationState: NovaGameDetailOptimizationState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val summary = optimizationState.profileSummary
    val limited = optimizationState.reviewRequired ||
        summary?.noticeTone == NovaLaunchProfileNoticeTone.WARNING
    val lamp = when {
        limited -> colors.warning
        summary == null -> colors.textMuted
        else -> colors.accent
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = modifier.testTag("nova-game-detail-status"),
    ) {
        Box(modifier = Modifier.size(7.dp).clip(RoundedCornerShape(percent = 50)).background(lamp))
        Text(
            text = novaGameDetailStatusText(uiState, summary),
            color = colors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.11.em,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The action lane. Play holds first focus; the rail is gated so it never grows a node that
 * leads nowhere. While a review is expanded the lane becomes the review's own choices —
 * the three buttons of the alert this replaces.
 */
@Composable
private fun NovaGameDetailActions(
    uiState: NovaGameDetailUiState,
    optimizationState: NovaGameDetailOptimizationState,
    playLabel: String,
    reviewExpanded: Boolean,
    showLaunchModeAction: Boolean,
    playFocusRequester: FocusRequester,
    onPrimaryLaunch: () -> Unit,
    onRetryHighFps: () -> Unit,
    onResetProfile: () -> Unit,
    onDestination: (NovaGameDetailDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        NovaActionButton(
            text = playLabel,
            onClick = onPrimaryLaunch,
            enabled = uiState.playEnabled,
            primary = true,
            minHeight = NovaGameDetailActionHeight,
            cornerRadius = NovaGameDetailCornerRadius,
            fontSize = 15.sp,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            modifier = Modifier
                .focusRequester(playFocusRequester)
                .testTag("nova-game-detail-primary"),
        )

        if (reviewExpanded) {
            if (optimizationState.profileSummary?.showRetryHighFps == true) {
                NovaGameDetailSecondaryAction(stringResource(R.string.nova_library_retry_high_fps), onRetryHighFps)
            }
            NovaGameDetailSecondaryAction(stringResource(R.string.nova_library_reset_game_profile), onResetProfile)
        } else {
            if (showLaunchModeAction) {
                NovaGameDetailSecondaryAction(stringResource(R.string.nova_library_launch_mode_title)) {
                    onDestination(NovaGameDetailDestination.LAUNCH_MODE)
                }
            }
            NovaGameDetailSecondaryAction(stringResource(R.string.nova_library_launch_options_secondary)) {
                onDestination(NovaGameDetailDestination.TUNE)
            }
            NovaGameDetailSecondaryAction(stringResource(R.string.nova_artwork_studio_title)) {
                onDestination(NovaGameDetailDestination.ARTWORK)
            }
        }
    }
}

@Composable
private fun NovaGameDetailSecondaryAction(text: String, onClick: () -> Unit) {
    val surfaces = LocalNovaLibrarySurfaces.current
    NovaActionButton(
        text = text,
        onClick = onClick,
        minHeight = NovaGameDetailActionHeight,
        cornerRadius = NovaGameDetailCornerRadius,
        fontSize = 13.sp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(NovaGameDetailCornerRadius))
            .background(surfaces.control.copy(alpha = 1f)),
    )
}

/**
 * The alert this replaces stated the reason and offered launch, retry and reset. Expanded
 * in place it keeps all four, without a dialog landing on the artwork.
 */
@Composable
private fun LaunchProfileReviewNotice(
    optimizationState: NovaGameDetailOptimizationState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val summary = optimizationState.profileSummary
    val detail = listOf(
        summary?.noticeDetail,
        summary?.noticeRecommendation,
        summary?.reasonLine,
    ).firstOrNull { !it.isNullOrBlank() }
        ?: stringResource(
            R.string.nova_library_preflight_review_message,
            optimizationState.reviewReason.ifBlank { "fps_override" },
        )

    Column(
        modifier = modifier
            .sizeIn(maxWidth = 520.dp)
            .clip(RoundedCornerShape(NovaGameDetailCornerRadius))
            .background(colors.warning.copy(alpha = 0.13f))
            .border(1.dp, colors.warning.copy(alpha = 0.46f), RoundedCornerShape(NovaGameDetailCornerRadius))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("nova-game-detail-review"),
    ) {
        Text(
            text = stringResource(R.string.nova_library_preflight_review_title),
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = detail,
            color = colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** Source, when it was last played, and its primary genre — who the game is. */
private fun novaGameDetailIdentityLine(
    sourceLabel: String,
    lastPlayedText: String?,
    game: PolarisGame,
): String = listOf(sourceLabel, lastPlayedText, game.genres.firstOrNull())
    .filter { !it.isNullOrBlank() }
    .joinToString("  ·  ")

/** Mode, profile and freshness — what the primary action will do. */
private fun novaGameDetailStatusText(
    uiState: NovaGameDetailUiState,
    summary: NovaLaunchProfileSummary?,
): String {
    return listOf(
        uiState.hostStreamDisplayModeLabel.takeIf { uiState.playUsesVirtualDisplay },
        summary?.selectedLine,
        summary?.limitingLine?.takeIf { it.isNotBlank() } ?: summary?.freshnessLine,
    ).filter { !it.isNullOrBlank() }.joinToString("  ·  ")
}

/**
 * B unwinds a level, X reaches Tune. A is not repeated here: the primary action already
 * carries it, and this window exists to remove duplication.
 */
@Composable
private fun novaGameDetailOverviewHints(): List<NovaControllerHint> = listOf(
    NovaControllerHint(
        key = stringResource(R.string.nova_controller_hint_b),
        label = stringResource(R.string.nova_controller_hint_close),
    ),
)
