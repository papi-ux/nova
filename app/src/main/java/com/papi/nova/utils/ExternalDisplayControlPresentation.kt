package com.papi.nova.utils

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.papi.nova.Game
import com.papi.nova.R
import com.papi.nova.StartExternalDisplayControlReceiver
import com.papi.nova.binding.input.GameInputDevice

/**
 * Game-owned controller surface rendered on a presentation-capable companion display.
 */
class ExternalDisplayControlPresentation(
    override val game: Game,
    override val controlDisplay: Display,
) : Presentation(game, controlDisplay, R.style.ExternalDisplayControllerTheme),
    ExternalDisplayControlHost {

    private lateinit var controller: ExternalDisplayControlController

    override val hostContext: Context
        get() = context

    override val hostWindow: Window
        get() = requireNotNull(window)

    override val companionDialogContext: Context by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            game.createDisplayContext(controlDisplay).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
                null,
            )
        } else {
            context
        }
    }

    override val companionDialogWindowType: Int
        get() = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG

    override fun companionDialogWindowToken(): IBinder? = hostWindow.decorView.windowToken

    override fun setControllerContentView(view: View) {
        setContentView(view)
    }

    override fun isHostShowing(): Boolean = isShowing

    override fun dismissHost() {
        dismiss()
    }

    override fun cancelHost() {
        super.cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ExternalDisplayControlController(this)
        controller.onCreate()
    }

    override fun onStart() {
        super.onStart()
        controller.onStart()
    }

    override fun dismissAfterCurrentCallback() {
        controller.dismissAfterCurrentCallback()
    }

    override fun cancel() {
        controller.cancel()
    }

    fun disposeAfterFailedShow() {
        controller.disposeAfterFailedShow()
    }

    override fun onStop() {
        controller.onStop()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        controller.onWindowFocusChanged(hasFocus)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        controller.handleCompanionBack()
    }

    override fun handleBackFromOwningGame(): Boolean = controller.handleBackFromOwningGame()

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return controller.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return controller.onKeyDown(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return controller.onKeyUp(event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        return controller.onKeyMultiple(keyCode, repeatCount, event)
    }

    override fun toggleZoomMode(callGame: Boolean) {
        controller.toggleZoomMode(callGame)
    }

    fun showGameMenu() {
        controller.showGameMenu()
    }

    override fun isCompanionDisplayAvailable(): Boolean {
        return controller.isCompanionDisplayAvailable()
    }

    override fun shouldMigrateOpenMenuToStream(streamAvailable: Boolean): Boolean {
        return controller.shouldMigrateOpenMenuToStream(streamAvailable)
    }

    override fun showGameMenuOnCompanion(device: GameInputDevice?): Boolean {
        return controller.showGameMenuOnCompanion(device)
    }

    override fun hideGameMenu() {
        controller.hideGameMenu()
    }

    override fun isGameMenuOpen(): Boolean {
        return controller.isGameMenuOpen()
    }

    override fun toggleKeyboard() {
        controller.toggleKeyboard()
    }

    override fun toggleFullKeyboard() {
        controller.toggleFullKeyboard()
    }

    override fun toggleGameMenu() {
        controller.toggleGameMenu()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "secondary_screen_active_channel_id"

        const val SECONDARY_SCREEN_NOTIFICATION_ID = 1
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001

        @JvmStatic
        fun ensureCompanionControlsNotification(game: Game) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(game, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    game,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE,
                )
                return
            }
            postCompanionControlsNotification(game)
        }

        @JvmStatic
        fun onCompanionNotificationPermissionResult(
            game: Game,
            granted: Boolean,
            shouldPost: Boolean,
        ) {
            if (granted && shouldPost) {
                postCompanionControlsNotification(game)
            } else if (!granted) {
                Toast.makeText(game, game.getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
            }
        }

        private fun postCompanionControlsNotification(context: Context) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
                channel.setShowBadge(false)
                notificationManager.createNotificationChannel(channel)
            }

            val broadcastIntent = Intent(context, StartExternalDisplayControlReceiver::class.java)
                .setAction(StartExternalDisplayControlReceiver.ACTION_START_EXTERNAL_DISPLAY_CONTROL)
                .setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notificationBuilder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_title))
                .setContentText(context.getString(R.string.notification_text))
                .setSmallIcon(R.drawable.app_icon)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)

            val notification: Notification = notificationBuilder.build()
            notificationManager.notify(SECONDARY_SCREEN_NOTIFICATION_ID, notification)
        }
    }
}
