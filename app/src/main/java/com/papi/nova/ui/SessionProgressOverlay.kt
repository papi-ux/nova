package com.papi.nova.ui

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.preference.PreferenceManager
import com.papi.nova.Game
import com.papi.nova.LimeLog
import com.papi.nova.ui.compose.NovaComposeTheme

/**
 * Overlay that shows session lifecycle progress during stream setup.
 */
class SessionProgressOverlay(private val activity: Activity) {
    private var overlayView: ComposeView? = null
    private var backgroundBlurLeases: List<NovaMenuBlur.BlurLease> = emptyList()
    private val overlayState = mutableStateOf(NovaSessionProgressUiState.from("initializing"))
    private var spaceSession = false
    // The artwork the game's page offered for this stream. Only the first show takes it, and a
    // dismissal lets it go, so showing again, a reconnect or any other way in starts without it.
    private val launchArtwork = mutableStateOf<NovaLaunchArtwork?>(null)
    private var launchArtworkTaken = false
    private var animationsOff = false

    fun setSpaceSession(enabled: Boolean) {
        activity.runOnUiThread {
            spaceSession = enabled
            if (enabled) overlayState.value = NovaSessionProgressUiState.fromSpace("initializing")!!
        }
    }

    fun show() {
        activity.runOnUiThread {
            if (overlayView != null) {
                return@runOnUiThread
            }
            overlayState.value = if (spaceSession) NovaSessionProgressUiState.fromSpace("initializing")!!
                else NovaSessionProgressUiState.from("initializing")
            if (!launchArtworkTaken) {
                launchArtworkTaken = true
                launchArtwork.value = takeLaunchArtwork()
                animationsOff = novaAnimationsOff(activity)
            }
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
                        NovaSessionProgressOverlayContent(
                            state = overlayState.value,
                            artwork = launchArtwork.value,
                            animationsOff = animationsOff,
                        )
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

    fun updateState(state: String, message: String = "", localConnectionTitle: String? = null) {
        activity.runOnUiThread {
            val nextState = if (spaceSession) NovaSessionProgressUiState.fromSpace(state) ?: return@runOnUiThread
                else NovaSessionProgressUiState.from(state, message, localConnectionTitle)
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
            launchArtwork.value = null
            view?.let {
                safeRemoveFromParent(it)
                LimeLog.info("Nova: Session progress overlay dismissed")
            }
        }
    }

    /** The artwork offered for the host and app this stream's own extras name, if it is still fresh. */
    private fun takeLaunchArtwork(): NovaLaunchArtwork? {
        val intent = activity.intent ?: return null
        return NovaLaunchArtworkHandoff.take(
            intent.getStringExtra(Game.EXTRA_PC_UUID),
            intent.getIntExtra(Game.EXTRA_APP_ID, 0),
            SystemClock.elapsedRealtime(),
        )
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
