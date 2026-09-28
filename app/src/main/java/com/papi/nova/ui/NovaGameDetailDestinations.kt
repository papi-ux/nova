package com.papi.nova.ui

import android.content.res.Configuration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.papi.nova.ui.compose.NovaBadge
import com.papi.nova.ui.compose.NovaChromeType
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.novaHoldsFirstFocus
import kotlinx.coroutines.delay
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaControllerHintBar
import com.papi.nova.ui.panel.NovaCurrentMark
import com.papi.nova.ui.panel.novaFocusRing
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaTitleAndValueMeasurePolicy
import com.papi.nova.ui.panel.novaClickable
import com.papi.nova.ui.panel.novaPanelType

/** The three ways a launch can go when Polaris reports desktop Steam active. */
internal enum class NovaSteamLaunchChoice {
    PRIVATE_STREAM,
    MIRROR_DESKTOP,
    CLOSE_STEAM_THEN_PRIVATE,
}

/**
 * Dissolves the last band of a scrolling body, so what passes under the hint bar reads
 * as continuing rather than as clipped. It erases content alpha instead of painting a
 * ground: the panel is translucent, and a solid band would stripe window colour across
 * the artwork showing through it.
 */
internal fun Modifier.novaFadeAtCut(
    active: Boolean = true,
    /** How tall the dissolve is. A short list of rows wants a slim one; see Play Setup's rows. */
    band: Dp = NOVA_DETAIL_BOTTOM_FADE,
): Modifier = if (!active) this else this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = band.toPx().coerceAtMost(size.height)
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
 * A drill-in that needs the window. Used by Artwork, whose studio lays itself out as a
 * Row of weighted Columns and cannot fold into a panel.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NovaGameDetailFullScreen(
    eyebrow: String,
    headline: String,
    scrollState: ScrollState,
    onDismiss: () -> Unit,
    /** Given the height its body has, as the wide panel's is, so what fills it can fit to it. */
    content: @Composable (bodyHeight: Dp) -> Unit,
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
                // Cutouts and bars, not gesture zones: the same ground the library stands on.
                // safeContent also keeps clear of the back-swipe edges and the home gesture,
                // which cost this window about 30dp a side and as much at the bottom on a
                // handheld, for controls a swipe would not have reached anyway.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(
                    horizontal = novaGameDetailWindowInset(),
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
                selfInset = false,
            )
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val bodyHeight = maxHeight
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .novaFadeAtCut(scrollState.canScrollForward)
                        .novaHoldsFirstFocus()
                        .verticalScroll(scrollState),
                    content = { content(bodyHeight) },
                )
            }
            // A keyboard in landscape leaves about a third of the screen. The hints are for a
            // controller, which is not what is being used while it is up.
            if (!WindowInsets.isImeVisible) {
                NovaGameDetailDestinationHints(selfInset = false)
            }
        }
    }
}

/** Every destination says how to act and how to get back. */
@Composable
private fun NovaGameDetailDestinationHints(
    /** False inside a panel that already pads its sides; see [NovaGameDetailDestinationHeader]. */
    selfInset: Boolean = true,
) {
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
            .then(novaGameDetailSelfInset(selfInset))
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
    accessory: (@Composable () -> Unit)? = null,
    /**
     * Pad its own sides. The lane pads only top and bottom, so its rows can reach the edge they
     * are drawn against, and leaves each child to keep its text clear of a cutout. The panels
     * that fill the window pad all four sides themselves, and padded again the header and the
     * hint bar stood 28dp and an inset inside the body they belong to.
     */
    selfInset: Boolean = true,
) {
    val colors = LocalNovaComposeColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(novaGameDetailSelfInset(selfInset))
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
        if (accessory != null) {
            accessory()
            Spacer(modifier = Modifier.width(12.dp))
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

/** The side padding a lane child gives itself, or nothing inside a panel that already has it. */
@Composable
private fun novaGameDetailSelfInset(selfInset: Boolean): Modifier = if (!selfInset) Modifier else Modifier
    .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Horizontal))
    .padding(horizontal = NovaGameDetailInset)

/**
 * The side margin of a panel that fills the window.
 *
 * On a handheld or a phone it is the library's margin, so Play Setup opens to the width the
 * grid behind it has. A television keeps the wider one: nothing reports its overscan, and text
 * at the very edge of a TV can be off the glass.
 */
@Composable
internal fun novaGameDetailWindowInset(): Dp {
    val television = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    return if (television) NovaGameDetailInset else NOVA_DETAIL_WINDOW_INSET
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
 * One selectable row: a card at the row radius, laid out as the foundation's rows are. The title
 * and the caption wrap rather than being cut, and a [value] too wide to sit beside the title goes
 * under it, so nothing in the row is clipped, ellipsized or scrolled at any size (R13).
 *
 * @param current this row is the one current value of a choice, such as the Space this device
 *   is on. It carries the trailing check, a SemiBold title and Current for TalkBack (R9).
 * @param onFocused the row has just taken focus. Play Setup uses this to point its legend at
 *   whatever is under the cursor, so the explanation follows the d-pad without the legend having
 *   to be a stop on it.
 *
 * Focus is the one focus look, the selection fill and a ring inside the card, so it never reads
 * as the current value, and a focused row the cursor may stand on but not choose takes a quieter
 * ring. A acts on release and only on the row it was pressed on; a tap acts at once.
 *
 * The row never moves on focus. Scaling or offsetting a focused cell is what caused the #183
 * regression.
 */
@Composable
internal fun NovaSteamChoiceRow(
    label: String,
    caption: String,
    enabled: Boolean,
    onClick: (() -> Unit)? = null,
    value: String = "",
    onFocused: (() -> Unit)? = null,
    /**
     * Claim focus once the screen has settled. Only for a screen with no page stack of its own,
     * the Space chooser: a panel page marks its first row with novaInitialFocus instead, so the
     * host settles focus and restores it after a pop without a second request racing it.
     */
    autoFocus: Boolean = false,
    modifier: Modifier = Modifier,
    /** A short status word drawn as a chip before the chevron, and read after the label. */
    badge: String = "",
    /**
     * Read the caption too. A destination card's caption is the only place it says why it
     * cannot be chosen, and a row whose caption only restates its label keeps it quiet.
     */
    describeCaption: Boolean = false,
    /**
     * Let the cursor stop on this row while it cannot be chosen. A place a game cannot open in
     * says why in its caption, and with no way to stand on the card there was no way to have it
     * read out. A press still does nothing.
     */
    focusableWhenDisabled: Boolean = false,
    current: Boolean = false,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val type = novaPanelType
    var focused by remember { mutableStateOf(false) }
    val actionable = onClick != null && enabled
    val shape = RoundedCornerShape(NovaRadius.row)
    val currentLabel = stringResource(R.string.nova_panel_current)
    val ink = if (enabled) colors.textPrimary else colors.textMuted
    val quietInk = if (enabled) colors.textSecondary else colors.textMuted
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        // After the screen's own first-focus pass, so this is the answer that sticks.
        LaunchedEffect(Unit) {
            delay(NOVA_DETAIL_FIRST_ROW_FOCUS_DELAY_MS)
            runCatching { focusRequester.requestFocus() }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = NovaPanelMetrics.RowGap)
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.hasFocus && !focused) onFocused?.invoke()
                focused = state.hasFocus
            }
            .clip(shape)
            // The one focus look, and under the cursor but not choosable a quieter ring, so it
            // reads as here and not as ready.
            .novaFocusRing(
                shape = shape,
                ring = if (actionable) Color.Unspecified else surfaces.focusRing.copy(alpha = NOVA_DETAIL_RESTING_RING_ALPHA),
                restFill = surfaces.tile,
                restBorder = surfaces.tileBorder,
                restBorderWidth = NovaPanelMetrics.Hairline,
            )
            .semantics {
                contentDescription = listOf(label, value, badge, if (describeCaption) caption else "")
                    .filter { it.isNotBlank() }
                    .joinToString(". ")
                // A row that offers an action it cannot take right now says so to TalkBack and to tests.
                if (onClick != null && !enabled) disabled()
                if (current) {
                    this.selected = true
                    stateDescription = currentLabel
                }
            }
            // A on release, and only on the row it was pressed on; a row that cannot be chosen
            // swallows it rather than letting it reach whatever holds this row.
            .novaClickable(
                enabled = actionable,
                role = Role.Button,
                focusableWhenDisabled = focusableWhenDisabled,
            ) { onClick?.invoke() }
            .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
    ) {
        val titleBlock: @Composable () -> Unit = {
            Column {
                Text(
                    text = label,
                    style = type.rowTitle,
                    fontWeight = if (current) FontWeight.SemiBold else type.rowTitle.fontWeight,
                    color = ink,
                )
                if (caption.isNotBlank()) {
                    Text(text = caption, style = type.caption, color = quietInk)
                }
            }
        }
        if (value.isNotBlank()) {
            Layout(
                contents = listOf(
                    titleBlock,
                    {
                        // A value read against other values, so the digits line up.
                        Text(text = value, style = type.value.copy(fontFeatureSettings = "tnum"), color = quietInk)
                    },
                ),
                modifier = Modifier.weight(1f),
                measurePolicy = ChoiceRowValueMeasurePolicy,
            )
        } else {
            Box(modifier = Modifier.weight(1f)) { titleBlock() }
        }
        if (badge.isNotBlank()) {
            NovaBadge(text = badge, color = quietInk)
        }
        if (current) {
            NovaCurrentMark()
        }
        if (actionable) {
            Text(text = "›", style = type.value, color = colors.textMuted)
        }
    }
}

// Inside a row, which pads itself: the value beside the title while the title keeps its share,
// and under it otherwise.
private val ChoiceRowValueMeasurePolicy = NovaTitleAndValueMeasurePolicy(
    labelInset = 0.dp,
    valueInset = 0.dp,
    stackGap = NovaPanelMetrics.SpaceXs,
)

/** The ring on a row the cursor may stand on but not choose: here, and not ready. */
private const val NOVA_DETAIL_RESTING_RING_ALPHA = 0.45f

/** The body dissolves over this much before the hint bar, marking the cut. */
private val NOVA_DETAIL_BOTTOM_FADE = 52.dp

/** Below this a phone in landscape has no height to spare for chrome. */
private val NOVA_DETAIL_SHORT_VIEWPORT = 500.dp

/** The library's side margin, for the panels that fill the window over it. */
private val NOVA_DETAIL_WINDOW_INSET = 18.dp

/** Long enough to land after novaHoldsFirstFocus rather than race it. */
private const val NOVA_DETAIL_FIRST_ROW_FOCUS_DELAY_MS = 140L
