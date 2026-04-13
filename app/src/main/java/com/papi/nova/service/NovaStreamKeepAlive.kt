package com.papi.nova.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.papi.nova.R

/**
 * Foreground service that keeps the streaming connection alive when
 * the user switches to another Android app (e.g., checking Discord).
 *
 * The Game activity starts this service in onPause() and stops it in onResume().
 * The service itself doesn't hold any streaming state — it exists purely to
 * prevent Android from killing the app's process while in the background.
 *
 * Auto-stops after 5 minutes if the user doesn't return.
 */
class NovaStreamKeepAlive : Service() {

    companion object {
        private const val CHANNEL_ID = "nova_stream_keepalive"
        private const val NOTIFICATION_ID = 9002
        private const val AUTO_STOP_MS = 5 * 60 * 1000L  // 5 minutes

        fun start(context: Context) {
            val intent = Intent(context, NovaStreamKeepAlive::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NovaStreamKeepAlive::class.java))
        }
    }

    private val autoStopRunnable = Runnable { stopSelf() }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Auto-stop after timeout
        handler.postDelayed(autoStopRunnable, AUTO_STOP_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStopRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Stream Keep-Alive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the stream alive while switching apps"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nova stream active")
            .setContentText("Tap to return to your game")
            .setSmallIcon(R.drawable.ic_channel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }
}
