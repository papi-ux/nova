package com.papi.nova.api

import com.papi.nova.shared.polaris.model.PolarisGame

fun PolarisGame.resolveLaunchModeChoice(defaultToVirtualDisplay: Boolean, clientSettings: PolarisClientSettings? = null): PolarisGame.LaunchModeChoice {
    val contract = launchMode
    val headlessAvailable = modeAvailability(clientSettings, PolarisGame.MODE_HEADLESS_STREAM)
    val virtualAvailable = modeAvailability(clientSettings, PolarisGame.MODE_HOST_VIRTUAL_DISPLAY)
    val headlessAllowed = (contract?.allows(PolarisGame.MODE_HEADLESS_STREAM) ?: true) && headlessAvailable != false
    val virtualDisplayAllowed = (contract?.allows(PolarisGame.MODE_HOST_VIRTUAL_DISPLAY) ?: true) && virtualAvailable != false
    val hostRequestedMode = clientSettings?.desired?.streamDisplayMode?.takeIf { it.isNotBlank() }
        ?: clientSettings?.effective?.streamDisplayMode?.takeIf { it.isNotBlank() }
        ?: ""
    val hostDefaultMode = hostRequestedMode.takeIf { it.isNotBlank() }?.let {
        PolarisGame.resolveLaunchMode(it, headlessAllowed, virtualDisplayAllowed)
    } ?: ""
    val fallbackMode = if (defaultToVirtualDisplay && virtualDisplayAllowed) PolarisGame.MODE_HOST_VIRTUAL_DISPLAY else PolarisGame.MODE_HEADLESS_STREAM
    val preferredMode = PolarisGame.resolveLaunchMode(contract?.preferredMode?.takeIf { it.isNotBlank() } ?: fallbackMode, headlessAllowed, virtualDisplayAllowed)
    val recommendedMode = PolarisGame.resolveLaunchMode(hostDefaultMode.takeIf { it.isNotBlank() } ?: contract?.recommendedMode?.takeIf { it.isNotBlank() } ?: preferredMode, headlessAllowed, virtualDisplayAllowed)
    val virtualUnavailableReason = modeUnavailableReason(clientSettings, PolarisGame.MODE_HOST_VIRTUAL_DISPLAY)

    return PolarisGame.LaunchModeChoice(
        preferredMode = preferredMode,
        recommendedMode = recommendedMode,
        headlessAllowed = headlessAllowed,
        virtualDisplayAllowed = virtualDisplayAllowed,
        virtualDisplayUnavailable = (contract?.allows(PolarisGame.MODE_HOST_VIRTUAL_DISPLAY) ?: defaultToVirtualDisplay) && virtualAvailable == false,
        virtualDisplayUnavailableReason = virtualUnavailableReason,
        hostDefaultMode = hostDefaultMode,
        hostModeReason = clientSettings?.desired?.streamDisplayModeReason?.takeIf { it.isNotBlank() } ?: clientSettings?.effective?.streamDisplayModeReason ?: ""
    )
}

// Canonical ids make alias sets unnecessary: normalizeLaunchMode maps every
// legacy spelling onto one registry id, so catalog matching is plain
// id-equality after normalization. The old alias sets deliberately lumped
// desktop_display and windowed_stream in with "headless", which is how a host
// reporting "GPU-native available" used to read as "headless available".
private fun modeAvailability(clientSettings: PolarisClientSettings?, mode: String): Boolean? {
    val modes = clientSettings?.capabilities?.modes ?: return null
    val matches = matchingModes(modes, mode)
    if (matches.isEmpty()) return null
    return matches.any { it.available }
}

private fun modeUnavailableReason(clientSettings: PolarisClientSettings?, mode: String): String {
    val modes = clientSettings?.capabilities?.modes ?: return ""
    return matchingModes(modes, mode).firstOrNull { !it.available }?.reason.orEmpty()
}

private fun matchingModes(modes: List<PolarisClientSettings.ModeOption>, mode: String): List<PolarisClientSettings.ModeOption> {
    val normalizedMode = PolarisGame.normalizeLaunchMode(mode)
    return modes.filter { option -> PolarisGame.normalizeLaunchMode(option.value) == normalizedMode }
}
