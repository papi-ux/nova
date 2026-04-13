package com.papi.nova.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.papi.nova.LimeLog

/**
 * Calm, non-alarming overlay shown during stream reconnection.
 * No error language — just "Reconnecting..." with a progress indicator.
 * Auto-dismisses on reconnect success or times out to error after 15s.
 */
class ReconnectOverlay(private val activity: Activity) {

    private var overlayView: View? = null
    private var statusText: TextView? = null
    private var attemptText: TextView? = null

    fun show(attempt: Int, maxAttempts: Int) {
        activity.runOnUiThread {
            if (overlayView != null) {
                // Update existing overlay
                attemptText?.text = "Attempt $attempt of $maxAttempts"
                return@runOnUiThread
            }

            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xDD000000.toInt())
                setPadding(80, 80, 80, 80)
            }

            statusText = TextView(activity).apply {
                text = "Reconnecting..."
                textSize = 22f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
            }
            container.addView(statusText)

            val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                setPadding(0, 40, 0, 24)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(80, 0, 80, 0) }
            }
            container.addView(progress)

            val subtitle = TextView(activity).apply {
                text = "Stream will resume automatically"
                textSize = 14f
                setTextColor(0xAAFFFFFF.toInt())
                gravity = Gravity.CENTER
            }
            container.addView(subtitle)

            attemptText = TextView(activity).apply {
                text = "Attempt $attempt of $maxAttempts"
                textSize = 12f
                setTextColor(0x88FFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 0)
            }
            container.addView(attemptText)

            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            rootView.addView(container, params)
            overlayView = container

            LimeLog.info("Nova: Reconnect overlay shown (attempt $attempt)")
        }
    }

    fun dismiss() {
        activity.runOnUiThread {
            overlayView?.let { view ->
                val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
                rootView.removeView(view)
                LimeLog.info("Nova: Reconnect overlay dismissed")
            }
            overlayView = null
            statusText = null
            attemptText = null
        }
    }

    val isShowing get() = overlayView != null
}
