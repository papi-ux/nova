package com.papi.nova.utils

import java.util.concurrent.TimeUnit

object CompanionScreenDimmingPolicy {
    fun delayMillis(timeoutSeconds: Int): Long? {
        if (timeoutSeconds <= 0) return null
        return TimeUnit.SECONDS.toMillis(timeoutSeconds.toLong())
    }

    fun shouldDimNow(keyboardVisible: Boolean, quickMenuOpen: Boolean): Boolean {
        return !keyboardVisible && !quickMenuOpen
    }
}
