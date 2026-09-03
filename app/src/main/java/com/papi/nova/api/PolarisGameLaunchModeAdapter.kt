package com.papi.nova.api

import com.papi.nova.shared.polaris.model.PolarisGame

fun PolarisGame.resolveLaunchModeChoice(defaultToVirtualDisplay: Boolean, clientSettings: PolarisClientSettings? = null): PolarisGame.LaunchModeChoice {
    val contract = launchMode
    val headlessAllowed = isLaunchModeAvailable(PolarisGame.MODE_HEADLESS_STREAM, clientSettings)
    val virtualDisplayAllowed = isLaunchModeAvailable(PolarisGame.MODE_HOST_VIRTUAL_DISPLAY, clientSettings)
    val hostRequestedMode = clientSettings?.desired?.streamDisplayMode?.takeIf { it.isNotBlank() }
        ?: clientSettings?.effective?.streamDisplayMode?.takeIf { it.isNotBlank() }
        ?: ""
    val resolveAvailable = { mode: String ->
        PolarisGame.normalizeLaunchMode(mode)
            .takeIf { it.isNotBlank() && isLaunchModeAvailable(it, clientSettings) }
            .orEmpty()
    }
    val hostDefaultMode = resolveAvailable(hostRequestedMode)
    val fallbackMode = buildList {
        if (defaultToVirtualDisplay) add(PolarisGame.MODE_HOST_VIRTUAL_DISPLAY)
        add(PolarisGame.MODE_HEADLESS_STREAM)
        add(PolarisGame.MODE_DESKTOP_DISPLAY)
        add(PolarisGame.MODE_DESKTOP_TAKEOVER)
        add(PolarisGame.MODE_GAMESCOPE_STREAM)
        add(PolarisGame.MODE_WINDOWED_STREAM)
        add(PolarisGame.MODE_HOST_VIRTUAL_DISPLAY)
        contract?.allowedModes.orEmpty().forEach(::add)
    }.asSequence().map(resolveAvailable).firstOrNull { it.isNotBlank() }.orEmpty()
    val preferredMode = resolveAvailable(contract?.preferredMode.orEmpty()).ifBlank { fallbackMode }
    val recommendedMode = hostDefaultMode
        .ifBlank { resolveAvailable(contract?.recommendedMode.orEmpty()) }
        .ifBlank { preferredMode }
        .ifBlank { fallbackMode }
    val virtualAvailable = modeAvailability(clientSettings, PolarisGame.MODE_HOST_VIRTUAL_DISPLAY)
    val virtualUnavailableReason = clientSettings.launchModeUnavailableReason(
        PolarisGame.MODE_HOST_VIRTUAL_DISPLAY,
    )
    val virtualIntent = defaultToVirtualDisplay ||
        PolarisGame.normalizeLaunchMode(hostRequestedMode) == PolarisGame.MODE_HOST_VIRTUAL_DISPLAY ||
        PolarisGame.normalizeLaunchMode(contract?.preferredMode.orEmpty()) == PolarisGame.MODE_HOST_VIRTUAL_DISPLAY ||
        PolarisGame.normalizeLaunchMode(contract?.recommendedMode.orEmpty()) == PolarisGame.MODE_HOST_VIRTUAL_DISPLAY

    return PolarisGame.LaunchModeChoice(
        preferredMode = preferredMode,
        recommendedMode = recommendedMode,
        headlessAllowed = headlessAllowed,
        virtualDisplayAllowed = virtualDisplayAllowed,
        // A current host intentionally removes an unavailable mode from the
        // per-game allowed list. The catalog's typed false still needs to reach
        // the player as setup guidance instead of being hidden by that removal.
        virtualDisplayUnavailable = virtualIntent && virtualAvailable == false,
        virtualDisplayUnavailableReason = virtualUnavailableReason,
        hostDefaultMode = hostDefaultMode,
        hostModeReason = clientSettings?.desired?.streamDisplayModeReason?.takeIf { it.isNotBlank() } ?: clientSettings?.effective?.streamDisplayModeReason ?: ""
    )
}

/** True only when every authority supplied by the host accepts this mode now. */
fun PolarisGame.isLaunchModeAvailable(mode: String, clientSettings: PolarisClientSettings?): Boolean {
    val normalizedMode = PolarisGame.normalizeLaunchMode(mode)
    if (normalizedMode.isBlank()) return false

    val contractModes = launchMode?.allowedModes.orEmpty()
    if (contractModes.isNotEmpty() && launchMode?.allows(normalizedMode) != true) return false

    val catalogModes = clientSettings?.capabilities?.modes.orEmpty()
    if (catalogModes.isNotEmpty() && matchingModes(catalogModes, normalizedMode).none { it.available }) return false
    return true
}

/** Whether this available mode may be carried as a one-launch streamMode override. */
fun PolarisClientSettings?.isLaunchModeSessionOverridable(mode: String): Boolean {
    val normalizedMode = PolarisGame.normalizeLaunchMode(mode)
    if (normalizedMode.isBlank() || normalizedMode == PolarisGame.MODE_HEADLESS_DONGLE) return false
    val catalogModes = this?.capabilities?.modes.orEmpty()
    if (catalogModes.isEmpty()) return true
    return matchingModes(catalogModes, normalizedMode).any { it.available && it.sessionOverridable }
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

/** The host's current typed reason for rejecting this launch mode, when present. */
fun PolarisClientSettings?.launchModeUnavailableReason(mode: String): String {
    val modes = this?.capabilities?.modes ?: return ""
    return matchingModes(modes, mode).firstOrNull { !it.available }?.let {
        it.unavailableReason.ifBlank { it.reason }
    }.orEmpty()
}

private fun matchingModes(modes: List<PolarisClientSettings.ModeOption>, mode: String): List<PolarisClientSettings.ModeOption> {
    val normalizedMode = PolarisGame.normalizeLaunchMode(mode)
    return modes.filter { option -> PolarisGame.normalizeLaunchMode(option.value) == normalizedMode }
}
