package com.papi.nova.preferences

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.novaFocusMotion
import com.papi.nova.utils.AndroidDisplayCandidateAdapter
import com.papi.nova.utils.AndroidDisplayRolePlan
import com.papi.nova.utils.AndroidStreamDisplayTarget

private val DisplayRoleCardShape = RoundedCornerShape(NovaRadius.row)

@Composable
internal fun NovaDisplayRoleComposerDialog(
    definition: NovaSettingDefinition,
    state: NovaSettingsUiState,
    onDismiss: () -> Unit,
    onSave: (NovaSettingDefinition, NovaSettingValue) -> Unit,
) {
    val displays = rememberAndroidDisplayRoleSpecs()
    val currentTarget = state.stringValue(definition)
    var pendingTarget by rememberSaveable(definition.key, currentTarget) {
        mutableStateOf(currentTarget)
    }
    val roleState = remember(displays, currentTarget, pendingTarget) {
        AndroidDisplayRolePlan.build(
            displays = displays,
            defaultDisplayId = Display.DEFAULT_DISPLAY,
            currentTarget = currentTarget,
            pendingTarget = pendingTarget,
        )
    }

    NovaSelectDialogShell(
        onDismissRequest = onDismiss,
        confirmButton = {
            NovaDisplayRoleComposerActions(
                roleState = roleState,
                onSwap = {
                    AndroidDisplayRolePlan.swapTarget(roleState.pending)?.let {
                        pendingTarget = it
                    }
                },
                onApply = {
                    onSave(
                        definition,
                        NovaSettingValue.StringValue(roleState.pending.target),
                    )
                },
                onDismiss = onDismiss,
            )
        },
        dismissButton = {},
        title = { Text(stringResource(R.string.title_display_role_composer)) },
        text = {
            NovaDisplayRoleComposerBody(
                roleState = roleState,
                displays = displays,
                onPendingTarget = { pendingTarget = it },
            )
        },
    )
}

@Composable
internal fun NovaDisplayRoleComposerLegacyPanel(
    currentTarget: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    val displays = rememberAndroidDisplayRoleSpecs()
    var pendingTarget by rememberSaveable("legacy-display-roles", currentTarget) {
        mutableStateOf(currentTarget)
    }
    val roleState = remember(displays, currentTarget, pendingTarget) {
        AndroidDisplayRolePlan.build(
            displays = displays,
            defaultDisplayId = Display.DEFAULT_DISPLAY,
            currentTarget = currentTarget,
            pendingTarget = pendingTarget,
        )
    }
    val surfaces = LocalNovaLibrarySurfaces.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DisplayRoleCardShape)
            .background(surfaces.panel.copy(alpha = 1f))
            .border(1.dp, surfaces.panelBorder, DisplayRoleCardShape)
            .padding(18.dp),
    ) {
        Text(stringResource(R.string.title_display_role_composer))
        Spacer(Modifier.height(12.dp))
        NovaDisplayRoleComposerBody(
            modifier = Modifier.weight(1f, fill = false),
            roleState = roleState,
            displays = displays,
            maxHeight = 170.dp,
            onPendingTarget = { pendingTarget = it },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            NovaDisplayRoleComposerActions(
                roleState = roleState,
                onSwap = {
                    AndroidDisplayRolePlan.swapTarget(roleState.pending)?.let {
                        pendingTarget = it
                    }
                },
                onApply = { onApply(roleState.pending.target) },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun NovaDisplayRoleComposerBody(
    modifier: Modifier = Modifier,
    roleState: AndroidDisplayRolePlan.State,
    displays: List<AndroidDisplayRolePlan.DisplaySpec>,
    maxHeight: Dp = 620.dp,
    onPendingTarget: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .heightIn(max = maxHeight)
            .clipToBounds(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.summary_display_role_composer),
                color = LocalNovaComposeColors.current.textSecondary,
                fontSize = 14.sp,
            )
        }
        item {
            NovaDisplayRoleRouteSummary(roleState)
        }
        item {
            NovaDisplayRoleFollowAction(
                selected = roleState.pending.followingSafeDefault,
                onClick = { onPendingTarget(AndroidStreamDisplayTarget.AUTO) },
            )
        }
        items(roleState.pending.assignments, key = { it.display.displayId }) { pendingAssignment ->
            val currentRole = roleState.current.assignments
                .firstOrNull { it.display.displayId == pendingAssignment.display.displayId }
                ?.role
                ?: AndroidDisplayRolePlan.Role.AVAILABLE
            val target = targetForDisplay(
                display = pendingAssignment.display,
                displays = displays,
            )
            NovaDisplayRoleCard(
                currentRole = currentRole,
                pendingAssignment = pendingAssignment,
                enabled = target != null,
                onClick = { target?.let(onPendingTarget) },
            )
        }
        item {
            roleRecoveryMessage(roleState.pending.recovery)?.let { message ->
                Text(
                    text = message,
                    color = LocalNovaComposeColors.current.textSecondary,
                    fontSize = 13.sp,
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.display_role_next_stream),
                color = LocalNovaComposeColors.current.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun NovaDisplayRoleRouteSummary(roleState: AndroidDisplayRolePlan.State) {
    val colors = LocalNovaComposeColors.current
    val current = routeSummary(roleState.current)
    val pending = routeSummary(roleState.pending)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.display_role_current, current),
            color = colors.textSecondary,
            fontSize = 13.sp,
        )
        Text(
            text = stringResource(R.string.display_role_pending, pending),
            color = if (roleState.hasChanges) colors.accent else colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun routeSummary(route: AndroidDisplayRolePlan.Route): String {
    if (route.followingSafeDefault) return stringResource(R.string.display_role_follow)
    val streamLabel = route.stream?.label ?: stringResource(R.string.display_role_unavailable)
    val companionLabel = route.companion?.label ?: stringResource(R.string.display_role_none)
    return stringResource(R.string.display_role_route_summary, streamLabel, companionLabel)
}

@Composable
private fun NovaDisplayRoleFollowAction(
    selected: Boolean,
    onClick: () -> Unit,
) {
    NovaDisplayRoleActionButton(
        label = stringResource(R.string.display_role_follow),
        supporting = stringResource(R.string.display_role_follow_supporting),
        accessibilityDescription = stringResource(R.string.display_role_follow_action_description),
        enabled = true,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
internal fun NovaDisplayRoleCard(
    currentRole: AndroidDisplayRolePlan.Role,
    pendingAssignment: AndroidDisplayRolePlan.Assignment,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val assignment = pendingAssignment
    val pendingRoleLabel = roleLabel(assignment.role)
    val currentRoleLabel = roleLabel(currentRole)
    val description = stringResource(
        if (enabled) {
            R.string.display_role_card_action_description
        } else {
            R.string.display_role_card_unavailable_description
        },
        assignment.display.label,
        pendingRoleLabel,
    )
    var focused by remember { mutableStateOf(false) }
    val selected = assignment.role == AndroidDisplayRolePlan.Role.STREAM
    val selectionState = stringResource(
        if (selected) {
            R.string.display_role_selection_state_selected
        } else {
            R.string.display_role_selection_state_not_selected
        },
    )
    // This component had the split right before the others did; it just spelled the
    // selected tint as its own alpha rather than as the shared token, and put selection
    // ahead of focus so a focused selected role lost its focus fill.
    val background = when {
        focused -> surfaces.selectedControl
        selected -> colors.accentSurface
        else -> surfaces.control.copy(alpha = 0.76f * LocalNovaMenuOpacityScale.current)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DisplayRoleCardShape)
            .novaFocusMotion(focused = focused, pressed = false)
            .background(background)
            .border(
                // Hue cannot separate these two: focusRing is defined as accent. Selection
                // is drawn at 0.72 alpha, the way NovaSelectableChip already drew it, and
                // width carries the rest of the distinction.
                width = when {
                    focused -> 3.dp
                    selected -> 2.dp
                    else -> 1.dp
                },
                color = when {
                    focused -> surfaces.focusRing
                    selected -> colors.accent.copy(alpha = 0.72f)
                    else -> surfaces.tileBorder
                },
                shape = DisplayRoleCardShape,
            )
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .focusable(enabled)
            .semantics {
                contentDescription = description
                stateDescription = selectionState
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = assignment.display.label,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                text = pendingRoleLabel,
                color = if (selected) colors.accent else colors.textSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
        Text(
            text = stringResource(
                R.string.display_role_resolution_refresh,
                assignment.display.width,
                assignment.display.height,
                assignment.display.refreshRateHz,
            ),
            color = colors.textSecondary,
            fontSize = 13.sp,
        )
        Text(
            text = stringResource(R.string.display_role_card_current, currentRoleLabel),
            color = colors.textSecondary,
            fontSize = 12.sp,
        )
        if (!enabled) {
            Text(
                text = stringResource(R.string.display_role_unrepresentable),
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
internal fun NovaDisplayRoleActionButton(
    label: String,
    supporting: String,
    accessibilityDescription: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    var focused by remember { mutableStateOf(false) }
    val selectionState = stringResource(
        if (selected) {
            R.string.display_role_selection_state_selected
        } else {
            R.string.display_role_selection_state_not_selected
        },
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DisplayRoleCardShape)
            .novaFocusMotion(focused = focused, pressed = false)
            .background(
                when {
                    focused -> surfaces.selectedControl
                    selected -> colors.accentSurface
                    else -> surfaces.control.copy(alpha = 0.70f * LocalNovaMenuOpacityScale.current)
                },
            )
            // The border was the plainest statement of the problem in the app: a selected
            // card wore the focus ring, at focus width, while focus was somewhere else. The
            // ring now means focus and nothing else, and selection is drawn in the accent.
            .border(
                width = when {
                    focused -> 3.dp
                    selected -> 2.dp
                    else -> 1.dp
                },
                color = when {
                    focused -> surfaces.focusRing
                    selected -> colors.accent.copy(alpha = 0.72f)
                    else -> surfaces.tileBorder
                },
                shape = DisplayRoleCardShape,
            )
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .focusable(enabled)
            .semantics {
                contentDescription = accessibilityDescription
                stateDescription = selectionState
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = if (selected) colors.accent else colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = supporting,
            color = colors.textSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
internal fun NovaDisplayRoleComposerActions(
    roleState: AndroidDisplayRolePlan.State,
    onSwap: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(
            enabled = roleState.canSwap,
            onClick = onSwap,
        ) {
            Text(stringResource(R.string.display_role_swap))
        }
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.display_role_cancel))
        }
        TextButton(
            enabled = roleState.canApply,
            onClick = onApply,
        ) {
            Text(stringResource(R.string.display_role_apply))
        }
    }
}

@Composable
private fun roleLabel(role: AndroidDisplayRolePlan.Role): String = when (role) {
    AndroidDisplayRolePlan.Role.STREAM -> stringResource(R.string.display_role_stream)
    AndroidDisplayRolePlan.Role.COMPANION -> stringResource(R.string.display_role_companion)
    AndroidDisplayRolePlan.Role.AVAILABLE -> stringResource(R.string.display_role_available)
}

@Composable
private fun roleRecoveryMessage(recovery: AndroidDisplayRolePlan.Recovery): String? = when (recovery) {
    AndroidDisplayRolePlan.Recovery.NONE -> null
    AndroidDisplayRolePlan.Recovery.SINGLE_DISPLAY -> stringResource(R.string.display_role_recovery_single)
    AndroidDisplayRolePlan.Recovery.REQUESTED_DISPLAY_UNAVAILABLE ->
        stringResource(R.string.display_role_recovery_unavailable)
    AndroidDisplayRolePlan.Recovery.UNKNOWN_TARGET -> stringResource(R.string.display_role_recovery_unknown)
}

private fun targetForDisplay(
    display: AndroidDisplayRolePlan.DisplaySpec,
    displays: List<AndroidDisplayRolePlan.DisplaySpec>,
): String? {
    if (display.isDefault) return AndroidStreamDisplayTarget.PRIMARY
    val firstExternal = displays.firstOrNull { !it.isDefault }
    if (display.displayId == firstExternal?.displayId) return AndroidStreamDisplayTarget.EXTERNAL
    val largest = displays.maxWithOrNull(
        compareBy<AndroidDisplayRolePlan.DisplaySpec> { it.pixelArea }
            .thenByDescending { if (it.isDefault) 0 else 1 },
    )
    return AndroidStreamDisplayTarget.LARGEST.takeIf { largest?.displayId == display.displayId }
}

@Composable
private fun rememberAndroidDisplayRoleSpecs(): List<AndroidDisplayRolePlan.DisplaySpec> {
    val context = LocalContext.current
    val displayManager = remember(context) {
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }
    var displayGeneration by remember { mutableIntStateOf(0) }
    DisposableEffect(displayManager) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                displayGeneration += 1
            }

            override fun onDisplayChanged(displayId: Int) {
                displayGeneration += 1
            }

            override fun onDisplayRemoved(displayId: Int) {
                displayGeneration += 1
            }
        }
        displayManager.registerDisplayListener(listener, null)
        onDispose {
            displayManager.unregisterDisplayListener(listener)
        }
    }

    return remember(displayManager, displayGeneration) {
        displayManager.displays
            .map { display ->
                display.toRoleSpec(
                    context = context,
                    candidate = AndroidDisplayCandidateAdapter.from(display),
                )
            }
    }
}

@Suppress("DEPRECATION")
private fun Display.toRoleSpec(
    context: Context,
    candidate: AndroidStreamDisplayTarget.Candidate,
): AndroidDisplayRolePlan.DisplaySpec {
    val refreshRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val currentMode = mode
        currentMode.refreshRate.takeIf { it > 0f } ?: this.refreshRate
    } else {
        this.refreshRate
    }
    val default = displayId == Display.DEFAULT_DISPLAY
    val fallbackLabel = context.getString(
        if (default) R.string.display_role_primary_display else R.string.display_role_external_display,
    )
    return AndroidDisplayRolePlan.DisplaySpec(
        displayId = displayId,
        label = name.takeIf { it.isNotBlank() } ?: fallbackLabel,
        width = candidate.width,
        height = candidate.height,
        refreshRateHz = refreshRate,
        isDefault = default,
    )
}
