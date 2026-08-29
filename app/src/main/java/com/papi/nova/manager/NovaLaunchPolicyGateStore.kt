package com.papi.nova.manager

import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Process-local, one-shot handoff for the asynchronous launch-policy gate.
 *
 * The token is unguessable and bound to every launch input Game resolved before
 * doing network I/O. Nothing is persisted, so an external Intent cannot assert
 * that a host was checked or inject a resolved profile.
 */
object NovaLaunchPolicyGateStore {
    data class Decision(
        val optimizationJson: String?,
        val profilePreference: String,
        val resolvedProfileTrusted: Boolean
    )

    private data class Entry(
        val fingerprint: String,
        val decision: Decision,
        val expiresAtNanos: Long
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val lifetimeNanos = TimeUnit.SECONDS.toNanos(30)

    @Synchronized
    fun issue(fingerprint: String, decision: Decision): String {
        val now = System.nanoTime()
        removeExpired(now)
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(fingerprint, decision, now + lifetimeNanos)
        return token
    }

    @Synchronized
    fun consume(token: String?, fingerprint: String): Decision? {
        if (token.isNullOrBlank()) return null
        val now = System.nanoTime()
        removeExpired(now)
        val entry = entries.remove(token) ?: return null
        return entry.decision.takeIf { entry.fingerprint == fingerprint }
    }

    @Synchronized
    internal fun clearForTests() {
        entries.clear()
    }

    private fun removeExpired(now: Long) {
        entries.entries.removeAll { it.value.expiresAtNanos <= now }
    }
}
