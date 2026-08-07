package com.papi.nova.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.papi.nova.ui.compose.NovaChromeType
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.novaHoldsFirstFocus
import kotlinx.coroutines.delay
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaControllerHintBar

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
            .background(NovaGameDetailScrim.copy(alpha = NOVA_DETAIL_SCRIM_ALPHA))
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
                // Vertical only. A cutout must not eat text, but it need not stop a row
                // background from reaching the edge it is drawn against.
                .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Vertical))
                .padding(vertical = if (shortViewport) 10.dp else 20.dp)
                .testTag("nova-game-detail-panel"),
        ) {
            NovaGameDetailDestinationHeader(
                eyebrow = eyebrow,
                headline = headline,
                readout = readout,
                compact = shortViewport,
                onDismiss = onDismiss,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .novaFadeAtCut()
                    .novaHoldsFirstFocus()
                    .verticalScroll(scrollState),
                content = { content() },
            )
            NovaGameDetailDestinationHints()
        }
    }
}

/**
 * Dissolves the last band of a scrolling body, so what passes under the hint bar reads
 * as continuing rather than as clipped. It erases content alpha instead of painting a
 * ground: the panel is translucent, and a solid band would stripe window colour across
 * the artwork showing through it.
 */
private fun Modifier.novaFadeAtCut(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = NOVA_DETAIL_BOTTOM_FADE.toPx().coerceAtMost(size.height)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height - fade,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - fade),
            size = Size(size.width, fade),
            blendMode = BlendMode.DstIn,
        )
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
 * A drill-in that takes the full width and still lets the game through.
 *
 * Play Setup needs the width -- the decision is comparative and a 53% lane cannot put two
 * things side by side -- but it must not become a second screen. Deciding how to play
 * while looking at the thing you are deciding about is the reason this window is
 * cinematic at all, and an opaque full-width panel throws that away.
 *
 * So: a scrim to separate, and a translucent ground over it. This is the difference
 * between this and [NovaGameDetailFullScreen], which is solid on purpose because the
 * studio has no outside worth revealing.
 */
@Composable
internal fun NovaGameDetailWidePanel(
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
                .background(NovaGameDetailScrim.copy(alpha = NOVA_DETAIL_SCRIM_ALPHA))
                .background(colors.window.copy(alpha = NOVA_DETAIL_WIDE_PANEL_ALPHA))
                .background(surfaces.panel)
                .windowInsetsPadding(WindowInsets.safeContent)
                .padding(
                    horizontal = NovaGameDetailInset,
                    vertical = if (shortViewport) 10.dp else 20.dp,
                )
                .testTag("nova-game-detail-wide-panel"),
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
                    .novaFadeAtCut()
                    .novaHoldsFirstFocus()
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
                    .novaFadeAtCut()
                    .novaHoldsFirstFocus()
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
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = NovaGameDetailInset)
            .padding(top = 10.dp),
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
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = NovaGameDetailInset)
            .padding(bottom = if (compact) 6.dp else 14.dp),
    ) {
    Column(modifier = Modifier.weight(1f)) {
        if (!compact) {
            Text(
                text = eyebrow,
                color = colors.textMuted,
                style = NovaChromeType.label(fontSize = 10.sp),
            )
        }
        Text(
            text = if (compact) "$eyebrow · $headline" else headline,
            color = colors.textPrimary,
            fontSize = if (compact) 17.sp else 27.sp,
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
            .clip(RoundedCornerShape(NovaRadius.chip))
            .background(surfaces.control)
            .border(1.dp, colors.divider.copy(alpha = 0.6f), RoundedCornerShape(NovaRadius.chip))
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
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = NovaGameDetailInset)
            .padding(top = 16.dp, bottom = 6.dp),
    ) {
        Text(
            text = text.uppercase(),
            color = colors.textMuted,
            style = NovaChromeType.label(fontSize = 8.sp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.divider.copy(alpha = 0.5f)),
        )
    }
}

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
                .clip(RoundedCornerShape(NovaRadius.hero))
                .background(colors.warning.copy(alpha = 0.13f))
                .border(
                    1.dp,
                    colors.warning.copy(alpha = 0.46f),
                    RoundedCornerShape(NovaRadius.hero),
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

/**
 * One selectable row. A bordered card at the row radius, which is the shape the option
 * cards in this window already used -- drawing rows full bleed here and as cards there
 * meant two shapes for one kind of control.
 *
 * @param selected this row holds the current value. Drawn as a tint.
 * @param onFocused the row has just taken focus. Play Setup uses this to point the
 *   comparison strip at whatever is under the cursor, so the explanation follows the
 *   d-pad without the strip having to be a stop on it.
 *
 * Focus is drawn as a ring, and the two compose: a focused row that is not the current
 * value gets the ring alone. That state is the most common one on a d-pad and it had no
 * drawing at all while selection and focus shared one.
 *
 * The row never moves on focus. Scaling or offsetting a focused cell is what caused the
 * #183 regression.
 */
@Composable
internal fun NovaSteamChoiceRow(
    label: String,
    caption: String,
    enabled: Boolean,
    onClick: (() -> Unit)? = null,
    value: String = "",
    selected: Boolean = false,
    onFocused: (() -> Unit)? = null,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    var focused by remember { mutableStateOf(false) }
    val actionable = onClick != null && enabled
    val accentBar = colors.accent
    val barWidth = NOVA_DETAIL_ROW_FOCUS_BAR
    val shape = RoundedCornerShape(NovaRadius.row)
    // The accent is light on a dark surface and dark on a light one, so the same alpha
    // is a whisper in one theme and an inverted block in the other. Scale it by the
    // polarity; the bar, not the fill, is what says this row has focus.
    //
    // Polarity comes from the text rather than colors.window, because under Portable
    // Chrome the panel takes its lightness from surfaces.panel layered over the window,
    // so the window is the wrong ground to ask and the tint stayed at full strength.
    val tint = colors.accent.copy(
        alpha = if (colors.textPrimary.luminance() < 0.5f) 0.07f else 0.16f,
    )

    val ringing = focused && actionable
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = NOVA_DETAIL_ROW_GAP)
            .heightIn(min = NOVA_DETAIL_ROW_MIN_HEIGHT)
            .onFocusChanged { state ->
                val gained = state.isFocused || state.hasFocus
                if (gained && !focused) onFocused?.invoke()
                focused = gained
            }
            .then(
                if (actionable) {
                    Modifier.clickable(role = Role.Button) { onClick?.invoke() }
                } else {
                    Modifier
                }
            )
            // Explicit, like every other focusable in the app: clickable alone did not
            // register the row as a focus target and the d-pad had nothing to reach.
            .focusable(enabled = actionable)
            .clip(shape)
            .background(if (selected) tint else surfaces.tile)
            .border(
                1.dp,
                if (ringing || selected) colors.accent.copy(alpha = 0.72f) else surfaces.tileBorder,
                shape,
            )
            .drawBehind {
                if (selected) {
                    drawRect(color = accentBar, size = Size(barWidth.toPx(), size.height))
                }
            }
            .then(if (ringing) Modifier.border(NOVA_DETAIL_FOCUS_RING, surfaces.focusRing, shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .semantics { contentDescription = if (value.isBlank()) label else "$label. $value" },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (enabled) colors.textPrimary else colors.textMuted,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (value.isNotBlank()) {
            Text(
                text = value,
                color = if (enabled) colors.textSecondary else colors.textMuted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                // a value read against other values, so the digits line up
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (actionable) {
            Text(
                text = "\u203a",
                color = colors.textMuted,
                fontSize = 17.sp,
                modifier = Modifier.padding(start = 10.dp, end = 4.dp),
            )
        }
    }
}

/** 438dp of an 832dp landscape shell, as drawn. */
private const val NOVA_DETAIL_PANEL_WIDTH_FRACTION = 0.53f

/** Every row is at least a full action's worth of height. */
private val NOVA_DETAIL_ROW_MIN_HEIGHT = 48.dp

/** The focused row grows a bar at its edge instead of a border that moves it. */
private val NOVA_DETAIL_ROW_FOCUS_BAR = 3.dp

/**
 * Lighter than the side panel's 0.80, because this one covers the whole window: at the
 * side-panel alpha a full-width sheet reads as opaque and the hero is gone.
 */
private const val NOVA_DETAIL_WIDE_PANEL_ALPHA = 0.86f

/** Cards need air between them where hairline rows did not. */
private val NOVA_DETAIL_ROW_GAP = 6.dp

/** The ring is focus. It sits outside whatever the selected state already drew. */
private val NOVA_DETAIL_FOCUS_RING = 2.dp

/** The body dissolves over this much before the hint bar, marking the cut. */
private val NOVA_DETAIL_BOTTOM_FADE = 52.dp

/**
 * A scrim is a shadow, not a surface, so it does not follow the theme. Painting it in
 * the window colour turned into a white veil under Portable Chrome.
 */
private val NovaGameDetailScrim = Color.Black

/** Enough of the game stays visible for the panel to read as a layer over it. */
private const val NOVA_DETAIL_SCRIM_ALPHA = 0.58f

/** Translucent enough to show artwork, opaque enough to keep body text legible. */
private const val NOVA_DETAIL_PANEL_ALPHA = 0.80f

/** Long enough for the body to be laid out, so the focus request has a target. */
private const val NOVA_DETAIL_FOCUS_SETTLE_MS = 75L

/** Below this a phone in landscape has no height to spare for chrome. */
private val NOVA_DETAIL_SHORT_VIEWPORT = 500.dp
