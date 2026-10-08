package com.papi.nova.diagnostics

import com.papi.nova.ui.NovaCommandCenterRuntime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Working Baseline, from the Command Center's row to the store.
 *
 * Opening the row and Compare only read. Save and Replace are the user's statement, made on a
 * confirmation, that this stream works well for them. Everything the save needs is taken on the
 * main thread at that confirmation's press ([save]): the stream is checked again and the snapshot
 * is frozen there, so readings from before a long wait on the confirmation are never what gets
 * saved, and nothing from the stream is read later. Only the frozen, immutable [PendingSave] then
 * goes to the runtime's IO work to be written; Clear ([clear]) likewise. Its outcome comes back on
 * the main thread, and only to the opening that asked ([retire] ends one).
 *
 * One change at a time: while a Save or Clear is outstanding, another is refused ([busy]), from
 * this opening or a later one, until that write has actually finished. Closing the menu drops only
 * its answer, so an older queued write can never land after, and overwrite, a newer one. Nothing here reads logs, writes files, asks the host
 * anything, or touches Doctor, tuning, bitrate, profiles or launch settings.
 */
internal class NovaWorkingBaselineFlow(
    private val store: NovaWorkingBaselineStore,
    private val runtime: NovaCommandCenterRuntime,
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
        /** A Save or Clear from this opening is still being written. */
        val busy: Boolean = false,
        /**
         * A Save or Clear would also remove stored baselines that can't be read or attributed any
         * more, or (Save only) the oldest baseline, to stay within the limit.
         */
        val unrecoverableOthers: Boolean = false,
        val saveEvictsOldest: Boolean = false,
    ) {
        val saved: NovaWorkingBaseline.Baseline? get() = (loaded as? NovaWorkingBaselineStore.Loaded.Found)?.baseline
        val canSave: Boolean get() = hasEntry && active && !busy
        val canCompare: Boolean get() = hasEntry && active && saved != null && !busy
        val canClear: Boolean get() = hasEntry && !busy && loaded != NovaWorkingBaselineStore.Loaded.None
    }

    enum class Outcome { SAVED, REPLACED, CLEARED, NOT_ACTIVE, NO_ENTRY, READINGS_UNAVAILABLE, STORAGE_FAILED, BUSY }

    sealed interface Compared {
        data class Shown(val comparison: NovaWorkingBaseline.Comparison) : Compared
        object NoEntry : Compared
        object NotActive : Compared
        object NoBaseline : Compared
        object Unreadable : Compared
    }

    /** What a confirmed Save froze on the main thread; the only thing the write ever sees. */
    class PendingSave(val entry: NovaWorkingBaseline.RequestedEntry, val snapshot: NovaMomentReport.Snapshot)

    /** Main thread only: which opening an answer belongs to. */
    private var generation = 0L
    /** Set at the press that queues a write; cleared only when that write has finished. */
    private val pending = AtomicBoolean(false)

    val busy: Boolean get() = pending.get()

    /** Refresh-only listener for the currently open page; never receives a retired outcome. */
    var onWriteFinished: (() -> Unit)? = null

    fun state(): State {
        val requested = entry() ?: return State(hasEntry = false, active = isActive(), loaded = NovaWorkingBaselineStore.Loaded.None, busy = busy)
        val inspection = store.inspect(requested)
        return State(
            hasEntry = true,
            active = isActive(),
            loaded = inspection.loaded,
            busy = busy,
            unrecoverableOthers = inspection.unrecoverableOthers,
            saveEvictsOldest = inspection.saveEvictsOldest,
        )
    }

    /**
     * Main thread, the confirmed Save or Replace press: rechecks the stream and freezes the
     * snapshot now, then writes it off the main thread. [done] runs on the main thread, once,
     * unless this opening was retired first. An answer that needs no write comes back at once.
     */
    fun save(done: (Outcome) -> Unit) {
        val frozen = when (val prepared = prepareSave()) {
            is Prepared.Ready -> prepared.save
            is Prepared.Refused -> return done(prepared.outcome)
        }
        write(done) { store.save(frozen.entry, frozen.snapshot).toOutcome() }
    }

    /** Main thread, the confirmed Clear press. Needs no running stream. */
    fun clear(done: (Outcome) -> Unit) {
        if (busy) return done(Outcome.BUSY)
        val requested = entry() ?: return done(Outcome.NO_ENTRY)
        write(done) { if (store.clear(requested)) Outcome.CLEARED else Outcome.STORAGE_FAILED }
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

    /**
     * The opening that asked is gone (the menu closed, or the stream stopped): a write already
     * queued still finishes, and keeps the next opening [busy] until it has, but its answer is
     * dropped.
     */
    fun retire() {
        generation++
        onWriteFinished = null
    }

    private sealed interface Prepared {
        class Ready(val save: PendingSave) : Prepared
        class Refused(val outcome: Outcome) : Prepared
    }

    /** The prepare half of a Save: main thread, at the press, reading only what it freezes. */
    private fun prepareSave(): Prepared {
        if (busy) return Prepared.Refused(Outcome.BUSY)
        val requested = entry() ?: return Prepared.Refused(Outcome.NO_ENTRY)
        if (!isActive()) return Prepared.Refused(Outcome.NOT_ACTIVE)
        val frozen = snapshot() ?: return Prepared.Refused(Outcome.NOT_ACTIVE)
        if (frozen.readings != NovaMomentReport.Readings.CURRENT) return Prepared.Refused(Outcome.READINGS_UNAVAILABLE)
        return Prepared.Ready(PendingSave(requested, frozen))
    }

    private fun write(done: (Outcome) -> Unit, work: () -> Outcome) {
        if (!pending.compareAndSet(false, true)) return done(Outcome.BUSY)
        val mine = generation
        runtime.launchIo(TASK) {
            val outcome = try {
                work()
            } catch (_: RuntimeException) {
                Outcome.STORAGE_FAILED
            } finally {
                // Finished, whether or not anyone is still there to hear how.
                pending.set(false)
            }
            runtime.onMain {
                if (mine == generation) done(outcome) else onWriteFinished?.invoke()
            }
        }
    }

    private fun NovaWorkingBaselineStore.Saved.toOutcome() = when (this) {
        NovaWorkingBaselineStore.Saved.SAVED -> Outcome.SAVED
        NovaWorkingBaselineStore.Saved.REPLACED -> Outcome.REPLACED
        NovaWorkingBaselineStore.Saved.READINGS_UNAVAILABLE -> Outcome.READINGS_UNAVAILABLE
        NovaWorkingBaselineStore.Saved.STORAGE_FAILED -> Outcome.STORAGE_FAILED
    }

    companion object {
        const val TASK = "NovaWorkingBaseline"
    }
}
