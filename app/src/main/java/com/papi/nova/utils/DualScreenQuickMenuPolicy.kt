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
