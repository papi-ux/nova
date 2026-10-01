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
    val preferredMode = resolveAvailable(contract?.fixedLaunchMode ?: contract?.preferredMode.orEmpty()).ifBlank { fallbackMode }
    // The host's configured display normally wins, because it is a later and more specific answer
    // than an app's stored preference. An entry that states it does not follow the host default is
    // the exception: the Desktop entry is the desktop, so a host configured to make screens for
    // games would otherwise open it on one of those without anybody asking.
    val contractRecommendedMode = resolveAvailable(contract?.fixedLaunchMode ?: contract?.recommendedMode.orEmpty())
    val recommendedMode = if (contract?.followsEffectiveHostDefault == false && contractRecommendedMode.isNotBlank()) {
        contractRecommendedMode
    } else {
        hostDefaultMode
            .ifBlank { contractRecommendedMode }
            .ifBlank { preferredMode }
            .ifBlank { fallbackMode }
    }
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

    // App-specific refusal outranks the host's general mode catalog, including worker modes.
    val contract = launchMode
    if (contract?.launchAs != null) {
        if (!contract.hasKnownLaunchAs || contract.launchAsAvailable != true) return false
        if (contract.fixedLaunchMode != null && normalizedMode != contract.fixedLaunchMode) return false
    }

    val contractModes = launchMode?.allowedModes.orEmpty()
    if (contractModes.isNotEmpty() && launchMode?.allows(normalizedMode) != true) return false

    // A Space's own worker advertises this mode in its entry contract. The desktop
    // catalog describes another process and may have no gamescope capture at all.
    // Fresh /spaces identity/openability and session guards still run before launch.
    if (isSpaceWorkerLaunchMode(normalizedMode)) return true

    val catalogModes = clientSettings?.capabilities?.modes.orEmpty()
    if (catalogModes.isNotEmpty() && matchingModes(catalogModes, normalizedMode).none { it.available }) return false
    return true
}

/** Only a correctly identified Space with an explicit worker contract bypasses desktop capture. */
fun PolarisGame.isSpaceWorkerLaunchMode(mode: String): Boolean {
    val context = space ?: return false
    if (context.id.isBlank() || context.target.isBlank() || id != "space.${context.id}.${context.target}") return false
    return PolarisGame.normalizeLaunchMode(mode) == PolarisGame.MODE_GAMESCOPE_STREAM &&
        launchMode?.allows(PolarisGame.MODE_GAMESCOPE_STREAM) == true
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
