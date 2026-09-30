package com.papi.nova

import android.content.Context
import com.papi.nova.manager.WorkerLaunchContract

/**
 * A failed Space stream asked to try again.
 *
 * The stream window cannot restart itself: the library owns the Space check that precedes
 * every open. The request is left here for the library to consume when it comes back, and
 * it expires if nothing does, the same way a local End request reaches the session card.
 */
object NovaSpaceRetrySignal {
    private const val PREFS = "nova_prefs"
    private const val KEY_PC_PREFIX = "space_retry_requested_pc_"
    private const val KEY_HOST_PREFIX = "space_retry_requested_host_"
    private const val MAX_AGE_MS = 30_000L

    fun mark(context: Context, pcUuid: String?, host: String?, appIdentity: String? = null) {
        val target = appIdentity ?: WorkerLaunchContract.APP_UUID
        if (!WorkerLaunchContract.isProfileApp(target)) return
        val keys = keysFor(pcUuid, host)
        if (keys.isEmpty()) return
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            keys.forEach { putLong(it, now); putString(it + "_target", target) }
        }.apply()
    }

    fun consume(context: Context, pcUuid: String?, host: String?): Boolean =
        consumeTarget(context, pcUuid, host) != null

    fun consumeTarget(context: Context, pcUuid: String?, host: String?): String? {
        val keys = keysFor(pcUuid, host)
        if (keys.isEmpty()) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var target: String? = null
        val edit = prefs.edit()
        keys.forEach { key ->
            val markedAt = prefs.getLong(key, 0L)
            if (markedAt > 0L) {
                edit.remove(key)
                val identity = prefs.getString(key + "_target", WorkerLaunchContract.APP_UUID)
                edit.remove(key + "_target")
                if (target == null && System.currentTimeMillis() - markedAt in 0..MAX_AGE_MS &&
                    WorkerLaunchContract.isProfileApp(identity)) target = identity
            }
        }
        edit.apply()
        return target
    }

    private fun keysFor(pcUuid: String?, host: String?): Set<String> {
        val keys = linkedSetOf<String>()
        pcUuid?.trim()?.takeIf { it.isNotBlank() }?.let { keys += KEY_PC_PREFIX + it }
        host?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { keys += KEY_HOST_PREFIX + it }
        return keys
    }
}
