package com.papi.nova

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

class StartExternalDisplayControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_START_EXTERNAL_DISPLAY_CONTROL) {
            return
        }

        requestFocusToGameActivity(true)
    }

    companion object {
        const val ACTION_START_EXTERNAL_DISPLAY_CONTROL =
            "com.papi.nova.action.START_EXTERNAL_DISPLAY_CONTROL"

        private const val TIMEOUT_MS = 300L
        private val handler = Handler(Looper.getMainLooper())
        private var isTimeoutActive = false

        @JvmStatic
        @Deprecated("Companion controls are now Game-owned; use requestFocusToGameActivity(true)")
        @Suppress("UNUSED_PARAMETER")
        fun requestFocusToExternalDisplayControl(context: Context) {
            requestFocusToGameActivity(true)
        }

        @JvmStatic
        @Deprecated("Companion controls are now Game-owned; use requestFocusToGameActivity(true)")
        @Suppress("UNUSED_PARAMETER")
        fun requestFocusToExternalDisplayControl(context: Context, streamDisplayId: Int) {
            requestFocusToGameActivity(true)
        }

        @JvmStatic
        fun requestFocusToGameActivity(showCompanionControls: Boolean) {
            if (isTimeoutActive) {
                return
            }

            isTimeoutActive = true
            val game = Game.instance
            if (game != null) {
                val activityManager = game.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
                activityManager?.moveTaskToFront(game.taskId, 0)
            }

            handler.postDelayed(
                {
                    if (
                        showCompanionControls &&
                        game != null &&
                        Game.instance === game &&
                        !game.isFinishing
                    ) {
                        game.showCompanionControls()
                    }
                    isTimeoutActive = false
                },
                TIMEOUT_MS,
            )
        }
    }
}
