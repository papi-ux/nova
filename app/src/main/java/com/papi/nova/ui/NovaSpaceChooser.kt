package com.papi.nova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.R
import com.papi.nova.api.PolarisSpaces
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaControllerHintBar
import kotlinx.coroutines.delay

/**
 * The Space chooser, in the detail window's row grammar: the current Space marked, a status
 * chip on every row, and A/B hints at the foot.
 *
 * Rows stay selectable while a Space is starting, stopping or in use, because choosing a
 * Space is how you browse its games; only what the host says cannot run at all is off. Focus
 * is claimed once, when the chooser opens, so a poll that changes a chip does not move the
 * cursor. Three Spaces and the Desktop fit a Retroid Pocket 6 without scrolling.
 *
 * Desktop is always listed. When this device has no Desktop Access its row is off and says where
 * to turn it on, and a device with a single Space and no Desktop reads that plainly in the intro.
 */
@Composable
internal fun NovaSpaceChooser(
    snapshot: PolarisSpaces,
    busy: Boolean,
    statusKnown: Boolean,
    error: String?,
    onChoose: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val backFocus = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    val blocked = NovaSpacesCopy.switchBlockedReason(snapshot)?.let { stringResource(it) }
    val only = NovaSpacesCopy.onlyPlace(snapshot)
    LaunchedEffect(Unit) {
        delay(NOVA_FIRST_FOCUS_SETTLE_MS)
        input.requestInputMode(InputMode.Keyboard)
        // The current row claims focus itself; when no row can, Back is the only target.
        if (!snapshot.canSwitch) runCatching { backFocus.requestFocus() }
    }
    Box(
        Modifier.fillMaxSize().background(colors.window).windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp).testTag("nova-space-chooser"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.nova_space_change), color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = when {
                    busy -> stringResource(R.string.nova_space_changing)
                    blocked != null -> blocked
                    only != null -> stringResource(R.string.nova_space_chooser_only_one, only.name)
                    else -> stringResource(R.string.nova_space_chooser_intro)
                },
                color = if (blocked != null && !busy) colors.textPrimary else colors.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.testTag("nova-space-chooser-caption"),
            )
            error?.let {
                Text(it, color = colors.textPrimary, fontSize = 14.sp, modifier = Modifier.testTag("nova-space-chooser-error"))
            }
            if (!statusKnown) {
                Text(stringResource(R.string.nova_space_status_unknown_hint), color = colors.textSecondary, fontSize = 13.sp)
            }
            val current = snapshot.selectedId
            // Always listed. Without Desktop Access the row is off and its caption says where to turn
            // it on; hiding it left a device that could not switch with nothing to explain why.
            val desktop = NovaSpacesCopy.desktopChoice(snapshot)
            NovaSteamChoiceRow(
                label = stringResource(R.string.nova_space_desktop),
                caption = stringResource(desktop.caption),
                enabled = desktop.enabled,
                onClick = { onChoose("desktop") },
                selected = current == "desktop",
                autoFocus = current == "desktop",
                modifier = Modifier.testTag("nova-space-choice-desktop"),
                describeCaption = !snapshot.desktopAllowed,
            )
            snapshot.spaces.forEach { space ->
                val reason = NovaSpacesCopy.openBlockedReason(space)
                NovaSteamChoiceRow(
                    label = space.name,
                    caption = NovaSpacesCopy.chooserCaption(
                        space.launcher,
                        when {
                            space.selected -> stringResource(R.string.nova_space_current)
                            reason != null -> stringResource(reason)
                            else -> null
                        },
                    ),
                    enabled = snapshot.canSwitch && space.state != "unavailable",
                    onClick = { onChoose(space.id) },
                    selected = space.selected,
                    badge = stringResource(NovaSpacesCopy.stateLabel(space.state)),
                    autoFocus = space.selected,
                    modifier = Modifier.testTag("nova-space-choice-${space.id}"),
                )
            }
            NovaActionButton(
                text = stringResource(R.string.nova_space_back),
                onClick = onBack,
                minHeight = 48.dp,
                modifier = Modifier.focusRequester(backFocus).testTag("nova-space-chooser-back"),
            )
            NovaControllerHintBar(
                hints = listOf(
                    NovaControllerHint(stringResource(R.string.nova_controller_hint_a), stringResource(R.string.nova_controller_hint_select)),
                    NovaControllerHint(stringResource(R.string.nova_controller_hint_b), stringResource(R.string.nova_controller_hint_back)),
                ),
                compact = true,
            )
        }
    }
}
