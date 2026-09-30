package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus

/**
 * Live Tuning's switch, from its split's confirm to the result on its row, for one opening of the
 * Command Center (review findings 5 and 7).
 *
 * [request] asks the host for the state the split offered when it armed, never a flip of what the
 * host says at that moment: another device may have switched it since, and a flip turned Turn Off
 * into On. The host rewrites polaris.conf for every device. When it does not confirm the save,
 * [unconfirmed] holds what was asked for, for [shownMs], and the row's caption says what the host
 * reports now, which its chip shows ([NovaQuickMenuUiState]); that is said in place, where a
 * snackbar had floated over the stream. A save the host applied whose answer was lost is no
 * failure there, since the state it reports is the one asked for.
 */
internal class NovaLiveTuningSave(
    /** Runs [block] off the main thread. */
    private val launch: (block: suspend () -> Unit) -> Unit,
    /** Runs [block] on the main thread while this opening of the Command Center stands. */
    private val onMain: suspend (block: () -> Unit) -> Unit,
    /** Runs [block] on the main thread after [delayMs]. */
    private val later: (delayMs: Long, block: () -> Unit) -> Unit,
    /** Asks the host for [enable] against the status it was asked on; true once the host confirms. */
    private val save: (enable: Boolean, observed: PolarisSessionStatus) -> Boolean,
    /** Fetches the host's status again, off the main thread. */
    private val fetch: () -> Unit,
    /** Publishes what [fetch] got, on the main thread. */
    private val publish: () -> Unit,
    /** The page shows what changed. */
    private val changed: () -> Unit,
    private val shownMs: Long = SHOWN_MS,
) {
    /** A save is on its way; a second confirm does nothing until it lands. */
    var pending: Boolean = false
        private set

    /** What a save the host did not confirm asked for, while its row says so; null otherwise. */
    var unconfirmed: Boolean? = null
        private set

    // Counted, so an earlier result's timer never cuts a later one short.
    private var results = 0

    /** Asks the host to turn Live Tuning On for [enable] true, Off for false, as the split offered. */
    fun request(enable: Boolean, observed: PolarisSessionStatus) {
        if (pending) return
        pending = true
        unconfirmed = null
        changed()
        launch {
            val confirmed = save(enable, observed)
            fetch()
            onMain {
                pending = false
                publish()
                if (!confirmed) {
                    unconfirmed = enable
                    val shown = ++results
                    later(shownMs) {
                        if (results == shown) {
                            unconfirmed = null
                            changed()
                        }
                    }
                }
                changed()
            }
        }
    }

    companion object {
        /** How long a result stays in its row's caption, as Clear Game Profile's does. */
        const val SHOWN_MS = 4_000L
    }
}
