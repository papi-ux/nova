package com.papi.nova.preferences

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaActionSurface
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.panel.NovaCurrentMark
import com.papi.nova.ui.panel.NovaPageScope
import com.papi.nova.ui.panel.NovaPanelButtonPair
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.novaClickable
import com.papi.nova.ui.panel.novaFocusRing
import com.papi.nova.ui.panel.novaPanelType
import com.papi.nova.utils.AndroidDisplayCandidateAdapter
import com.papi.nova.utils.AndroidDisplayRolePlan
import com.papi.nova.utils.AndroidStreamDisplayTarget

private val DisplayRoleRowShape = RoundedCornerShape(NovaRadius.row)

/**
 * The display role composer as a page: which display streams and which shows the companion
 * controls, previewed before anything changes. It opens on the route in use, Follow or the
 * display that streams, and Apply hands the new target to [SettingsPage.DisplayRole.onApply]
 * after the page has left. B and the page header leave without applying, as Cancel did.
 */
@Composable
internal fun NovaPageScope.NovaDisplayRolePage(page: SettingsPage.DisplayRole) {
    val displays = rememberAndroidDisplayRoleSpecs()
    var pendingTarget by rememberSaveable(page.currentTarget) { mutableStateOf(page.currentTarget) }
    val roleState = remember(displays, page.currentTarget, pendingTarget) {
        AndroidDisplayRolePlan.build(
            displays = displays,
            defaultDisplayId = Display.DEFAULT_DISPLAY,
            currentTarget = page.currentTarget,
            pendingTarget = pendingTarget,
        )
    }
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val streamDisplayId = roleState.pending.assignments
        .firstOrNull { it.role == AndroidDisplayRolePlan.Role.STREAM }?.display?.displayId

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "summary") {
            Text(
                text = stringResource(R.string.summary_display_role_composer),
                style = type.caption,
                color = colors.textSecondary,
            )
        }
        item(key = "route") { NovaDisplayRoleRouteSummary(roleState) }
        item(key = "follow") {
            val following = roleState.pending.followingSafeDefault
            NovaDisplayRoleActionButton(
                label = stringResource(R.string.display_role_follow),
                supporting = stringResource(R.string.display_role_follow_supporting),
                accessibilityDescription = stringResource(R.string.display_role_follow_action_description),
                enabled = true,
                selected = following,
                onClick = { if (isTop) pendingTarget = AndroidStreamDisplayTarget.AUTO },
                modifier = if (following || streamDisplayId == null) Modifier.novaInitialFocus() else Modifier,
            )
        }
        items(roleState.pending.assignments, key = { it.display.displayId }) { pendingAssignment ->
            val currentRole = roleState.current.assignments
                .firstOrNull { it.display.displayId == pendingAssignment.display.displayId }
                ?.role
                ?: AndroidDisplayRolePlan.Role.AVAILABLE
            val target = targetForDisplay(display = pendingAssignment.display, displays = displays)
            val initial = !roleState.pending.followingSafeDefault && pendingAssignment.display.displayId == streamDisplayId
            NovaDisplayRoleCard(
                currentRole = currentRole,
                pendingAssignment = pendingAssignment,
                enabled = target != null,
                onClick = { if (isTop) target?.let { pendingTarget = it } },
                modifier = if (initial) Modifier.novaInitialFocus() else Modifier,
            )
        }
        roleRecoveryMessageRes(roleState.pending.recovery)?.let { message ->
            item(key = "recovery") {
                Text(text = stringResource(message), style = type.caption, color = colors.warning)
            }
        }
        item(key = "next-stream") {
            Text(
                text = stringResource(R.string.display_role_next_stream),
                style = type.caption,
                color = colors.textSecondary,
            )
        }
        item(key = "actions") {
            NovaDisplayRoleComposerActions(
                roleState = roleState,
                onSwap = {
                    if (isTop) AndroidDisplayRolePlan.swapTarget(roleState.pending)?.let { pendingTarget = it }
                },
                onApply = {
                    if (isTop) {
                        val target = roleState.pending.target
                        if (!panel.pop()) panel.close()
                        page.onApply(target)
                    }
                },
            )
        }
    }
}

@Composable
private fun NovaDisplayRoleRouteSummary(roleState: AndroidDisplayRolePlan.State) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val current = routeSummary(roleState.current)
    val pending = routeSummary(roleState.pending)
    Column(verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs)) {
        Text(
            text = stringResource(R.string.display_role_current, current),
            style = type.caption,
            color = colors.textSecondary,
        )
        Text(
            text = stringResource(R.string.display_role_pending, pending),
            style = type.rowTitle,
            fontWeight = FontWeight.SemiBold,
            color = if (roleState.hasChanges) colors.accent else colors.textPrimary,
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

/**
 * One display and the role it will take. The display that will stream is the chosen one: it
 * carries the check, a SemiBold name and selected semantics (R9), and fills and borders only ever
 * mean focus. A display the routing cannot name stays focusable and says why.
 */
@Composable
internal fun NovaDisplayRoleCard(
    currentRole: AndroidDisplayRolePlan.Role,
    pendingAssignment: AndroidDisplayRolePlan.Assignment,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    val lines = buildList {
        add(pendingRoleLabel)
        add(
            stringResource(
                R.string.display_role_resolution_refresh,
                assignment.display.width,
                assignment.display.height,
                assignment.display.refreshRateHz,
            ),
        )
        add(stringResource(R.string.display_role_card_current, currentRoleLabel))
        if (!enabled) add(stringResource(R.string.display_role_unrepresentable))
    }
    NovaDisplayRoleChoice(
        title = assignment.display.label,
        lines = lines,
        description = description,
        selected = assignment.role == AndroidDisplayRolePlan.Role.STREAM,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    )
}

/** Follow: Nova's safe default routing, chosen like a display. */
@Composable
internal fun NovaDisplayRoleActionButton(
    label: String,
    supporting: String,
    accessibilityDescription: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NovaDisplayRoleChoice(
        title = label,
        lines = listOf(supporting),
        description = accessibilityDescription,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * A radio choice on the composer page: the one focus look, the trailing check for the choice in
 * effect, and text that wraps rather than cuts.
 */
@Composable
private fun NovaDisplayRoleChoice(
    title: String,
    lines: List<String>,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val selectionState = stringResource(
        if (selected) {
            R.string.display_role_selection_state_selected
        } else {
            R.string.display_role_selection_state_not_selected
        },
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .clip(DisplayRoleRowShape)
            .novaFocusRing(DisplayRoleRowShape)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = selectionState
                this.selected = selected
            }
            .novaClickable(enabled = enabled, role = Role.RadioButton, focusableWhenDisabled = true, onClick = onClick)
            .alpha(if (enabled) 1f else NovaPanelMetrics.DisabledAlpha)
            .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs)) {
            Text(
                text = title,
                style = type.rowTitle,
                fontWeight = if (selected) FontWeight.SemiBold else type.rowTitle.fontWeight,
                color = colors.textPrimary,
            )
            lines.forEach { line -> Text(text = line, style = type.caption, color = colors.textSecondary) }
        }
        if (selected) {
            NovaCurrentMark()
        } else {
            // The check's room, so the text does not move when the choice does.
            Spacer(Modifier.size(NovaPanelMetrics.CurrentMarkSize))
        }
    }
}

/**
 * Swap and Apply, side by side while both fit and stacked at full width otherwise, so neither
 * label is cut at any width or font scale. There is no Cancel: B and the page header leave.
 */
@Composable
internal fun NovaDisplayRoleComposerActions(
    roleState: AndroidDisplayRolePlan.State,
    onSwap: () -> Unit,
    onApply: () -> Unit,
) {
    NovaPanelButtonPair(
        first = {
            NovaDisplayRoleActionSurface(
                text = stringResource(R.string.display_role_swap),
                enabled = roleState.canSwap,
                primary = false,
                onClick = onSwap,
            )
        },
        second = {
            NovaDisplayRoleActionSurface(
                text = stringResource(R.string.display_role_apply),
                enabled = roleState.canApply,
                primary = true,
                onClick = onApply,
            )
        },
        modifier = Modifier.padding(top = NovaPanelMetrics.SpaceSm),
    )
}

/** A page button that can be disabled, as Swap and Apply are when there is nothing to do. */
@Composable
private fun NovaDisplayRoleActionSurface(text: String, enabled: Boolean, primary: Boolean, onClick: () -> Unit) {
    NovaActionSurface(
        onClick = onClick,
        enabled = enabled,
        primary = primary,
        contentDescription = text,
        minHeight = NovaPanelMetrics.ButtonMinHeight,
        cornerRadius = NovaRadius.hero,
        contentPadding = PaddingValues(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
    ) { contentColor, _ ->
        Text(text = text, style = novaPanelType.value, color = contentColor)
    }
}

@Composable
private fun roleLabel(role: AndroidDisplayRolePlan.Role): String = when (role) {
    AndroidDisplayRolePlan.Role.STREAM -> stringResource(R.string.display_role_stream)
    AndroidDisplayRolePlan.Role.COMPANION -> stringResource(R.string.display_role_companion)
    AndroidDisplayRolePlan.Role.AVAILABLE -> stringResource(R.string.display_role_available)
}

private fun roleRecoveryMessageRes(recovery: AndroidDisplayRolePlan.Recovery): Int? = when (recovery) {
    AndroidDisplayRolePlan.Recovery.NONE -> null
    AndroidDisplayRolePlan.Recovery.SINGLE_DISPLAY -> R.string.display_role_recovery_single
    AndroidDisplayRolePlan.Recovery.REQUESTED_DISPLAY_UNAVAILABLE -> R.string.display_role_recovery_unavailable
    AndroidDisplayRolePlan.Recovery.UNKNOWN_TARGET -> R.string.display_role_recovery_unknown
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
