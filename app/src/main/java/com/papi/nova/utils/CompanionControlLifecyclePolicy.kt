package com.papi.nova.utils

internal object CompanionControlLifecyclePolicy {
    @JvmStatic
    fun canShow(
        streamActive: Boolean,
        gameFinishing: Boolean,
        gameDestroyed: Boolean,
        dismissedByUser: Boolean,
        explicitUserRequest: Boolean,
    ): Boolean =
        streamActive &&
            !gameFinishing &&
            !gameDestroyed &&
            (!dismissedByUser || explicitUserRequest)

    @JvmStatic
    fun canHide(reopenAvailable: Boolean): Boolean = reopenAvailable

    @JvmStatic
    fun shouldRestoreDismissedCompanion(
        dismissedByUser: Boolean,
        reopenAvailable: Boolean,
    ): Boolean = dismissedByUser && !reopenAvailable

    @JvmStatic
    fun shouldPreserveReopenNotification(
        streamActive: Boolean,
        dismissedByUser: Boolean,
    ): Boolean = streamActive && dismissedByUser
}
