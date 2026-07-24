package com.papi.nova.utils

object DualScreenQuickMenuPolicy {
    const val FOLLOW_INTERACTION = "follow_interaction"
    const val STREAM = "stream"
    const val COMPANION = "companion"

    enum class Surface {
        STREAM,
        COMPANION,
    }

    enum class BackAction {
        PASS_THROUGH,
        DISMISS,
        SHOW,
    }

    fun normalize(policy: String?): String {
        return when (policy) {
            STREAM -> STREAM
            COMPANION -> COMPANION
            else -> FOLLOW_INTERACTION
        }
    }

    fun resolve(
        policy: String?,
        originDisplayId: Int?,
        lastInteractionDisplayId: Int?,
        streamDisplayId: Int,
        companionDisplayId: Int?,
    ): Surface {
        if (companionDisplayId == null) return Surface.STREAM

        return when (normalize(policy)) {
            STREAM -> Surface.STREAM
            COMPANION -> Surface.COMPANION
            else -> {
                surfaceForDisplay(originDisplayId, streamDisplayId, companionDisplayId)
                    ?: surfaceForDisplay(lastInteractionDisplayId, streamDisplayId, companionDisplayId)
                    ?: Surface.STREAM
            }
        }
    }

    fun backAction(backMenuEnabled: Boolean, quickMenuOpen: Boolean): BackAction {
        if (!backMenuEnabled) return BackAction.PASS_THROUGH
        return if (quickMenuOpen) BackAction.DISMISS else BackAction.SHOW
    }

    /**
     * Recovers companion origin when Android routes display-owned Back through [Game].
     * API 33 can preserve either owner-checked window focus or an ACTION_DOWN interaction,
     * depending on which legacy callback path receives the system gesture.
     */
    fun escapedBackOrigin(
        companionDisplayId: Int?,
        lastInteractionDisplayId: Int?,
        companionHasWindowFocus: Boolean,
    ): Int? {
        if (companionDisplayId == null) return null

        return companionDisplayId.takeIf {
            companionHasWindowFocus || lastInteractionDisplayId == companionDisplayId
        }
    }

    fun legacyCompanionBackOrigin(
        companionDisplayId: Int?,
        lastInteractionDisplayId: Int? = null,
        companionHasWindowFocus: Boolean,
        inputDeviceId: Int,
        isMouseInput: Boolean,
        ignoreSyntheticEvents: Boolean = false,
        sendMetaOnBack: Boolean = false,
    ): Int? {
        if (inputDeviceId >= 0 || isMouseInput || ignoreSyntheticEvents || sendMetaOnBack) return null
        return escapedBackOrigin(
            companionDisplayId = companionDisplayId,
            lastInteractionDisplayId = lastInteractionDisplayId,
            companionHasWindowFocus = companionHasWindowFocus,
        )
    }

    fun acceptsCompanionFocus(
        currentCompanionDisplayId: Int?,
        focusDisplayId: Int,
        isCurrentPresentation: Boolean,
    ): Boolean {
        return isCurrentPresentation && currentCompanionDisplayId == focusDisplayId
    }

    fun openWithFallback(
        requestedSurface: Surface,
        showStream: () -> Unit,
        showCompanion: () -> Boolean,
    ): Surface {
        if (requestedSurface == Surface.COMPANION && showCompanion()) {
            return Surface.COMPANION
        }

        showStream()
        return Surface.STREAM
    }

    fun shouldMigrateCompanionMenu(
        menuWasOpen: Boolean,
        dismissalRequestedByNova: Boolean,
        streamAvailable: Boolean,
    ): Boolean {
        return menuWasOpen && !dismissalRequestedByNova && streamAvailable
    }

    private fun surfaceForDisplay(
        displayId: Int?,
        streamDisplayId: Int,
        companionDisplayId: Int,
    ): Surface? {
        return when (displayId) {
            streamDisplayId -> Surface.STREAM
            companionDisplayId -> Surface.COMPANION
            else -> null
        }
    }
}
