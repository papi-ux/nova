package com.papi.nova.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.widget.Toast
import com.papi.nova.Game
import com.papi.nova.LimeLog
import com.papi.nova.R

/**
 * One-shot activity used only to force the stream Game activity onto the selected Android display.
 *
 * Do not use ExternalDisplayControlActivity for this: that activity is a singleton companion/control
 * surface. Reusing it as a bootstrap can strand Thor-style bottom-screen launches as a black
 * control placeholder before Game owns the top display.
 */
class GameDisplayLaunchTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gameIntent = readLaunchIntent()
        if (gameIntent == null) {
            finish()
            return
        }

        launchGameOnRequestedDisplay(this, gameIntent)
        finish()
    }

    @Suppress("DEPRECATION")
    private fun readLaunchIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_LAUNCH_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_LAUNCH_INTENT)
        }
    }

    companion object {
        @JvmField
        val EXTRA_LAUNCH_INTENT: String = "launchIntent"

        @JvmStatic
        @SuppressLint("InlinedApi")
        fun launchGameOnRequestedDisplay(context: Context, gameIntent: Intent) {
            val launchIntent = Intent(gameIntent)
            if (context !is Activity) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val targetDisplayId = launchIntent.getIntExtra(Game.EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
            val targetDisplay = displayManager.getDisplay(targetDisplayId)

            if (targetDisplay != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                LimeLog.info("Nova: Android display launch stream id=${targetDisplay.displayId}")
                val options = ActivityOptions.makeBasic()
                options.setLaunchDisplayId(targetDisplay.displayId)
                if (targetDisplay.displayId != Display.DEFAULT_DISPLAY && context is Activity) {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.external_display_info,
                            targetDisplay.mode.physicalWidth,
                            targetDisplay.mode.physicalHeight,
                            targetDisplay.mode.refreshRate,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                context.startActivity(launchIntent, options.toBundle())
                return
            }

            LimeLog.warning(context.getString(R.string.no_external_display))
            context.startActivity(launchIntent)
        }
    }
}
