package com.papi.nova.binding.input

import android.os.Handler

internal class ControllerButtonReleaseScheduler(
    private val handler: Handler,
    private val minimumButtonDownTimeMs: Int,
) {
    data class ReleaseKey(val keyCode: Int, val scanCode: Int)

    private val pendingReleases = mutableMapOf<Any, MutableMap<ReleaseKey, Runnable>>()

    fun scheduleIfNeeded(
        owner: Any,
        key: ReleaseKey,
        downTimeMs: Long,
        eventTimeMs: Long,
        shouldSkip: () -> Boolean,
        release: () -> Unit,
    ): Boolean {
        val delayMs = minimumButtonDownTimeMs - (eventTimeMs - downTimeMs)
        if (delayMs <= 0) {
            return false
        }

        cancel(owner, key)
        lateinit var releaseRunnable: Runnable
        releaseRunnable =
            Runnable {
                val releases = pendingReleases[owner] ?: return@Runnable
                if (releases.remove(key) !== releaseRunnable) {
                    return@Runnable
                }
                if (releases.isEmpty()) {
                    pendingReleases.remove(owner)
                }
                if (!shouldSkip()) {
                    release()
                }
            }

        pendingReleases.getOrPut(owner) { mutableMapOf() }[key] = releaseRunnable
        handler.postDelayed(releaseRunnable, delayMs)
        return true
    }

    fun flushPendingRelease(owner: Any, key: ReleaseKey): Boolean {
        val releaseRunnable = pendingReleases[owner]?.get(key) ?: return false
        handler.removeCallbacks(releaseRunnable)
        releaseRunnable.run()
        return true
    }

    fun cancelOwner(owner: Any) {
        val releases = pendingReleases.remove(owner) ?: return
        for (release in releases.values) {
            handler.removeCallbacks(release)
        }
    }

    fun cancelAll() {
        for (releases in pendingReleases.values) {
            for (release in releases.values) {
                handler.removeCallbacks(release)
            }
        }
        pendingReleases.clear()
    }

    private fun cancel(owner: Any, key: ReleaseKey) {
        val releases = pendingReleases[owner] ?: return
        val release = releases.remove(key) ?: return
        handler.removeCallbacks(release)
        if (releases.isEmpty()) {
            pendingReleases.remove(owner)
        }
    }
}
