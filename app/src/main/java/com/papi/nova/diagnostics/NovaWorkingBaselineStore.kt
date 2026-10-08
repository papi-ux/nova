package com.papi.nova.diagnostics

import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * The device's working baselines: at most [NovaWorkingBaseline.MAX_SCOPES] entries, one per PC and
 * app entry, in their own app-private preferences file (excluded from backup and device transfer
 * by the app's backup rules, like every preferences file).
 *
 * Reading never writes. A missing or malformed local key, an entry that doesn't parse, or a file
 * holding more than this store ever writes all read as unavailable and are left exactly as they
 * are. Creating the key, evicting old entries and removing unreadable ones happen only in the
 * user's explicit Save ([save]) and Clear ([clear]).
 */
class NovaWorkingBaselineStore(
    private val preferences: SharedPreferences,
    private val newKey: () -> ByteArray = { ByteArray(NovaWorkingBaseline.KEY_BYTES).also(SecureRandom()::nextBytes) },
) {
    sealed interface Loaded {
        /** No baseline is saved for this entry. */
        object None : Loaded
        /** Something is stored but can't be read; it is left in place until an explicit Save or Clear. */
        object Unreadable : Loaded
        data class Found(val baseline: NovaWorkingBaseline.Baseline) : Loaded
    }

    enum class Saved { SAVED, REPLACED, READINGS_UNAVAILABLE, STORAGE_FAILED }

    /** Read-only. */
    fun load(entry: NovaWorkingBaseline.RequestedEntry): Loaded {
        val stored = try {
            preferences.all
        } catch (_: RuntimeException) {
            return Loaded.Unreadable
        }
        if (!withinBounds(stored)) return Loaded.Unreadable
        val keyText = stored[LOCAL_KEY]
        if (keyText == null) return if (stored.isEmpty()) Loaded.None else Loaded.Unreadable
        val scope = NovaWorkingBaseline.scope(NovaWorkingBaseline.unhex(keyText as? String), entry) ?: return Loaded.Unreadable
        val value = stored[ENTRY_PREFIX + scope] ?: return Loaded.None
        return NovaWorkingBaseline.parse(value as? String, scope)?.let { Loaded.Found(it) } ?: Loaded.Unreadable
    }

    /**
     * The user's confirmed Save or Replace of [snapshot], frozen at that press. This is the
     * maintenance point: it creates the local key if there is none (an unusable key is replaced,
     * which orphans every entry hashed with it, so those go too), drops whatever doesn't read,
     * and evicts the oldest baselines to stay within [NovaWorkingBaseline.MAX_SCOPES].
     */
    fun save(entry: NovaWorkingBaseline.RequestedEntry, snapshot: NovaMomentReport.Snapshot): Saved {
        if (snapshot.readings != NovaMomentReport.Readings.CURRENT) return Saved.READINGS_UNAVAILABLE
        val stored = try {
            preferences.all
        } catch (_: RuntimeException) {
            return Saved.STORAGE_FAILED
        }
        val existingKey = NovaWorkingBaseline.unhex(stored[LOCAL_KEY] as? String)
        val key = existingKey ?: newKey().takeIf { it.size == NovaWorkingBaseline.KEY_BYTES } ?: return Saved.STORAGE_FAILED
        val scope = NovaWorkingBaseline.scope(key, entry) ?: return Saved.STORAGE_FAILED
        val baseline = NovaWorkingBaseline.fromSnapshot(scope, snapshot) ?: return Saved.READINGS_UNAVAILABLE
        val text = NovaWorkingBaseline.encode(baseline) ?: return Saved.READINGS_UNAVAILABLE

        // Every other entry this store can still read, oldest first; anything else is removed.
        val kept = if (existingKey == null) emptyList() else stored.mapNotNull { (name, value) ->
            val other = name.removePrefix(ENTRY_PREFIX)
            if (name == ENTRY_PREFIX + scope || other == name || !NovaWorkingBaseline.isScope(other)) null
            else NovaWorkingBaseline.parse(value as? String, other)?.let { name to it.markedAtUtc }
        }.sortedBy { it.second }
        val keep = kept.takeLast(NovaWorkingBaseline.MAX_SCOPES - 1).map { it.first }.toSet()
        val replaced = existingKey != null && NovaWorkingBaseline.parse(stored[ENTRY_PREFIX + scope] as? String, scope) != null

        val editor = preferences.edit()
        stored.keys.filter { it != LOCAL_KEY && it !in keep }.forEach { editor.remove(it) }
        if (existingKey == null) editor.putString(LOCAL_KEY, NovaWorkingBaseline.hex(key))
        editor.putString(ENTRY_PREFIX + scope, text)
        return if (editor.commit()) (if (replaced) Saved.REPLACED else Saved.SAVED) else Saved.STORAGE_FAILED
    }

    /**
     * The user's confirmed Clear for this entry: removes its baseline, readable or not. Never
     * creates a key. Returns false only if storage refused the change.
     */
    fun clear(entry: NovaWorkingBaseline.RequestedEntry): Boolean {
        val stored = try {
            preferences.all
        } catch (_: RuntimeException) {
            return false
        }
        val key = NovaWorkingBaseline.unhex(stored[LOCAL_KEY] as? String)
        val editor = preferences.edit()
        if (key == null) {
            // Nothing stored can be matched to an entry without a usable key; Clear empties the file.
            if (stored.isEmpty()) return true
            stored.keys.forEach { editor.remove(it) }
        } else {
            val name = ENTRY_PREFIX + (NovaWorkingBaseline.scope(key, entry) ?: return false)
            if (name !in stored) return true
            editor.remove(name)
        }
        return editor.commit()
    }

    /** Only the names this store writes, no more of them than it keeps, and values within size. */
    private fun withinBounds(stored: Map<String, *>): Boolean {
        if (stored.size > NovaWorkingBaseline.MAX_SCOPES + 1) return false
        return stored.all { (name, value) ->
            (name == LOCAL_KEY || (name.startsWith(ENTRY_PREFIX) && NovaWorkingBaseline.isScope(name.removePrefix(ENTRY_PREFIX)))) &&
                value is String && value.length <= NovaWorkingBaseline.MAX_ENTRY_BYTES
        }
    }

    companion object {
        /** The preferences file, separate from settings so nothing else reads or rewrites it. */
        const val FILE = "nova_working_baseline"
        internal const val LOCAL_KEY = "local_key_v1"
        internal const val ENTRY_PREFIX = "baseline_v1_"
    }
}
