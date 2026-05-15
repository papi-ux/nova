package com.papi.nova.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.papi.nova.LimeLog
import com.papi.nova.ui.compose.NovaComposeTheme

/**
 * Calm, non-alarming overlay shown during stream reconnection.
 */
class ReconnectOverlay(private val activity: Activity) {
    private var overlayView: ComposeView? = null
    private val overlayState = mutableStateOf(NovaReconnectOverlayState(attempt = 1, maxAttempts = 1))

    fun show(attempt: Int, maxAttempts: Int) {
        activity.runOnUiThread {
            overlayState.value = NovaReconnectOverlayState(attempt = attempt, maxAttempts = maxAttempts)
            if (overlayView != null) {
                return@runOnUiThread
            }

            val composeView = ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    NovaComposeTheme {
                        NovaReconnectOverlayContent(state = overlayState.value)
                    }
                }
            }
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            rootView.addView(composeView, params)
            overlayView = composeView
            LimeLog.info("Nova: Reconnect overlay shown (attempt $attempt)")
        }
    }

    fun dismiss() {
        activity.runOnUiThread {
            val view = overlayView
            overlayView = null
            view?.let {
                safeRemoveFromParent(it)
                LimeLog.info("Nova: Reconnect overlay dismissed")
            }
        }
    }

    private fun safeRemoveFromParent(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        parent.post {
            val currentParent = view.parent as? ViewGroup
            currentParent?.removeView(view)
        }
    }

    val isShowing get() = overlayView != null
}
