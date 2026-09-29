package com.papi.nova.ui

import android.app.Activity
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaProblemBack
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The host's screen is locked: a full-screen state page over the stream. Unlock is focused and
 * asks Polaris to unlock the host, so A does it; Not Now, which is also what B does, leaves the
 * lock screen on the stream to sign in there, and the page stays away until the host locks again.
 * B never unlocks: it carries on with the stream ([NovaProblemBack.Continue]).
 * The page goes by itself when the host reports it unlocked.
 */
class LockScreenOverlay(
    private val activity: Activity,
    private val apiClient: PolarisApiClient,
) {
    @Volatile private var unlockInProgress = false
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var unlockJob: Job? = null

    // Main thread only.
    private var shown = false
    private var setAsideUntilNextLock = false

    fun show() {
        activity.runOnUiThread {
            if (shown || setAsideUntilNextLock || !isActivityUsable()) return@runOnUiThread
            shown = true
            unlockInProgress = false
            NovaSurfaces.of(activity).show(page(unlocking = false))
            LimeLog.info("Nova: Lock screen page shown")
        }
    }

    private fun page(unlocking: Boolean): NovaStatePage.Problem {
        val notNow = NovaAction(activity.getString(R.string.nova_stream_lock_not_now)) { setAside() }
        return NovaStatePage.Problem(
            key = PAGE_KEY,
            title = activity.getString(R.string.nova_stream_lock_title),
            message = activity.getString(R.string.nova_stream_lock_message),
            primary = NovaAction(
                activity.getString(if (unlocking) R.string.nova_lock_overlay_unlocking else R.string.nova_lock_overlay_unlock),
            ) { requestUnlock() },
            back = NovaProblemBack.Continue(notNow),
            secondary = listOf(notNow),
        )
    }

    private fun requestUnlock() {
        if (unlockInProgress) return
        unlockInProgress = true
        NovaSurfaces.existing(activity)?.update(PAGE_KEY) { page(unlocking = true) }
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
                if (!isActivityUsable() || !shown) {
                    return@withContext
                }
                if (unlocked) {
                    dismiss(cancelUnlock = false)
                } else {
                    unlockInProgress = false
                    NovaSurfaces.existing(activity)?.update(PAGE_KEY) { page(unlocking = false) }
                    Toast.makeText(activity, R.string.nova_lock_overlay_unlock_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Not Now: the page goes, and the host's lock screen stays on the stream until it unlocks. */
    private fun setAside() {
        setAsideUntilNextLock = true
        takeDown()
    }

    /** The host unlocked, or the stream is ending; the next lock shows the page again. */
    fun dismiss() {
        dismiss(cancelUnlock = true)
    }

    private fun dismiss(cancelUnlock: Boolean) {
        if (cancelUnlock) {
            unlockJob?.cancel()
            unlockJob = null
        }
        activity.runOnUiThread {
            setAsideUntilNextLock = false
            takeDown()
        }
    }

    private fun takeDown() {
        unlockInProgress = false
        if (!shown) return
        shown = false
        NovaSurfaces.existing(activity)?.dismiss(PAGE_KEY)
        LimeLog.info("Nova: Lock screen page dismissed")
    }

    fun destroy() {
        unlockJob?.cancel()
        unlockJob = null
        fallbackScope.cancel()
    }

    private fun unlockScope(): CoroutineScope =
        (activity as? LifecycleOwner)?.lifecycleScope ?: fallbackScope

    private fun isActivityUsable(): Boolean = !activity.isFinishing && !activity.isDestroyed

    val isShowing get() = shown

    private companion object {
        const val PAGE_KEY = "nova-host-locked"
    }
}
