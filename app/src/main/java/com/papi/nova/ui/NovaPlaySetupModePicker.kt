package com.papi.nova.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.papi.nova.R
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaRowTrailing
import com.papi.nova.ui.panel.NovaSectionLabel

/** One selectable mode in the picker: the host catalog entry plus its standing here. */
internal data class NovaPlaySetupModeChoice(
    val id: String,
    val label: String,
    /** What choosing it means, or, when unavailable, the host's reason it cannot be. */
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

/** Every Game: the host catalog verbatim; a pick sets the host's Default Display. */
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
 * saved per-game override (not the resolved playMode) as the current card, because the
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
 * Where a game runs, drawn where a panel or a sheet asks for it: the title, then the list. Play
 * Setup and Polaris Sync push it as a page of their own ([NovaPlayInPage]); this form keeps the
 * list for a body that still draws it in place.
 */
@Composable
internal fun NovaPlaySetupModePicker(
    state: NovaPlaySetupModePickerState,
    onPick: (String) -> Unit,
    onPickHostDefault: (() -> Unit)?,
    onConfigureHost: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        NovaPlaySetupColumnHead(state.title)
        NovaPlaySetupModeList(
            state = state,
            onPick = onPick,
            onPickHostDefault = onPickHostDefault,
            onConfigureHost = onConfigureHost,
        )
    }
}

/**
 * One row per mode, banded private first and host display second, with the pinned Host default
 * entry above them when there is a host to follow.
 *
 * Focus and current are two things (R9): the ring and the selection fill are focus, and the one
 * current choice carries the trailing check, a SemiBold label and Current for TalkBack. Each row
 * says in full what choosing it means, so there is no footer to read it from. A mode the host will
 * not take stays a stop so its reason can be read, and swallows A; a mode that is only a host
 * default opens Polaris settings instead of picking. [rowModifier] marks a row by its key, and
 * whether it is the one that should take focus when the list opens: the current choice, or the
 * first that can be chosen.
 */
@Composable
internal fun NovaPlaySetupModeList(
    state: NovaPlaySetupModePickerState,
    onPick: (String) -> Unit,
    onPickHostDefault: (() -> Unit)?,
    onConfigureHost: () -> Unit = {},
    rowModifier: (key: String, initial: Boolean) -> Modifier = { _, _ -> Modifier },
) {
    val hostDefaultShown = state.hostDefaultLabel != null && onPickHostDefault != null
    val initialKey = when {
        hostDefaultShown && state.hostDefaultCurrent -> HOST_DEFAULT_KEY
        else -> state.choices.firstOrNull { it.current }?.id
            ?: state.choices.firstOrNull { it.enabled || it.hostDefaultOnly }?.id
            ?: HOST_DEFAULT_KEY.takeIf { hostDefaultShown }
    }
    val hostDefaultTitle = stringResource(R.string.nova_play_setup_fact_host_default)
    val activeNow = stringResource(R.string.nova_polaris_sync_status_active_now)
    val aiPick = stringResource(R.string.nova_play_setup_ai_pick)
    val openSettings = stringResource(R.string.nova_play_setup_mode_open_host_settings)
    val hostOnlyBadge = stringResource(R.string.nova_play_setup_mode_host_default_only_badge)
    val currentHostBadge = stringResource(R.string.nova_play_setup_mode_current_host_default_badge)
    val bandLabels = mapOf(
        "private" to stringResource(R.string.nova_play_setup_band_private),
        "host" to stringResource(R.string.nova_play_setup_band_host),
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
    ) {
        if (hostDefaultShown) {
            val detail = state.hostDefaultLabel.orEmpty()
            NovaRow(
                title = hostDefaultTitle,
                caption = detail,
                trailing = if (state.hostDefaultCurrent) NovaRowTrailing.Current else NovaRowTrailing.None,
                onClick = { onPickHostDefault() },
                modifier = rowModifier(HOST_DEFAULT_KEY, initialKey == HOST_DEFAULT_KEY)
                    .semantics { contentDescription = "$hostDefaultTitle. $detail" },
            )
        }
        novaModePickerBands(state.choices).forEach { band ->
            // A blank or future group carries no label rather than a made-up one.
            bandLabels[band.group]?.let { NovaSectionLabel(it) }
            band.choices.forEach { choice ->
                val badge = when {
                    choice.hostDefaultOnly -> if (choice.active) currentHostBadge else hostOnlyBadge
                    choice.aiRecommended -> aiPick
                    choice.active && !choice.current -> activeNow
                    else -> null
                }
                val caption = listOfNotNull(badge, choice.detail.takeIf { it.isNotBlank() }).joinToString(" · ")
                val interactive = choice.enabled || choice.hostDefaultOnly
                NovaRow(
                    title = choice.label,
                    caption = caption.ifBlank { null },
                    trailing = when {
                        choice.current -> NovaRowTrailing.Current
                        choice.hostDefaultOnly -> NovaRowTrailing.Opens
                        else -> NovaRowTrailing.None
                    },
                    disabledReason = if (interactive) null else caption.ifBlank { choice.detail },
                    onClick = { if (choice.enabled) onPick(choice.id) else if (choice.hostDefaultOnly) onConfigureHost() },
                    modifier = rowModifier(choice.id, initialKey == choice.id)
                        .semantics {
                            contentDescription = when {
                                choice.hostDefaultOnly -> {
                                    "${choice.label}. $badge. ${choice.detail} $openSettings."
                                }
                                choice.aiRecommended -> "${choice.label}. $aiPick. ${choice.detail}"
                                else -> "${choice.label}. ${choice.detail}"
                            }
                        }
                        // A row that cannot be chosen keeps every key a card would have acted on,
                        // so none reaches the row that opened this list.
                        .then(if (interactive) Modifier else Modifier.onPreviewKeyEvent { it.key == Key.Spacebar }),
                )
            }
        }
    }
}

/** The key of the pinned Host default entry among the mode ids. */
private const val HOST_DEFAULT_KEY = "nova-host-default"
