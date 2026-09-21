package com.papi.nova.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.R
import com.papi.nova.grid.NovaHostPlaySurface
import com.papi.nova.grid.novaHostInUse
import com.papi.nova.grid.novaHostPlaySurface
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.NovaRevealingText
import com.papi.nova.ui.compose.novaHoldsFirstFocus

/** How a host's state reads at a glance: ready to play, wants something from you, or nothing to say. */
internal enum class NovaHostSheetTone { READY, ATTENTION, QUIET }

/** One thing a host's sheet can do, with the sentence that says what it does. */
internal data class NovaHostSheetAction(
    val key: String,
    val label: String,
    val caption: String,
    @param:DrawableRes val iconRes: Int,
)

internal data class NovaHostSheetState(
    val name: String,
    val status: String,
    val tone: NovaHostSheetTone,
    val hint: String,
    val primary: NovaHostSheetAction?,
    val actions: List<NovaHostSheetAction>,
    val destructive: NovaHostSheetAction?,
)

/** The header's words for a host, as string resources so the screen can fill in the address. */
internal data class NovaHostSheetCopy(
    @param:StringRes val statusRes: Int,
    @param:StringRes val hintRes: Int,
    val tone: NovaHostSheetTone,
    /** What the status is about when that is not the host's address: the device whose game is open. */
    val statusArg: String? = null,
)

/**
 * What the sheet's header says about a host: the reading of its state its card gives, in the
 * card's own words, so the two never disagree about the same machine.
 */
internal fun novaHostSheetCopy(details: ComputerDetails): NovaHostSheetCopy {
    if (details.state == ComputerDetails.State.OFFLINE) {
        return NovaHostSheetCopy(
            statusRes = R.string.pcview_card_status_offline,
            hintRes = if (details.macAddress != null) {
                R.string.pcview_card_hint_wake
            } else {
                R.string.pcview_card_hint_offline_no_wake
            },
            tone = NovaHostSheetTone.QUIET,
        )
    }
    if (details.state != ComputerDetails.State.ONLINE) {
        return NovaHostSheetCopy(
            statusRes = R.string.pcview_card_status_connecting,
            hintRes = R.string.pcview_card_hint_refreshing,
            tone = NovaHostSheetTone.QUIET,
        )
    }
    val pairedWithoutItsCertificate =
        details.pairState == PairingManager.PairState.PAIRED && details.serverCert == null
    return when {
        pairedWithoutItsCertificate -> NovaHostSheetCopy(
            statusRes = R.string.pcview_card_status_repair_pair,
            hintRes = R.string.pcview_card_hint_pair_repair,
            tone = NovaHostSheetTone.ATTENTION,
        )
        details.pairState == PairingManager.PairState.NOT_PAIRED -> NovaHostSheetCopy(
            statusRes = if (details.serverCert == null) {
                R.string.pcview_card_status_pair_required
            } else {
                R.string.pcview_card_status_repair_pair
            },
            hintRes = R.string.pcview_card_hint_pair,
            tone = NovaHostSheetTone.ATTENTION,
        )
        details.runningGameId != 0 -> if (
            novaHostPlaySurface(true, details.currentGameOwnedByClient, details.libraryState, details.currentGameWatchable)
                .let { it != NovaHostPlaySurface.RESUME && it != NovaHostPlaySurface.WATCH }
        ) {
            val inUse = novaHostInUse(details.currentGameOwnerDeviceName, details.currentGameWatchable)
            NovaHostSheetCopy(
                statusRes = inUse.statusRes,
                // The card's advice points at Manage, and this is Manage.
                hintRes = inUse.sheetHintRes,
                tone = NovaHostSheetTone.READY,
                statusArg = inUse.owner,
            )
        } else {
            NovaHostSheetCopy(
                statusRes = R.string.pcview_card_status_streaming,
                hintRes = R.string.pcview_card_hint_streaming,
                tone = NovaHostSheetTone.READY,
            )
        }
        details.libraryState == ComputerDetails.LibraryState.AVAILABLE -> NovaHostSheetCopy(
            statusRes = R.string.pcview_card_status_library_ready_format,
            hintRes = if (details.spacesAvailable) {
                R.string.pcview_card_hint_spaces
            } else {
                R.string.pcview_card_hint_open_library
            },
            tone = NovaHostSheetTone.READY,
        )
        details.libraryState == ComputerDetails.LibraryState.UNKNOWN -> NovaHostSheetCopy(
            statusRes = R.string.pcview_card_status_checking_library,
            hintRes = R.string.pcview_card_hint_checking_library,
            tone = NovaHostSheetTone.READY,
        )
        else -> NovaHostSheetCopy(
            statusRes = R.string.pcview_card_status_compatibility_format,
            hintRes = R.string.pcview_card_hint_open_apps,
            tone = NovaHostSheetTone.READY,
        )
    }
}

/**
 * Collects what a host's sheet will offer, in the order the screen decides it.
 *
 * The sheet was a flat list under two headings, so the one thing a host is opened for, its
 * library or the stream it is running, sat in a row like Test Network Connection. The first thing
 * offered under play is the primary action and stands alone at the top; anything else, played or
 * managed, is a tile under it. Removing the host stands apart at the end.
 */
internal class NovaHostSheetMenu {
    var primary: NovaHostSheetAction? = null
        private set
    var destructive: NovaHostSheetAction? = null
        private set
    private val tiles = mutableListOf<NovaHostSheetAction>()
    private val handlers = HashMap<String, () -> Unit>()

    val actions: List<NovaHostSheetAction> get() = tiles

    fun play(action: NovaHostSheetAction, run: () -> Unit) {
        if (primary == null) primary = action else tiles += action
        handlers[action.key] = run
    }

    fun manage(action: NovaHostSheetAction, run: () -> Unit) {
        tiles += action
        handlers[action.key] = run
    }

    fun remove(action: NovaHostSheetAction, run: () -> Unit) {
        destructive = action
        handlers[action.key] = run
    }

    fun run(key: String) {
        handlers[key]?.invoke()
    }
}

/** Tiles in rows of [columns]. A short last row keeps its columns rather than stretching across the sheet. */
internal fun <T> novaHostSheetRows(items: List<T>, columns: Int): List<List<T>> =
    items.chunked(columns.coerceAtLeast(1))

/**
 * A host's sheet: who it is and how it is, the one thing you most likely came for, then the rest.
 *
 * Every action says what it does in a line under its name, because "Compatibility App List" and
 * "Go to Server Config" explain themselves to nobody who has not used them. A line too long for
 * its tile shows the rest of itself under the cursor.
 */
@Composable
internal fun NovaHostSheet(
    state: NovaHostSheetState,
    columns: Int,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val scroll = rememberScrollState()
    val moreBelow = scroll.maxValue - scroll.value > 4
    val firstFocusKey = (state.primary ?: state.actions.firstOrNull() ?: state.destructive)?.key

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 18.dp)
            .testTag("nova-host-sheet"),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 40.dp, height = 4.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.textMuted.copy(alpha = 0.5f)),
        )

        NovaHostSheetHeader(state = state, modifier = Modifier.padding(top = 10.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(NOVA_HOST_SHEET_GAP),
            modifier = Modifier
                .padding(top = 12.dp)
                .weight(1f, fill = false)
                .novaFadeAtCut(moreBelow, band = 18.dp)
                .verticalScroll(scroll),
        ) {
            state.primary?.let { primary ->
                NovaHostSheetTile(
                    action = primary,
                    kind = NovaHostSheetTileKind.PRIMARY,
                    captionLines = 1,
                    firstFocus = primary.key == firstFocusKey,
                    onClick = { onAction(primary.key) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val tiles = state.actions.map { it to NovaHostSheetTileKind.PLAIN } +
                listOfNotNull(state.destructive?.let { it to NovaHostSheetTileKind.DESTRUCTIVE })
            novaHostSheetRows(tiles, columns).forEach { row ->
                // One height for the row: a caption that takes a second line lifts its neighbour too.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NOVA_HOST_SHEET_GAP),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                ) {
                    row.forEach { (action, kind) ->
                        NovaHostSheetTile(
                            action = action,
                            kind = kind,
                            captionLines = 2,
                            firstFocus = action.key == firstFocusKey,
                            onClick = { onAction(action.key) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun NovaHostSheetHeader(state: NovaHostSheetState, modifier: Modifier = Modifier) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val lamp = when (state.tone) {
        NovaHostSheetTone.READY -> colorResource(R.color.nova_success)
        NovaHostSheetTone.ATTENTION -> colors.warning
        NovaHostSheetTone.QUIET -> colors.textMuted
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxWidth().testTag("nova-host-sheet-header"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(NovaRadius.hero))
                .background(surfaces.control),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_computer),
                contentDescription = null,
                tint = if (state.tone == NovaHostSheetTone.QUIET) colors.textMuted else colors.accent,
                modifier = Modifier.size(26.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.name,
                color = colors.textPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 3.dp),
            ) {
                Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(percent = 50)).background(lamp))
                Text(
                    text = state.status,
                    color = if (state.tone == NovaHostSheetTone.ATTENTION) colors.warning else colors.textSecondary,
                    fontSize = 13.sp,
                )
            }
            if (state.hint.isNotBlank()) {
                // Nothing points at the header, so its advice takes the lines it needs.
                Text(
                    text = state.hint,
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

private enum class NovaHostSheetTileKind { PRIMARY, PLAIN, DESTRUCTIVE }

@Composable
private fun NovaHostSheetTile(
    action: NovaHostSheetAction,
    kind: NovaHostSheetTileKind,
    captionLines: Int,
    firstFocus: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val interactionSource = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NovaRadius.hero)
    val primary = kind == NovaHostSheetTileKind.PRIMARY

    val background = if (primary) {
        Brush.linearGradient(
            if (focused) listOf(
                lerp(colors.accent, Color.White, 0.48f),
                lerp(colors.accent, Color.White, 0.68f),
                lerp(colors.accent, Color.White, 0.82f),
            ) else listOf(
                colors.accent,
                lerp(colors.accent, Color.White, 0.28f),
                lerp(colors.accent, Color.White, 0.62f),
            ),
        )
    } else {
        SolidColor(if (focused) surfaces.selectedControl else surfaces.control)
    }
    val ink = when (kind) {
        NovaHostSheetTileKind.PRIMARY -> colors.onAccent
        NovaHostSheetTileKind.DESTRUCTIVE -> colorResource(R.color.nova_error)
        NovaHostSheetTileKind.PLAIN -> colors.textPrimary
    }
    val quietInk = if (primary) colors.onAccent.copy(alpha = 0.78f) else colors.textSecondary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .heightIn(min = NOVA_HOST_SHEET_TILE_MIN_HEIGHT)
            .clip(shape)
            .background(background, shape)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = when {
                    focused && primary -> colors.onAccent
                    focused -> colors.accent
                    else -> surfaces.tileBorder
                },
                shape = shape,
            )
            .then(if (firstFocus) Modifier.novaHoldsFirstFocus() else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = "${action.label}. ${action.caption}" }
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("nova-host-sheet-action-${action.key}"),
    ) {
        Icon(
            painter = painterResource(action.iconRes),
            contentDescription = null,
            tint = ink.copy(alpha = if (primary) 0.9f else 0.76f),
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action.label,
                color = ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                // Under the cursor a label runs past rather than ending in an ellipsis; it moves
                // only when it does not fit.
                overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
            )
            NovaRevealingText(
                text = action.caption,
                highlighted = focused,
                maxLines = captionLines,
                color = quietInk,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

private val NOVA_HOST_SHEET_GAP = 8.dp
private val NOVA_HOST_SHEET_TILE_MIN_HEIGHT = 52.dp
