package com.papi.nova.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    content: @Composable () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current

    Box(modifier = Modifier.fillMaxSize().background(colors.window.copy(alpha = 0.58f))) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(NOVA_DETAIL_PANEL_WIDTH_FRACTION)
                .background(colors.window)
                .background(surfaces.panel)
                .windowInsetsPadding(WindowInsets.safeContent)
                .padding(horizontal = NovaGameDetailInset, vertical = 20.dp)
                .testTag("nova-game-detail-panel"),
        ) {
            NovaGameDetailDestinationHeader(eyebrow, headline, readout)
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

/**
 * A drill-in that needs the window. Used by Artwork, whose studio lays itself out as a
 * Row of weighted Columns and cannot fold into a panel.
 */
@Composable
internal fun NovaGameDetailFullScreen(
    eyebrow: String,
    headline: String,
    scrollState: ScrollState,
    content: @Composable () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.window)
            .background(surfaces.panel)
            .windowInsetsPadding(WindowInsets.safeContent)
            .padding(horizontal = NovaGameDetailInset, vertical = 20.dp)
            .testTag("nova-game-detail-fullscreen"),
    ) {
        NovaGameDetailDestinationHeader(eyebrow, headline, readout = "")
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
private fun NovaGameDetailDestinationHeader(eyebrow: String, headline: String, readout: String) {
    val colors = LocalNovaComposeColors.current
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Text(
            text = eyebrow,
            color = colors.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.22.em,
        )
        Text(
            text = headline,
            color = colors.textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
        if (readout.isNotBlank()) {
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
