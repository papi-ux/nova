package com.papi.nova.api

import com.papi.nova.shared.polaris.model.PolarisGame

fun PolarisGame.resolveLaunchModeChoice(defaultToVirtualDisplay: Boolean, clientSettings: PolarisClientSettings? = null): PolarisGame.LaunchModeChoice {
    val contract = launchMode
    val headlessAvailable = modeAvailability(clientSettings, "headless")
    val virtualAvailable = modeAvailability(clientSettings, "virtual_display")
    val headlessAllowed = (contract?.allows("headless") ?: true) && headlessAvailable != false
    val virtualDisplayAllowed = (contract?.allows("virtual_display") ?: true) && virtualAvailable != false
    val hostDefaultMode = PolarisGame.resolveLaunchMode(
        clientSettings?.desired?.streamDisplayMode?.takeIf { it.isNotBlank() } ?: clientSettings?.effective?.streamDisplayMode ?: "",
        headlessAllowed,
        virtualDisplayAllowed
    )
    val fallbackMode = if (defaultToVirtualDisplay && virtualDisplayAllowed) "virtual_display" else "headless"
    val preferredMode = PolarisGame.resolveLaunchMode(contract?.preferredMode?.takeIf { it.isNotBlank() } ?: fallbackMode, headlessAllowed, virtualDisplayAllowed)
    val recommendedMode = PolarisGame.resolveLaunchMode(hostDefaultMode.takeIf { it.isNotBlank() } ?: contract?.recommendedMode?.takeIf { it.isNotBlank() } ?: preferredMode, headlessAllowed, virtualDisplayAllowed)
    val virtualUnavailableReason = modeUnavailableReason(clientSettings, "virtual_display")

    return PolarisGame.LaunchModeChoice(
        preferredMode = preferredMode,
        recommendedMode = recommendedMode,
        headlessAllowed = headlessAllowed,
        virtualDisplayAllowed = virtualDisplayAllowed,
        virtualDisplayUnavailable = (contract?.allows("virtual_display") ?: defaultToVirtualDisplay) && virtualAvailable == false,
        virtualDisplayUnavailableReason = virtualUnavailableReason,
        hostDefaultMode = hostDefaultMode,
        hostModeReason = clientSettings?.desired?.streamDisplayModeReason?.takeIf { it.isNotBlank() } ?: clientSettings?.effective?.streamDisplayModeReason ?: ""
    )
}

private val HEADLESS_MODE_ALIASES = setOf("headless", PolarisClientSettings.MODE_HEADLESS_STREAM, PolarisClientSettings.MODE_DESKTOP_DISPLAY, PolarisClientSettings.MODE_GPU_NATIVE_TEST, "host_display")
private val VIRTUAL_DISPLAY_MODE_ALIASES = setOf("virtual_display", PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY)

private fun modeAvailability(clientSettings: PolarisClientSettings?, mode: String): Boolean? {
    val modes = clientSettings?.capabilities?.modes ?: return null
    val aliases = aliasesForMode(mode)
    val matches = modes.filter { it.value in aliases }
    if (matches.isEmpty()) return null
    return matches.any { it.available }
}

private fun modeUnavailableReason(clientSettings: PolarisClientSettings?, mode: String): String {
    val modes = clientSettings?.capabilities?.modes ?: return ""
    val aliases = aliasesForMode(mode)
    return modes.firstOrNull { it.value in aliases && !it.available }?.reason.orEmpty()
}

private fun aliasesForMode(mode: String): Set<String> {
    return when (PolarisGame.normalizeLaunchMode(mode)) {
        "virtual_display" -> VIRTUAL_DISPLAY_MODE_ALIASES
        else -> HEADLESS_MODE_ALIASES
    }
}
