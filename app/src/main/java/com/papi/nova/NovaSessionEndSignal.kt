package com.papi.nova

import android.content.Context

object NovaSessionEndSignal {
    private const val PREFS = "nova_prefs"
    private const val KEY_PC_PREFIX = "active_session_quit_requested_pc_"
    private const val KEY_HOST_PREFIX = "active_session_quit_requested_host_"
    private const val KEY_ANY = "active_session_quit_requested_any"
    private const val MAX_AGE_MS = 30_000L

    fun mark(context: Context, pcUuid: String?, host: String? = null) {
        val keys = keysFor(pcUuid, host)
        if (keys.isEmpty()) {
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                val now = System.currentTimeMillis()
                keys.forEach { putLong(it, now) }
            }
            .commit()
    }

    fun consume(context: Context, pcUuid: String?, host: String? = null): Boolean {
        val keys = keysFor(pcUuid, host)
        if (keys.isEmpty()) {
            return false
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var consumed = false
        val edit = prefs.edit()
        keys.forEach { key ->
            val markedAt = prefs.getLong(key, 0L)
            if (markedAt > 0L) {
                edit.remove(key)
                val ageMs = System.currentTimeMillis() - markedAt
                if (ageMs in 0..MAX_AGE_MS) {
                    consumed = true
                }
            }
        }
        edit.apply()
        return consumed
    }

    private fun keysFor(pcUuid: String?, host: String?): Set<String> {
        val keys = linkedSetOf<String>()
        pcUuid?.trim()?.takeIf { it.isNotBlank() }?.let {
            keys += KEY_PC_PREFIX + it
        }
        host?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let {
            keys += KEY_HOST_PREFIX + it
        }
        keys += KEY_ANY
        return keys
    }
}
