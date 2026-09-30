package com.papi.nova.ui.panel

import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi

/**
 * Lets the platform's Back act in a window only when its press began in that window.
 *
 * On API 34 and later, in an activity that opts in to OnBackInvokedCallback (PcView, AppView, the
 * settings screens and others), KEYCODE_BACK never reaches dispatchKeyEvent: the platform hands its
 * press to the window's top back callback as onBackStarted and its release as onBackInvoked. A Back
 * held while a panel or state page appears went down in the window under it, so the new window sees
 * only the release, which closed what had just appeared. Through this gate a release acts only after
 * a start seen here. A back gesture starts in the window it acts on, so gestures always pass.
 *
 * Android 13 needs no gate and gives nothing to gate on: it reads only the application's
 * enableOnBackInvokedCallback, which Nova keeps off, so there KEYCODE_BACK goes through
 * dispatchKeyEvent, where Dialog and Activity act only on a release whose press they tracked. B and
 * Escape never come this way: [NovaKeyGate] turns them into Back only for a release it saw pressed.
 */
class NovaBackStartGate {
    private var started = false

    /** A press or a gesture began in this window. */
    fun onStarted() {
        started = true
    }

    /** The press or gesture that began here was cancelled. */
    fun onCancelled() {
        started = false
    }

    /** Whether a release may act: once for each start seen here, never for a release alone. */
    fun admit(): Boolean {
        val admitted = started
        started = false
        return admitted
    }

    /** Forgets a start, for a window that loses focus in the middle of a press. */
    fun reset() {
        started = false
    }
}

/**
 * [window] as its back owner registers through it, with each animation callback behind [gate].
 *
 * NovaPanelWindow hands this to its OnBackPressedDispatcher, whose one callback on API 34 and later
 * is an animation callback, so that callback acts on a release only after a start in this window.
 * What the platform registers itself, such as the soft keyboard's callback, never comes through here
 * and keeps working as before. A plain callback, which gets no start, passes through as it is.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class NovaStartGatedBackDispatcher(
    private val window: OnBackInvokedDispatcher,
    private val gate: NovaBackStartGate,
) : OnBackInvokedDispatcher {
    private val gated = HashMap<OnBackInvokedCallback, OnBackInvokedCallback>()

    override fun registerOnBackInvokedCallback(priority: Int, callback: OnBackInvokedCallback) {
        window.registerOnBackInvokedCallback(priority, gated.getOrPut(callback) { gateOf(callback) })
    }

    override fun unregisterOnBackInvokedCallback(callback: OnBackInvokedCallback) {
        window.unregisterOnBackInvokedCallback(gated.remove(callback) ?: callback)
    }

    /** What stands in [window] for each callback registered here, for tests. */
    internal fun registered(): List<OnBackInvokedCallback> = gated.values.toList()

    private fun gateOf(callback: OnBackInvokedCallback): OnBackInvokedCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && callback is OnBackAnimationCallback) {
            StartGatedBackCallback(callback, gate)
        } else {
            callback
        }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private class StartGatedBackCallback(
    private val callback: OnBackAnimationCallback,
    private val gate: NovaBackStartGate,
) : OnBackAnimationCallback {
    override fun onBackStarted(backEvent: BackEvent) {
        gate.onStarted()
        callback.onBackStarted(backEvent)
    }

    override fun onBackProgressed(backEvent: BackEvent) {
        callback.onBackProgressed(backEvent)
    }

    override fun onBackCancelled() {
        gate.onCancelled()
        callback.onBackCancelled()
    }

    override fun onBackInvoked() {
        // A release with no start here began in another window: it neither acts nor cancels.
        if (gate.admit()) callback.onBackInvoked()
    }
}
