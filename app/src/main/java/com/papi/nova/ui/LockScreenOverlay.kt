package com.papi.nova.ui

import android.app.Activity
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.papi.nova.LimeLog
import com.papi.nova.api.PolarisApiClient
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Overlay shown when the server's screen is locked.
 * Provides a tap-to-unlock button that calls the Polaris unlock API.
 */
class LockScreenOverlay(
    private val activity: Activity,
    private val apiClient: PolarisApiClient
) {
    private var overlayView: View? = null
    @Volatile private var unlockInProgress = false
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var unlockJob: Job? = null

    fun show() {
        if (overlayView != null) return

        activity.runOnUiThread {
            unlockInProgress = false
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xEE000000.toInt())
                setPadding(80, 80, 80, 80)
                isClickable = true
                isFocusable = true
            }

            val title = TextView(activity).apply {
                text = "Host locked"
                textSize = 24f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 40)
            }
            container.addView(title)

            val unlockBtn = Button(activity).apply {
                text = "Tap to unlock"
                textSize = 18f
                setOnClickListener {
                    requestUnlock(this)
                }
            }
            container.addView(unlockBtn)
            container.setOnClickListener {
                requestUnlock(unlockBtn)
            }

            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            val rootView = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            rootView.addView(container, params)
            container.bringToFront()
            overlayView = container

            LimeLog.info("Nova: Lock screen overlay shown")
        }
    }

    private fun requestUnlock(unlockBtn: Button) {
        if (unlockInProgress) return
        unlockInProgress = true
        unlockBtn.isEnabled = false
        unlockBtn.text = "Unlocking…"
        LimeLog.info("Nova: Requesting unlock...")
        unlockJob?.cancel()
        unlockJob = unlockScope().launch(Dispatchers.IO + CoroutineName("NovaUnlockScreen")) {
            val unlocked = try {
                apiClient.unlockScreen()
            } catch (e: Exception) {
                LimeLog.warning("Nova: Unlock failed: ${e.message}")
                false
            }

            withContext(Dispatchers.Main.immediate) {
                if (!isActivityUsable() || overlayView == null) {
                    return@withContext
                }
                if (unlocked) {
                    dismiss(cancelUnlock = false)
                } else {
                    unlockInProgress = false
                    unlockBtn.isEnabled = true
                    unlockBtn.text = "Tap to unlock"
                    Toast.makeText(activity, "Unlock request failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun dismiss() {
        dismiss(cancelUnlock = true)
    }

    private fun dismiss(cancelUnlock: Boolean) {
        if (cancelUnlock) {
            unlockJob?.cancel()
            unlockJob = null
        }
        activity.runOnUiThread {
            unlockInProgress = false
            val view = overlayView
            overlayView = null
            view?.let {
                safeRemoveFromParent(it)
                LimeLog.info("Nova: Lock screen overlay dismissed")
            }
        }
    }

    fun destroy() {
        unlockJob?.cancel()
        unlockJob = null
        fallbackScope.cancel()
    }

    private fun safeRemoveFromParent(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        parent.post {
            val currentParent = view.parent as? ViewGroup
            currentParent?.removeView(view)
        }
    }

    private fun unlockScope(): CoroutineScope =
        (activity as? LifecycleOwner)?.lifecycleScope ?: fallbackScope

    private fun isActivityUsable(): Boolean =
        !activity.isFinishing && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed)

    val isShowing get() = overlayView != null
}
