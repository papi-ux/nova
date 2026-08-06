package com.papi.nova.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaControllerHintBar
import com.papi.nova.ui.compose.NovaFocusableCard

/** The three ways a launch can go when Polaris reports desktop Steam active. */
internal enum class NovaSteamLaunchChoice {
    PRIVATE_STREAM,
    MIRROR_DESKTOP,
    CLOSE_STEAM_THEN_PRIVATE,
}

/**
 * A drill-in that sits beside the game rather than on top of it. The header is pinned and
 * the body scrolls, so focus drives the scroll rather than the reverse.
 */
@Composable
internal fun NovaGameDetailPanel(
    eyebrow: String,
    headline: String,
    readout: String,
    scrollState: ScrollState,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.window.copy(alpha = NOVA_DETAIL_SCRIM_ALPHA))
            // The dimmed area beside the panel is the game you came from, so tapping it
            // is the same gesture as pressing back.
            .novaDismissOnTap(onDismiss)
            .testTag("nova-game-detail-scrim"),
    ) {
        // The panel exists so the game stays visible beside what you are changing. In
        // portrait there is nothing to sit beside, so it takes the window instead of
        // squeezing a phone-width column inside a phone.
        val widthFraction = if (maxHeight > maxWidth) 1f else NOVA_DETAIL_PANEL_WIDTH_FRACTION
        val shortViewport = maxHeight < NOVA_DETAIL_SHORT_VIEWPORT
        val bodyWidth = maxWidth * widthFraction - NovaGameDetailInset * 2
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(widthFraction)
                // Translucent, so the game reads underneath and the panel is a layer over
                // it rather than another screen. Separation comes from the outside scrim.
                .background(colors.window.copy(alpha = NOVA_DETAIL_PANEL_ALPHA))
                .background(surfaces.panel)
                // Taps inside the panel are not taps outside it.
                .novaDismissOnTap {}
                .windowInsetsPadding(WindowInsets.safeContent)
                .padding(horizontal = NovaGameDetailInset, vertical = if (shortViewport) 10.dp else 20.dp)
                .testTag("nova-game-detail-panel"),
        ) {
            NovaGameDetailDestinationHeader(
                eyebrow = eyebrow,
                headline = headline,
                readout = readout,
                compact = shortViewport,
                onDismiss = onDismiss,
            )
            CompositionLocalProvider(
                LocalNovaDetailWideBody provides (bodyWidth >= NOVA_DETAIL_TWO_COLUMN_MIN),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    content = { content() },
                )
            }
            NovaGameDetailDestinationHints()
        }
    }
}

/** A tap target that swallows the gesture, with no ripple to imply a button. */
private fun Modifier.novaDismissOnTap(onDismiss: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onDismiss,
    )
}

/**
 * True when the destination body has room for two readable columns. Provided by the
 * panel because only the panel knows how much of the window it took.
 */
internal val LocalNovaDetailWideBody = staticCompositionLocalOf { false }

/**
 * Two columns when there is width for them, stacked when there is not. Wide, this stops
 * a 500dp body running one narrow column with everything else below the fold.
 */
@Composable
internal fun NovaGameDetailColumns(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    if (LocalNovaDetailWideBody.current) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f), content = { left() })
            Column(modifier = Modifier.weight(1f), content = { right() })
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            left()
            right()
        }
    }
}

/**
 * A drill-in that needs the window. Used by Artwork, whose studio lays itself out as a
 * Row of weighted Columns and cannot fold into a panel.
 */
@Composable
internal fun NovaGameDetailFullScreen(
    eyebrow: String,
    headline: String,
    scrollState: ScrollState,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val shortViewport = maxHeight < NOVA_DETAIL_SHORT_VIEWPORT
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Solid: there is no outside here, so translucency would only print the
                // Overview through the studio rather than reveal anything new.
                .background(colors.window)
                .background(surfaces.panel)
                .windowInsetsPadding(WindowInsets.safeContent)
                .padding(
                    horizontal = NovaGameDetailInset,
                    vertical = if (shortViewport) 10.dp else 20.dp,
                )
                .testTag("nova-game-detail-fullscreen"),
        ) {
            NovaGameDetailDestinationHeader(
                eyebrow = eyebrow,
                headline = headline,
                readout = "",
                compact = shortViewport,
                onDismiss = onDismiss,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                content = { content() },
            )
            NovaGameDetailDestinationHints()
        }
    }
}

/** Every destination says how to act and how to get back. */
@Composable
private fun NovaGameDetailDestinationHints() {
    NovaControllerHintBar(
        hints = listOf(
            NovaControllerHint(
                key = stringResource(R.string.nova_controller_hint_a),
                label = stringResource(R.string.nova_controller_hint_select),
            ),
            NovaControllerHint(
                key = stringResource(R.string.nova_controller_hint_b),
                label = stringResource(R.string.nova_controller_hint_back),
            ),
        ),
        compact = true,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )
}

@Composable
private fun NovaGameDetailDestinationHeader(
    eyebrow: String,
    headline: String,
    readout: String,
    compact: Boolean = false,
    onDismiss: () -> Unit = {},
) {
    val colors = LocalNovaComposeColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = if (compact) 6.dp else 14.dp),
    ) {
    Column(modifier = Modifier.weight(1f)) {
        if (!compact) {
            Text(
                text = eyebrow,
                color = colors.textMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.22.em,
            )
        }
        Text(
            text = if (compact) "$eyebrow · $headline" else headline,
            color = colors.textPrimary,
            fontSize = if (compact) 15.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (compact) 0.dp else 3.dp),
        )
        if (readout.isNotBlank() && !compact) {
            Text(
                text = readout,
                color = colors.textSecondary,
                fontSize = 11.sp,
                letterSpacing = 0.10.em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
        // Portrait and the studio have no outside to tap, so the way out is always here.
        NovaGameDetailCloseControl(onDismiss)
    }
}

/** The touch equivalent of back, for the destinations that fill the window. */
@Composable
private fun NovaGameDetailCloseControl(onDismiss: () -> Unit) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = 12.dp)
            .clip(RoundedCornerShape(NovaGameDetailCornerRadius))
            .background(surfaces.control)
            .border(1.dp, colors.divider.copy(alpha = 0.6f), RoundedCornerShape(NovaGameDetailCornerRadius))
            .novaDismissOnTap(onDismiss)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("nova-game-detail-close"),
    ) {
        Text(
            text = stringResource(R.string.nova_game_detail_close),
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Divides what you read from what you do. The sheet presented both as one list, so a
 * readout like "MangoHUD: On" sat in the same shape as "Reset profile" — one is a
 * statement, the other has consequences.
 */
@Composable
internal fun NovaGameDetailGroupLabel(text: String) {
    val colors = LocalNovaComposeColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp),
    ) {
        Text(
            text = text.uppercase(),
            color = colors.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.22.em,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.divider.copy(alpha = 0.5f)),
        )
    }
}

/** Retry and reset: the two things in Tune that change something rather than report it. */
@Composable
internal fun LaunchProfileSummaryActions(
    summary: NovaLaunchProfileSummary?,
    resetProfileLabel: String,
    resetProfileWorking: Boolean,
    onRetryHighFps: () -> Unit,
    onResetProfile: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (summary?.showRetryHighFps == true) {
            NovaSteamChoiceRow(
                label = summary.retryHighFpsLabel.ifBlank {
                    stringResource(R.string.nova_library_retry_high_fps)
                },
                caption = "",
                enabled = true,
                onClick = onRetryHighFps,
            )
        }
        NovaSteamChoiceRow(
            label = resetProfileLabel,
            caption = "",
            enabled = !resetProfileWorking,
            onClick = onResetProfile,
        )
    }
}

/**
 * The desktop-Steam choice, as rows in Launch mode rather than a sheet over the artwork.
 * A blocked option stays visible and inert: hiding it loses the reason it is blocked,
 * which is the part worth reading.
 */
@Composable
internal fun NovaDesktopSteamLaunchDecisionRows(
    decision: NovaDesktopSteamLaunchDecision,
    onChoice: (NovaSteamLaunchChoice) -> Unit,
) {
    val colors = LocalNovaComposeColors.current

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("nova-game-detail-steam-decision"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NovaGameDetailCornerRadius))
                .background(colors.warning.copy(alpha = 0.13f))
                .border(
                    1.dp,
                    colors.warning.copy(alpha = 0.46f),
                    RoundedCornerShape(NovaGameDetailCornerRadius),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.nova_desktop_steam_title),
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = decision.reason.ifBlank {
                    stringResource(R.string.nova_desktop_steam_message)
                },
                color = colors.textSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        NovaSteamChoiceRow(
            label = stringResource(R.string.nova_desktop_steam_private_stream),
            caption = decision.privateStreamUnavailableReason,
            enabled = decision.privateStreamEnabled,
            onClick = { onChoice(NovaSteamLaunchChoice.PRIVATE_STREAM) },
        )
        if (decision.forcePrivateAfterSteamCloseEnabled) {
            NovaSteamChoiceRow(
                label = decision.forcePrivateAfterSteamCloseLabel.ifBlank {
                    stringResource(R.string.nova_desktop_steam_force_private)
                },
                caption = stringResource(R.string.nova_desktop_steam_force_private_caption),
                enabled = true,
                onClick = { onChoice(NovaSteamLaunchChoice.CLOSE_STEAM_THEN_PRIVATE) },
            )
        }
        NovaSteamChoiceRow(
            label = stringResource(R.string.nova_desktop_steam_mirror_desktop),
            caption = stringResource(R.string.nova_desktop_steam_mirror_caption),
            enabled = decision.mirrorDesktopEnabled,
            onClick = { onChoice(NovaSteamLaunchChoice.MIRROR_DESKTOP) },
        )
    }
}

@Composable
private fun NovaSteamChoiceRow(
    label: String,
    caption: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    NovaFocusableCard(
        onClick = onClick,
        enabled = enabled,
        contentDescription = label,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = label,
                color = if (enabled) colors.textPrimary else colors.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/**
 * Wide enough for the insight cards, which carry a single-line profile badge that
 * truncated at 53%, and still narrow enough to keep the game present beside it.
 */
private const val NOVA_DETAIL_PANEL_WIDTH_FRACTION = 0.60f

/** Enough of the game stays visible for the panel to read as a layer over it. */
private const val NOVA_DETAIL_SCRIM_ALPHA = 0.72f

/** Translucent enough to show artwork, opaque enough to keep body text legible. */
private const val NOVA_DETAIL_PANEL_ALPHA = 0.80f

/** Two columns need this much body width before either becomes too narrow to read. */
private val NOVA_DETAIL_TWO_COLUMN_MIN = 440.dp

/** Below this a phone in landscape has no height to spare for chrome. */
private val NOVA_DETAIL_SHORT_VIEWPORT = 500.dp
