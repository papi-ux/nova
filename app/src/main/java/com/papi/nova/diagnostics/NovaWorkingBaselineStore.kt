package com.papi.nova.diagnostics

import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * The device's working baselines: at most [NovaWorkingBaseline.MAX_SCOPES] entries, one per PC and
 * app entry, in their own app-private preferences file (excluded from backup and device transfer
 * by the app's backup rules, like every preferences file).
 *
 * Reading ([load], [inspect]) never writes. A missing or malformed local key, an entry that
 * doesn't parse, or a file holding anything this store doesn't write (another name, too many
 * entries, a value over [NovaWorkingBaseline.MAX_ENTRY_BYTES] UTF-8 bytes) all read as unavailable
 * and are left exactly as they are.
 *
 * Maintenance happens only in the user's explicit, confirmed [save] and [clear], off the main
 * thread and one at a time. Both remove what can no longer be read or attributed: with no usable
 * key that is every stored baseline, since none can be matched to an entry. Only Save creates the
 * key when there is none and evicts the oldest readable baseline beyond the limit; Clear keeps
 * every other readable baseline. [inspect] says beforehand when either would remove anything
 * beyond this entry, so the confirmation can say so.
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

    /** A read-only look at this entry and at what a Save or Clear would also remove. */
    data class Inspection(
        val loaded: Loaded,
        /** Other stored records can't be read or attributed, so Save or Clear would remove them. */
        val unrecoverableOthers: Boolean,
        /** Saving this entry would evict the oldest other baseline to stay within the limit. */
        val saveEvictsOldest: Boolean,
    )

    enum class Saved { SAVED, REPLACED, READINGS_UNAVAILABLE, STORAGE_FAILED }

    /** Read-only. */
    fun load(entry: NovaWorkingBaseline.RequestedEntry): Loaded = inspect(entry).loaded

    /** Read-only. */
    fun inspect(entry: NovaWorkingBaseline.RequestedEntry): Inspection {
        val stored = read() ?: return Inspection(Loaded.Unreadable, unrecoverableOthers = true, saveEvictsOldest = false)
        if (stored.isEmpty()) return Inspection(Loaded.None, unrecoverableOthers = false, saveEvictsOldest = false)
        val key = NovaWorkingBaseline.unhex(stored[LOCAL_KEY] as? String)
        val scope = key?.let { NovaWorkingBaseline.scope(it, entry) }
            // No usable key: nothing stored can be matched to any entry, this one included.
            ?: return Inspection(Loaded.Unreadable, unrecoverableOthers = stored.keys.any { it != LOCAL_KEY }, saveEvictsOldest = false)
        val mine = ENTRY_PREFIX + scope
        val readable = readableOthers(stored, mine)
        val unrecoverable = stored.keys.count { it != LOCAL_KEY && it != mine } > readable.size
        val loaded = when {
            !withinBounds(stored) -> Loaded.Unreadable
            mine !in stored -> Loaded.None
            else -> NovaWorkingBaseline.parse(stored[mine] as? String, scope)?.let { Loaded.Found(it) } ?: Loaded.Unreadable
        }
        return Inspection(loaded, unrecoverable, saveEvictsOldest = readable.size >= NovaWorkingBaseline.MAX_SCOPES)
    }

    /**
     * The user's confirmed Save or Replace of [snapshot], frozen at that press. Off the main
     * thread. Storage that refuses or throws is [Saved.STORAGE_FAILED], never a crash.
     */
    @Synchronized
    fun save(entry: NovaWorkingBaseline.RequestedEntry, snapshot: NovaMomentReport.Snapshot): Saved = try {
        saveLocked(entry, snapshot)
    } catch (_: RuntimeException) {
        Saved.STORAGE_FAILED
    }

    private fun saveLocked(entry: NovaWorkingBaseline.RequestedEntry, snapshot: NovaMomentReport.Snapshot): Saved {
        if (snapshot.readings != NovaMomentReport.Readings.CURRENT) return Saved.READINGS_UNAVAILABLE
        val stored = preferences.all
        val existingKey = NovaWorkingBaseline.unhex(stored[LOCAL_KEY] as? String)
        val key = existingKey ?: newKey().takeIf { it.size == NovaWorkingBaseline.KEY_BYTES } ?: return Saved.STORAGE_FAILED
        val scope = NovaWorkingBaseline.scope(key, entry) ?: return Saved.STORAGE_FAILED
        val baseline = NovaWorkingBaseline.fromSnapshot(scope, snapshot) ?: return Saved.READINGS_UNAVAILABLE
        val text = NovaWorkingBaseline.encode(baseline) ?: return Saved.READINGS_UNAVAILABLE
        val mine = ENTRY_PREFIX + scope

        // Every other entry this store can still read, newest kept; anything else is removed.
        val keep = if (existingKey == null) emptySet() else readableOthers(stored, mine)
            .sortedBy { it.second }.takeLast(NovaWorkingBaseline.MAX_SCOPES - 1).map { it.first }.toSet()
        val replaced = existingKey != null && NovaWorkingBaseline.parse(stored[mine] as? String, scope) != null

        val editor = preferences.edit()
        stored.keys.filter { it != LOCAL_KEY && it != mine && it !in keep }.forEach { editor.remove(it) }
        if (existingKey == null) editor.putString(LOCAL_KEY, NovaWorkingBaseline.hex(key))
        editor.putString(mine, text)
        return if (editor.commit()) (if (replaced) Saved.REPLACED else Saved.SAVED) else Saved.STORAGE_FAILED
    }

    /**
     * The user's confirmed Clear for this entry, off the main thread: removes its baseline,
     * readable or not, and whatever else can no longer be read or attributed, a stored key that
     * isn't usable included. Never creates a key. Returns false if storage refused or threw.
     */
    @Synchronized
    fun clear(entry: NovaWorkingBaseline.RequestedEntry): Boolean = try {
        clearLocked(entry)
    } catch (_: RuntimeException) {
        false
    }

    private fun clearLocked(entry: NovaWorkingBaseline.RequestedEntry): Boolean {
        val stored = preferences.all
        val key = NovaWorkingBaseline.unhex(stored[LOCAL_KEY] as? String)
        val scope = key?.let { NovaWorkingBaseline.scope(it, entry) }
        val remove = if (scope == null) {
            // Nothing stored can be matched to an entry without a usable key, and a key that is
            // present but can't be read is itself unrecoverable: it goes with them, so the file
            // reads as empty afterwards rather than as unreadable until a Save. No key is made.
            stored.keys.toList()
        } else {
            // Every other readable baseline stays, however many there are: Clear never evicts.
            val keep = readableOthers(stored, ENTRY_PREFIX + scope).map { it.first }.toSet()
            stored.keys.filter { it != LOCAL_KEY && it !in keep }
        }
        if (remove.isEmpty()) return true
        val editor = preferences.edit()
        remove.forEach { editor.remove(it) }
        return editor.commit()
    }

    /** The file as stored, or null if even reading it fails. */
    private fun read(): Map<String, *>? = try {
        preferences.all
    } catch (_: RuntimeException) {
        null
    }

    /** Every entry other than [mine] that reads as a baseline of its own scope, with its time. */
    private fun readableOthers(stored: Map<String, *>, mine: String): List<Pair<String, String>> =
        stored.mapNotNull { (name, value) ->
            val scope = name.removePrefix(ENTRY_PREFIX)
            if (name == mine || scope == name || !NovaWorkingBaseline.isScope(scope)) null
            else NovaWorkingBaseline.parse(value as? String, scope)?.let { name to it.markedAtUtc }
        }

    /**
     * Only the names this store writes, no more of them than it keeps, and every value a string
     * within [NovaWorkingBaseline.MAX_ENTRY_BYTES] UTF-8 bytes, whichever entry it belongs to.
     */
    private fun withinBounds(stored: Map<String, *>): Boolean {
        if (stored.size > NovaWorkingBaseline.MAX_SCOPES + 1) return false
        return stored.all { (name, value) ->
            (name == LOCAL_KEY || (name.startsWith(ENTRY_PREFIX) && NovaWorkingBaseline.isScope(name.removePrefix(ENTRY_PREFIX)))) &&
                value is String && NovaWorkingBaseline.fitsEntry(value)
        }
    }

    companion object {
        /** The preferences file, separate from settings so nothing else reads or rewrites it. */
        const val FILE = "nova_working_baseline"
        internal const val LOCAL_KEY = "local_key_v1"
        internal const val ENTRY_PREFIX = "baseline_v1_"
    }
}
