package com.papi.nova.service

import android.app.NotificationManager
import android.content.Context

/**
 * Clears the legacy active-stream notification if it exists from an older build.
 * Background/resumable sessions use NovaStreamKeepAlive.
 */
object NovaStreamNotification {

    private const val NOTIFICATION_ID = 9001

    fun dismiss(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr?.cancel(NOTIFICATION_ID)
    }
}
