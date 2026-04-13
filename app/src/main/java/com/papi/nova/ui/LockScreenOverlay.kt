package com.papi.nova.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.papi.nova.LimeLog
import com.papi.nova.api.PolarisApiClient

/**
 * Overlay shown when the server's screen is locked.
 * Provides a tap-to-unlock button that calls the Polaris unlock API.
 */
class LockScreenOverlay(
    private val activity: Activity,
    private val apiClient: PolarisApiClient
) {
    private var overlayView: View? = null

    fun show() {
        if (overlayView != null) return

        activity.runOnUiThread {
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xEE000000.toInt())
                setPadding(80, 80, 80, 80)
            }

            val title = TextView(activity).apply {
                text = "Server is locked"
                textSize = 24f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 40)
            }
            container.addView(title)

            val unlockBtn = Button(activity).apply {
                text = "Tap to Unlock"
                textSize = 18f
                setOnClickListener {
                    Thread {
                        val unlocked = try {
                            LimeLog.info("Nova: Requesting unlock...")
                            apiClient.unlockScreen()
                        } catch (e: Exception) {
                            LimeLog.warning("Nova: Unlock failed: ${e.message}")
                            false
                        }

                        activity.runOnUiThread {
                            if (!unlocked) {
                                Toast.makeText(activity, "Unlock request failed", Toast.LENGTH_SHORT).show()
                            }
                            // The SSE/session poll path will dismiss this when the server reports unlocked.
                        }
                    }.start()
                }
            }
            container.addView(unlockBtn)

            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            rootView.addView(container, params)
            overlayView = container

            LimeLog.info("Nova: Lock screen overlay shown")
        }
    }

    fun dismiss() {
        activity.runOnUiThread {
            overlayView?.let { view ->
                val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
                rootView.removeView(view)
                LimeLog.info("Nova: Lock screen overlay dismissed")
            }
            overlayView = null
        }
    }

    val isShowing get() = overlayView != null
}
