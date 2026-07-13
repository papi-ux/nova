package com.papi.nova.utils

internal object CompanionControlLifecyclePolicy {
    @JvmStatic
    fun canShow(
        streamActive: Boolean,
        gameFinishing: Boolean,
        gameDestroyed: Boolean
    ): Boolean = streamActive && !gameFinishing && !gameDestroyed
}
