package com.papi.nova.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.ui.NovaLibraryActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NovaStreamKeepAlive : Service() {

    companion object {
        private const val CHANNEL_ID = "nova_stream_keepalive"
        private const val NOTIFICATION_ID = 9002
        private const val DEFAULT_AUTO_STOP_MS = 5 * 60 * 1000L
        private const val EXTRA_TIMEOUT_SECONDS = "timeout_seconds"
        private const val EXTRA_GAME_NAME = "game_name"
        private const val EXTRA_SERVER_NAME = "server_name"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_HTTP_PORT = "http_port"
        private const val EXTRA_HTTPS_PORT = "https_port"
        private const val EXTRA_UNIQUE_ID = "unique_id"
        private const val EXTRA_PC_UUID = "pc_uuid"
        private const val EXTRA_SERVER_COMMANDS = "server_commands"
        private const val EXTRA_SERVER_CERT = "server_cert"

        @JvmStatic
        @JvmOverloads
        fun start(
            context: Context,
            timeoutSeconds: Int = 300,
            gameName: String? = null,
            serverName: String? = null,
            host: String? = null,
            httpPort: Int = 47989,
            httpsPort: Int = 47984,
            uniqueId: String? = null,
            pcUuid: String? = null,
            serverCommands: ArrayList<String>? = null,
            serverCert: ByteArray? = null
        ) {
            val intent = Intent(context, NovaStreamKeepAlive::class.java)
                .putExtra(EXTRA_TIMEOUT_SECONDS, timeoutSeconds)
                .putExtra(EXTRA_GAME_NAME, gameName.orEmpty())
                .putExtra(EXTRA_SERVER_NAME, serverName.orEmpty())
                .putExtra(EXTRA_HOST, host.orEmpty())
                .putExtra(EXTRA_HTTP_PORT, httpPort)
                .putExtra(EXTRA_HTTPS_PORT, httpsPort)
                .putExtra(EXTRA_UNIQUE_ID, uniqueId.orEmpty())
                .putExtra(EXTRA_PC_UUID, pcUuid.orEmpty())
            if (serverCommands != null) {
                intent.putStringArrayListExtra(EXTRA_SERVER_COMMANDS, serverCommands)
            }
            if (serverCert != null) {
                intent.putExtra(EXTRA_SERVER_CERT, serverCert)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, NovaStreamKeepAlive::class.java))
        }
    }

    private val autoStopRunnable = Runnable { stopSelf() }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val timeoutSeconds = intent
            ?.getIntExtra(EXTRA_TIMEOUT_SECONDS, 300)
            ?.takeIf { it >= 0 }
            ?: (DEFAULT_AUTO_STOP_MS / 1000L).toInt()
        val timeoutMs = timeoutSeconds
            .takeIf { it >= 0 }
            ?.times(1000L)
            ?: DEFAULT_AUTO_STOP_MS
        val gameName = intent?.getStringExtra(EXTRA_GAME_NAME).orEmpty()
        val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME).orEmpty()

        startForeground(NOTIFICATION_ID, buildNotification(intent, gameName, serverName, timeoutSeconds))
        syncDisconnectResumeTimeout(intent, timeoutSeconds)
        handler.removeCallbacks(autoStopRunnable)
        if (timeoutMs > 0) {
            handler.postDelayed(autoStopRunnable, timeoutMs)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStopRunnable)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows host sessions that can be resumed from the background"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(intent: Intent?, gameName: String, serverName: String, timeoutSeconds: Int): Notification {
        val launchIntent = buildResumeIntent(intent)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = gameName.ifBlank { "Session resumable" }
        val text = if (serverName.isBlank()) {
            "Live session is resumable. Tap to resume."
        } else {
            "Live on $serverName. Tap to resume."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(formatResumeWindow(timeoutSeconds))
            .setSmallIcon(R.drawable.ic_channel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun buildResumeIntent(intent: Intent?): Intent {
        val host = intent?.getStringExtra(EXTRA_HOST).orEmpty()
        val uniqueId = intent?.getStringExtra(EXTRA_UNIQUE_ID).orEmpty()
        val pcUuid = intent?.getStringExtra(EXTRA_PC_UUID).orEmpty()
        val serverCert = intent?.getByteArrayExtra(EXTRA_SERVER_CERT)
        val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
        val httpPort = intent?.getIntExtra(EXTRA_HTTP_PORT, 47989) ?: 47989
        val httpsPort = intent?.getIntExtra(EXTRA_HTTPS_PORT, 47984) ?: 47984
        val serverCommands = intent?.getStringArrayListExtra(EXTRA_SERVER_COMMANDS)
        val launchIntent = if (host.isNotBlank() && uniqueId.isNotBlank() && pcUuid.isNotBlank() && serverCert != null) {
            Intent(this, NovaLibraryActivity::class.java)
                .putExtra(NovaLibraryActivity.EXTRA_HOST, host)
                .putExtra(NovaLibraryActivity.EXTRA_SERVER_NAME, serverName)
                .putExtra(NovaLibraryActivity.EXTRA_HTTP_PORT, httpPort)
                .putExtra(NovaLibraryActivity.EXTRA_HTTPS_PORT, httpsPort)
                .putExtra(NovaLibraryActivity.EXTRA_UNIQUE_ID, uniqueId)
                .putExtra(NovaLibraryActivity.EXTRA_PC_UUID, pcUuid)
                .putExtra(NovaLibraryActivity.EXTRA_SERVER_CERT, serverCert)
                .apply {
                    if (serverCommands != null) {
                        putStringArrayListExtra(NovaLibraryActivity.EXTRA_SERVER_COMMANDS, serverCommands)
                    }
                }
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, com.papi.nova.PcView::class.java)
        }
        return launchIntent.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    private fun syncDisconnectResumeTimeout(intent: Intent?, timeoutSeconds: Int) {
        val host = intent?.getStringExtra(EXTRA_HOST).orEmpty()
        if (host.isBlank()) {
            return
        }

        val httpsPort = intent?.getIntExtra(EXTRA_HTTPS_PORT, 47984) ?: 47984
        val serverCert = intent?.getByteArrayExtra(EXTRA_SERVER_CERT)
        serviceScope.launch {
            try {
                PolarisApiClient(
                    this@NovaStreamKeepAlive,
                    host,
                    httpsPort,
                    serverCert
                ).updateClientSettings(disconnectResumeTimeoutSeconds = timeoutSeconds)
                LimeLog.info("Nova: Keep-alive synced disconnect resume timeout: ${timeoutSeconds}s")
            } catch (e: Exception) {
                LimeLog.warning("Nova: Keep-alive failed to sync disconnect resume timeout: ${e.message}")
            }
        }
    }

    private fun formatResumeWindow(timeoutSeconds: Int): String {
        if (timeoutSeconds <= 0) {
            return "Resume window active"
        }

        if (timeoutSeconds >= 60 && timeoutSeconds % 60 == 0) {
            val minutes = timeoutSeconds / 60
            return if (minutes == 1) "Resumes for 1 minute" else "Resumes for $minutes minutes"
        }

        return if (timeoutSeconds == 1) "Resumes for 1 second" else "Resumes for $timeoutSeconds seconds"
    }
}
