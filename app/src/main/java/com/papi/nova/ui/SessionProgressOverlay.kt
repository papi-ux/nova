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
 * Overlay that shows session lifecycle progress during stream setup.
 * Displays: "Preparing session..." → "Starting compositor..." → "Launching game..." → dismisses
 *
 * Rendered as a semi-transparent overlay on top of the streaming view.
 */
class SessionProgressOverlay(private val activity: Activity) {

    private var overlayView: View? = null
    private var statusText: TextView? = null
    private var stageText: TextView? = null

    private val stages = listOf(
        "initializing" to "Preparing session...",
        "cage_starting" to "Starting compositor...",
        "game_launching" to "Launching game...",
        "streaming" to "Connected"
    )

    fun show() {
        if (overlayView != null) return

        activity.runOnUiThread {
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xCC000000.toInt())
                setPadding(80, 80, 80, 80)
            }

            statusText = TextView(activity).apply {
                text = "Connecting to Polaris..."
                textSize = 20f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
            }
            container.addView(statusText)

            val progress = ProgressBar(activity).apply {
                setPadding(0, 32, 0, 32)
            }
            container.addView(progress)

            stageText = TextView(activity).apply {
                text = ""
                textSize = 14f
                setTextColor(0xAAFFFFFF.toInt())
                gravity = Gravity.CENTER
            }
            container.addView(stageText)

            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            rootView.addView(container, params)
            overlayView = container

            LimeLog.info("Nova: Session progress overlay shown")
        }
    }

    fun updateState(state: String, message: String = "") {
        activity.runOnUiThread {
            val stageInfo = stages.find { it.first == state }
            statusText?.text = stageInfo?.second ?: message.ifEmpty { state }

            // Show completed stages
            val currentIdx = stages.indexOfFirst { it.first == state }
            if (currentIdx >= 0) {
                val completed = stages.take(currentIdx).joinToString("\n") { "✓ ${it.second}" }
                stageText?.text = completed
            }

            // Auto-dismiss when streaming
            if (state == "streaming") {
                dismiss()
            }
        }
    }

    fun dismiss() {
        activity.runOnUiThread {
            overlayView?.let { view ->
                val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
                rootView.removeView(view)
                LimeLog.info("Nova: Session progress overlay dismissed")
            }
            overlayView = null
            statusText = null
            stageText = null
        }
    }

    val isShowing get() = overlayView != null
}
