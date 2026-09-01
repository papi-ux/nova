package com.papi.nova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.R
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaRadius

/** One selectable mode in the picker: the host catalog entry plus its standing here. */
internal data class NovaPlaySetupModeChoice(
    val id: String,
    val label: String,
    /** What choosing it means — or, when unavailable, the host's reason it cannot be. */
    val detail: String,
    /** Registry grouping: "private" or "host"; anything else bands together at the end. */
    val group: String,
    val current: Boolean,
    /** In effect this session without being the saved choice (fallback or pending relaunch). */
    val active: Boolean,
    val enabled: Boolean,
    /** Available on the host, but only as the host-wide default. Press opens host settings. */
    val hostDefaultOnly: Boolean = false,
    /** The provider's advisory pick from the current optimization payload; never auto-applied. */
    val aiRecommended: Boolean = false,
)

internal data class NovaPlaySetupModeBand(
    val group: String,
    val choices: List<NovaPlaySetupModeChoice>,
)

/**
 * The full-panel mode picker's state. Per-game scope carries the pinned follow-the-host
 * entry; host scope has no host to follow, so [hostDefaultLabel] is null there.
 */
internal data class NovaPlaySetupModePickerState(
    val title: String,
    val hostDefaultLabel: String?,
    val hostDefaultCurrent: Boolean,
    val choices: List<NovaPlaySetupModeChoice>,
)

/**
 * Whether a row's press should open the picker rather than cycle in place.
 *
 * The classic headless/virtual pair cycles in place. A multi-choice set that includes
 * registry modes cannot be represented by that inline pair, so it opens the picker even
 * when it contains exactly two choices. Hosts that predate the mode catalog keep the old
 * two-value press.
 */
internal fun novaModePickerEligible(choiceCount: Int, inlineChoiceCount: Int = 2): Boolean =
    choiceCount > 1 && choiceCount > inlineChoiceCount

/**
 * Band order is the mental model of the choice: private modes (the desktop stays
 * untouched) first, host-display modes (uses or swaps the host screen) second, and any
 * grouping a future host invents appended in the order it arrived rather than dropped.
 */
internal fun novaModePickerBands(choices: List<NovaPlaySetupModeChoice>): List<NovaPlaySetupModeBand> {
    val known = listOf("private", "host")
    val byGroup = choices.groupBy { it.group }
    val bands = mutableListOf<NovaPlaySetupModeBand>()
    known.forEach { group ->
        byGroup[group]?.let { bands += NovaPlaySetupModeBand(group, it) }
    }
    byGroup.keys.filterNot { it in known }.forEach { group ->
        bands += NovaPlaySetupModeBand(group, byGroup.getValue(group))
    }
    return bands
}

/** Every Game: the host catalog verbatim — pick sets the host's Default Display. */
internal fun buildHostModePickerState(
    modes: List<NovaPolarisModeUiState>,
    title: String,
): NovaPlaySetupModePickerState = NovaPlaySetupModePickerState(
    title = title,
    hostDefaultLabel = null,
    hostDefaultCurrent = false,
    choices = modes.map { mode ->
        NovaPlaySetupModeChoice(
            id = mode.mode,
            label = mode.label,
            detail = if (!mode.available && mode.unavailableReason.isNotBlank()) {
                mode.unavailableReason
            } else {
                mode.reason
            },
            group = mode.group,
            current = mode.selectedDesired,
            active = mode.selectedEffective && !mode.selectedDesired,
            enabled = mode.enabled,
        )
    },
)

/**
 * This Game: the host catalog cut down to what this game's contract allows, with the
 * saved per-game override (not the resolved playMode) as the current card — because the
 * picker edits the override, and the pinned Host default entry is "no override".
 */
internal fun buildGameModePickerState(
    modes: List<NovaPolarisModeUiState>,
    allowedModes: List<String>,
    playMode: String,
    hasExplicitOverride: Boolean,
    title: String,
    hostDefaultLabel: String,
    aiRecommendedMode: String = "",
    hostDefaultOnlyDetail: String = "",
    plainModeDetails: Map<String, String> = emptyMap(),
): NovaPlaySetupModePickerState {
    val allowed = allowedModes.map { PolarisGame.normalizeLaunchMode(it) }.toSet()
    return NovaPlaySetupModePickerState(
        title = title,
        hostDefaultLabel = hostDefaultLabel,
        hostDefaultCurrent = !hasExplicitOverride,
        choices = modes
            .filter { allowed.isEmpty() || PolarisStreamDisplayMode.normalize(it.mode) in allowed }
            .map { mode ->
                val normalizedMode = PolarisStreamDisplayMode.normalize(mode.mode)
                val plainModeDetail = plainModeDetails[normalizedMode].orEmpty()
                // A physical dongle swap is never a per-game action. Fail closed even
                // when an older host predates session_overridable and defaults it true.
                val hostDefaultOnly = mode.available && (
                    !mode.sessionOverridable ||
                        normalizedMode == PolarisClientSettings.MODE_HEADLESS_DONGLE
                    )
                NovaPlaySetupModeChoice(
                    id = mode.mode,
                    label = mode.label,
                    detail = when {
                        !mode.available && mode.unavailableReason.isNotBlank() -> mode.unavailableReason
                        // Available, but the host will not take it for one session. Saying
                        // so beats offering a pick the host silently drops on launch.
                        hostDefaultOnly && hostDefaultOnlyDetail.isNotBlank() ->
                            hostDefaultOnlyDetail
                        plainModeDetail.isNotBlank() -> plainModeDetail
                        else -> mode.reason
                    },
                    group = mode.group,
                    current = hasExplicitOverride && mode.mode == playMode,
                    active = mode.mode == playMode && !hasExplicitOverride,
                    enabled = mode.available && !hostDefaultOnly,
                    hostDefaultOnly = hostDefaultOnly,
                    aiRecommended = mode.available && !hostDefaultOnly &&
                        aiRecommendedMode.isNotBlank() && mode.mode == aiRecommendedMode,
                )
            },
    )
}

/**
 * The picker owns the panel body the way the desktop-Steam decision does: choosing where
 * a game runs is the one moment nothing else on the screen matters. Unlike the
 * comparison strip below the rows — a legend, deliberately not a focus target — these
 * cards ARE the surface, so they take d-pad focus; a disabled card still takes it so a
 * controller can read the host's reason in the footer, it just does nothing on press.
 */
@Composable
internal fun NovaPlaySetupModePicker(
    state: NovaPlaySetupModePickerState,
    onPick: (String) -> Unit,
    onPickHostDefault: (() -> Unit)?,
    onConfigureHost: () -> Unit = {},
) {
    val colors = LocalNovaComposeColors.current
    var footer by remember(state) {
        mutableStateOf(state.choices.firstOrNull { it.current }?.detail.orEmpty())
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        NovaPlaySetupColumnHead(state.title)

        if (state.hostDefaultLabel != null && onPickHostDefault != null) {
            NovaPlaySetupModeHostDefaultCard(
                label = stringResource(R.string.nova_play_setup_fact_host_default),
                detail = state.hostDefaultLabel,
                current = state.hostDefaultCurrent,
                onPick = onPickHostDefault,
                onFocusedDetail = { footer = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        novaModePickerBands(state.choices).forEach { band ->
            when (band.group) {
                "private" -> NovaPlaySetupColumnHead(stringResource(R.string.nova_play_setup_band_private))
                "host" -> NovaPlaySetupColumnHead(stringResource(R.string.nova_play_setup_band_host))
                // A blank or future group carries no header rather than a made-up one.
                else -> Unit
            }
            band.choices.chunked(3).forEachIndexed { chunkIndex, chunk ->
                if (chunkIndex > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    chunk.forEach { choice ->
                        NovaPlaySetupModeCard(
                            choice = choice,
                            onPick = { onPick(choice.id) },
                            onConfigureHost = onConfigureHost,
                            onFocusedDetail = { footer = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // The focused card's full story — this line is why the cards themselves can stay
        // one status line tall, and where an unavailable mode's host reason is quoted.
        Text(
            text = footer,
            color = colors.textMuted,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp)
                .padding(top = 2.dp),
        )
    }
}

@Composable
private fun NovaPlaySetupModeCard(
    choice: NovaPlaySetupModeChoice,
    onPick: () -> Unit,
    onConfigureHost: () -> Unit,
    onFocusedDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(NovaRadius.row)
    var focused by remember { mutableStateOf(false) }
    val interactive = choice.enabled || choice.hostDefaultOnly
    Column(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocusedDetail(choice.detail)
                }
            }
            // One focus target per card: clickable() brings its own, and stacking a
            // bare focusable() in front of it gave the d-pad a second, click-less stop.
            // Focus parked there, so A/DPAD_CENTER activated nothing and traversal
            // stepped twice per card. Disabled cards keep the plain focusable() so a
            // controller can still read the host's reason in the footer.
            .then(
                if (interactive) {
                    Modifier.clickable(role = Role.Button) {
                        if (choice.enabled) onPick() else onConfigureHost()
                    }
                } else {
                    Modifier
                        // A disabled card remains focusable so its host-supplied reason
                        // can be read, but activation must stop here. Otherwise Compose
                        // bubbles the controller key to an ancestor, which can activate
                        // the mode row underneath the full-panel picker.
                        .onPreviewKeyEvent { event ->
                            event.key == Key.ButtonA ||
                                event.key == Key.DirectionCenter ||
                                event.key == Key.Enter ||
                                event.key == Key.NumPadEnter ||
                                event.key == Key.Spacebar
                        }
                        .focusable()
                }
            )
            .heightIn(min = NovaGameDetailActionHeight)
            .clip(shape)
            .background(if (choice.current) colors.accentSurface else surfaces.tile)
            .border(
                1.dp,
                when {
                    focused -> colors.accent
                    choice.current -> colors.accent.copy(alpha = 0.58f)
                    choice.active -> colors.accent.copy(alpha = 0.34f)
                    else -> surfaces.tileBorder
                },
                shape,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics {
                contentDescription = when {
                    choice.hostDefaultOnly -> {
                        val state = if (choice.active) {
                            "Current host default"
                        } else {
                            "Host default only"
                        }
                        "${choice.label}. $state. ${choice.detail} Open Polaris Settings."
                    }
                    choice.aiRecommended -> "${choice.label}. Host match. ${choice.detail}"
                    else -> "${choice.label}. ${choice.detail}"
                }
                if (choice.current) selected = true
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = choice.label,
                color = if (interactive) colors.textPrimary else colors.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (choice.hostDefaultOnly) {
                Text(
                    text = stringResource(
                        if (choice.active) {
                            R.string.nova_play_setup_mode_current_host_default_badge
                        } else {
                            R.string.nova_play_setup_mode_host_default_only_badge
                        },
                    ),
                    color = if (choice.active) colors.accent else colors.textMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp),
                )
            } else if (choice.aiRecommended) {
                Text(
                    text = stringResource(R.string.nova_play_setup_ai_pick),
                    color = colors.accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        Text(
            text = choice.detail,
            color = if (choice.active && !choice.current) colors.accent else colors.textMuted,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (choice.hostDefaultOnly) {
            Text(
                text = stringResource(R.string.nova_play_setup_mode_open_host_settings),
                color = colors.accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun NovaPlaySetupModeHostDefaultCard(
    label: String,
    detail: String,
    current: Boolean,
    onPick: () -> Unit,
    onFocusedDetail: (String) -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(NovaRadius.row)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocusedDetail(detail)
                }
            }
            .clickable(role = Role.Button) { onPick() }
            .clip(shape)
            .background(if (current) colors.accentSurface else surfaces.tile)
            .border(
                1.dp,
                when {
                    focused -> colors.accent
                    current -> colors.accent.copy(alpha = 0.58f)
                    else -> surfaces.tileBorder
                },
                shape,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics {
                contentDescription = "$label. $detail"
                if (current) selected = true
            },
    ) {
        Text(
            text = label,
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = detail,
            color = colors.textMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
