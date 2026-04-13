package com.papi.nova.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.papi.nova.Game
import com.papi.nova.R

/**
 * Manages the persistent streaming notification.
 * Shows game name + disconnect action while streaming.
 */
object NovaStreamNotification {

    private const val CHANNEL_ID = "nova_streaming"
    private const val NOTIFICATION_ID = 9001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active streaming session status"
                setShowBadge(false)
            }
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr?.createNotificationChannel(channel)
        }
    }

    fun show(context: Context, gameName: String, serverName: String) {
        // Check preference
        val enabled = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context).getBoolean("nova_stream_notification", true)
        if (!enabled) return

        createChannel(context)

        // Tap to return to stream
        val returnIntent = Intent(context, Game::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val returnPending = PendingIntent.getActivity(
            context, 0, returnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Disconnect action
        val disconnectIntent = Intent(NovaQsTile.NOVA_DISCONNECT_ACTION).apply {
            setPackage(context.packageName)
        }
        val disconnectPending = PendingIntent.getBroadcast(
            context, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nova_star_foreground)
            .setContentTitle(gameName.ifEmpty { "Streaming" })
            .setContentText("Connected to $serverName")
            .setSubText("Nova")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(returnPending)
            .addAction(0, "Disconnect", disconnectPending)
            .setColor(context.getColor(R.color.nova_accent))
            .build()

        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr?.notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr?.cancel(NOTIFICATION_ID)
    }
}
