package com.papi.nova

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.annotation.RequiresApi
import com.papi.nova.utils.ExternalDisplayControlActivity

class StartExternalDisplayControlReceiver : BroadcastReceiver() {
    @RequiresApi(api = Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        requestFocusToGameActivity(true)
    }

    companion object {
        private const val TIMEOUT_MS = 300L
        private val handler = Handler(Looper.getMainLooper())
        private var isTimeoutActive = false

        @JvmStatic
        fun requestFocusToExternalDisplayControl(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intentTouchpad = Intent(context, ExternalDisplayControlActivity::class.java)
                intentTouchpad.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                val optionsDefault: Bundle = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                    .toBundle()
                context.startActivity(intentTouchpad, optionsDefault)
            }
        }

        @JvmStatic
        fun requestFocusToGameActivity(focusExternalDisplayControl: Boolean) {
            if (isTimeoutActive) {
                return
            }

            isTimeoutActive = true

            val game = Game.instance
            if (game != null) {
                if (focusExternalDisplayControl) {
                    requestFocusToExternalDisplayControl(game)
                }
                val activityManager = game.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
                activityManager?.moveTaskToFront(game.taskId, 0)
            }

            handler.postDelayed({ isTimeoutActive = false }, TIMEOUT_MS)
        }
    }
}
