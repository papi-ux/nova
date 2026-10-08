package com.papi.nova.diagnostics

/**
 * Working Baseline, from the Command Center's row to the store, on the main thread.
 *
 * Opening the row and Compare only read. Save and Replace are the user's statement, made on a
 * confirmation, that this stream works well for them. The snapshot is frozen at that
 * confirmation's press, after checking again that the stream is still up here, so readings taken
 * before a long wait on the confirmation are never what gets saved. Clear is its own confirmed
 * press. Nothing here reads logs, writes files, asks the host anything, or touches Doctor,
 * tuning, bitrate, profiles or launch settings.
 */
class NovaWorkingBaselineFlow(
    private val store: NovaWorkingBaselineStore,
    /** The requested PC and app entry, or null when the launch didn't name both. */
    private val entry: () -> NovaWorkingBaseline.RequestedEntry?,
    /** A stream is actually up on this device. */
    private val isActive: () -> Boolean,
    /** A snapshot of this instant, frozen when called. No log, file or host work. */
    private val snapshot: () -> NovaMomentReport.Snapshot?,
) {
    /** What the row and its page show, read without writing anything. */
    data class State(
        /** False when the launch didn't name a PC and app entry: nothing can be saved or compared. */
        val hasEntry: Boolean,
        val active: Boolean,
        val loaded: NovaWorkingBaselineStore.Loaded,
    ) {
        val saved: NovaWorkingBaseline.Baseline? get() = (loaded as? NovaWorkingBaselineStore.Loaded.Found)?.baseline
        val canSave: Boolean get() = hasEntry && active
        val canCompare: Boolean get() = hasEntry && active && saved != null
        val canClear: Boolean get() = hasEntry && loaded != NovaWorkingBaselineStore.Loaded.None
    }

    enum class Outcome { SAVED, REPLACED, CLEARED, NOT_ACTIVE, NO_ENTRY, READINGS_UNAVAILABLE, STORAGE_FAILED }

    sealed interface Compared {
        data class Shown(val comparison: NovaWorkingBaseline.Comparison) : Compared
        object NoEntry : Compared
        object NotActive : Compared
        object NoBaseline : Compared
        object Unreadable : Compared
    }

    fun state(): State {
        val requested = entry()
        return State(
            hasEntry = requested != null,
            active = isActive(),
            loaded = requested?.let(store::load) ?: NovaWorkingBaselineStore.Loaded.None,
        )
    }

    /** The confirmed Save or Replace press. */
    fun save(): Outcome {
        val requested = entry() ?: return Outcome.NO_ENTRY
        if (!isActive()) return Outcome.NOT_ACTIVE
        val frozen = snapshot() ?: return Outcome.NOT_ACTIVE
        return when (store.save(requested, frozen)) {
            NovaWorkingBaselineStore.Saved.SAVED -> Outcome.SAVED
            NovaWorkingBaselineStore.Saved.REPLACED -> Outcome.REPLACED
            NovaWorkingBaselineStore.Saved.READINGS_UNAVAILABLE -> Outcome.READINGS_UNAVAILABLE
            NovaWorkingBaselineStore.Saved.STORAGE_FAILED -> Outcome.STORAGE_FAILED
        }
    }

    /** The Compare press. Read-only, whatever is or isn't stored. */
    fun compare(): Compared {
        val requested = entry() ?: return Compared.NoEntry
        if (!isActive()) return Compared.NotActive
        val baseline = when (val loaded = store.load(requested)) {
            NovaWorkingBaselineStore.Loaded.None -> return Compared.NoBaseline
            NovaWorkingBaselineStore.Loaded.Unreadable -> return Compared.Unreadable
            is NovaWorkingBaselineStore.Loaded.Found -> loaded.baseline
        }
        val now = snapshot() ?: return Compared.NotActive
        return Compared.Shown(NovaWorkingBaseline.compare(baseline, now))
    }

    /** The confirmed Clear press. Needs no running stream. */
    fun clear(): Outcome {
        val requested = entry() ?: return Outcome.NO_ENTRY
        return if (store.clear(requested)) Outcome.CLEARED else Outcome.STORAGE_FAILED
    }
}
