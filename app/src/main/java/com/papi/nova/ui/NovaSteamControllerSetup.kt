package com.papi.nova.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.papi.nova.R
import com.papi.nova.binding.input.driver.UsbDriverService.SteamControllerUsbAccessStatus
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.panel.NovaPageScope
import com.papi.nova.ui.panel.NovaPanelButton
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.novaScrollEdgeFade

/** What Set Up Steam Controller's button does. */
internal enum class NovaSteamControllerSetupAction {
    /** Asks Android for USB access, with its usual prompt. Only this button asks (#354). */
    REQUEST_ACCESS,

    /** Reads the status again, which never asks for anything. */
    CHECK_AGAIN,

    /** Opens Settings, where Input & Controllers has USB controller driver. It changes nothing itself. */
    OPEN_SETTINGS,
}

/**
 * Set Up Steam Controller's words for one status, and its one button: always there, so focus has
 * one place to rest as the status changes. [settled] puts the line in the accent.
 */
@Immutable
internal data class NovaSteamControllerSetupUi(
    @StringRes val message: Int,
    val action: NovaSteamControllerSetupAction,
    @StringRes val actionLabel: Int,
    val settled: Boolean = false,
)

/**
 * The words for [status], null until the page first reads it. Only a receiver that needs access, or
 * whose request failed, offers the request, so reading a status never shows Android's prompt, and
 * while the prompt is up the button only reads again. Access allowed says only that: it is not a
 * report that every control works.
 */
internal fun steamControllerSetupUi(status: SteamControllerUsbAccessStatus?): NovaSteamControllerSetupUi {
    val checkAgain = NovaSteamControllerSetupAction.CHECK_AGAIN
    val checkAgainLabel = R.string.nova_steam_controller_setup_check_again
    return when (status) {
        null -> NovaSteamControllerSetupUi(R.string.nova_steam_controller_setup_checking, checkAgain, checkAgainLabel)
        SteamControllerUsbAccessStatus.USB_DISABLED -> NovaSteamControllerSetupUi(
            R.string.nova_steam_controller_setup_usb_disabled,
            NovaSteamControllerSetupAction.OPEN_SETTINGS,
            R.string.nova_steam_controller_setup_open_settings,
        )
        SteamControllerUsbAccessStatus.OWNER_INACTIVE ->
            NovaSteamControllerSetupUi(R.string.nova_steam_controller_setup_not_ready, checkAgain, checkAgainLabel)
        SteamControllerUsbAccessStatus.NO_DEVICE ->
            NovaSteamControllerSetupUi(R.string.nova_steam_controller_setup_no_device, checkAgain, checkAgainLabel)
        SteamControllerUsbAccessStatus.AMBIGUOUS ->
            NovaSteamControllerSetupUi(R.string.nova_steam_controller_setup_ambiguous, checkAgain, checkAgainLabel)
        SteamControllerUsbAccessStatus.ANDROID_GAMEPAD ->
            NovaSteamControllerSetupUi(R.string.nova_steam_controller_setup_android_gamepad, checkAgain, checkAgainLabel)
        SteamControllerUsbAccessStatus.NEEDS_PERMISSION -> NovaSteamControllerSetupUi(
            R.string.nova_steam_controller_setup_needs_permission,
            NovaSteamControllerSetupAction.REQUEST_ACCESS,
            R.string.nova_steam_controller_setup_allow,
        )
        SteamControllerUsbAccessStatus.REQUESTING_PERMISSION ->
            NovaSteamControllerSetupUi(R.string.nova_steam_controller_setup_requesting, checkAgain, checkAgainLabel)
        SteamControllerUsbAccessStatus.PERMISSION_GRANTED -> NovaSteamControllerSetupUi(
            R.string.nova_steam_controller_setup_granted, checkAgain, checkAgainLabel, settled = true,
        )
        SteamControllerUsbAccessStatus.NATIVE_INPUT_ACTIVE -> NovaSteamControllerSetupUi(
            R.string.nova_steam_controller_setup_native_input, checkAgain, checkAgainLabel, settled = true,
        )
        SteamControllerUsbAccessStatus.REQUEST_FAILED -> NovaSteamControllerSetupUi(
            R.string.nova_steam_controller_setup_request_failed,
            NovaSteamControllerSetupAction.REQUEST_ACCESS,
            R.string.nova_panel_try_again,
        )
    }
}

/**
 * Set Up Steam Controller, pushed from System: one line saying where the receiver stands, its next
 * step and Close. B returns to System as from any page there. Open Settings leaves the library, so
 * the panel closes first, as System's own rows that leave do.
 */
@Composable
internal fun NovaPageScope.NovaSteamControllerSetupPage(
    ui: NovaSteamControllerSetupUi,
    onAction: (NovaSteamControllerSetupAction) -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .novaScrollEdgeFade(scroll)
            .verticalScroll(scroll)
            .padding(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
    ) {
        NovaPanelStatusText(
            caption = stringResource(ui.message),
            captionColor = if (ui.settled) colors.accent else null,
            announce = true,
        )
        NovaPanelButton(
            text = stringResource(ui.actionLabel),
            primary = true,
            onClick = {
                val action = ui.action
                if (isTop) {
                    if (action == NovaSteamControllerSetupAction.OPEN_SETTINGS) closeThen { onAction(action) } else onAction(action)
                }
            },
            modifier = Modifier.fillMaxWidth().novaInitialFocus(),
        )
        NovaPanelButton(
            text = stringResource(R.string.nova_panel_close),
            onClick = { if (isTop) panel.pop() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
