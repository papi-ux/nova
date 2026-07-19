package com.papi.nova.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.preference.PreferenceManager
import com.papi.nova.LimeLog
import com.papi.nova.ui.compose.NovaComposeTheme

/**
 * Overlay that shows session lifecycle progress during stream setup.
 */
class SessionProgressOverlay(private val activity: Activity) {
    private var overlayView: ComposeView? = null
    private var backgroundBlurLeases: List<NovaMenuBlur.BlurLease> = emptyList()
    private val overlayState = mutableStateOf(NovaSessionProgressUiState.from("initializing"))

    fun show() {
        activity.runOnUiThread {
            if (overlayView != null) {
                return@runOnUiThread
            }
            overlayState.value = NovaSessionProgressUiState.from("initializing")
            val composeView = ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                NovaMenuBlur.releaseOnUnexpectedDetach(this) {
                    if (overlayView === this) {
                        overlayView = null
                        releaseBackgroundBlur()
                    }
                }
                setContent {
                    NovaComposeTheme {
                        NovaSessionProgressOverlayContent(state = overlayState.value)
                    }
                }
            }
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            backgroundBlurLeases = NovaMenuBlur.acquireChildren(
                rootView,
                NovaMenuPreferences.readOpacityPercent(prefs)
            )
            rootView.addView(composeView, params)
            overlayView = composeView
            LimeLog.info("Nova: Session progress overlay shown")
        }
    }

    fun updateState(state: String, message: String = "") {
        activity.runOnUiThread {
            val nextState = NovaSessionProgressUiState.from(state, message)
            if (nextState.progressFraction >= overlayState.value.progressFraction) {
                overlayState.value = nextState
            }
        }
    }

    fun dismiss() {
        activity.runOnUiThread {
            val view = overlayView
            overlayView = null
            releaseBackgroundBlur()
            view?.let {
                safeRemoveFromParent(it)
                LimeLog.info("Nova: Session progress overlay dismissed")
            }
        }
    }

    private fun releaseBackgroundBlur() {
        NovaMenuBlur.releaseAll(backgroundBlurLeases)
        backgroundBlurLeases = emptyList()
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
