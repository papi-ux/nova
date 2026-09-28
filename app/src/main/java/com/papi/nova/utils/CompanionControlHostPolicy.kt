package com.papi.nova.utils

import android.view.Display

object CompanionControlHostPolicy {
    enum class HostType {
        PRESENTATION,
        ACTIVITY,
    }

    @JvmStatic
    fun select(displayId: Int, presentationCapable: Boolean): HostType {
        return if (displayId == Display.DEFAULT_DISPLAY || !presentationCapable) {
            HostType.ACTIVITY
        } else {
            HostType.PRESENTATION
        }
    }
}
