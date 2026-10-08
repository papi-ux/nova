package com.papi.nova.diagnostics

import com.papi.nova.ui.NovaCommandCenterRuntime
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * Report This Moment, from press to a saved report, and from the user's own Share to the chooser.
 *
 * The press freezes the moment on the main thread ([freeze]): the snapshot, the values to scrub
 * and the log held open where it ended. Only then does work leave the main thread, to read the
 * bounded log and save the report ([prepare]). Back on the main thread, while the stream's
 * runtime still stands, the saved report is confirmed in the stream ([saved]); nothing else
 * opens. Opening another app can pause or stop the stream (Game stops its connection when it
 * stops), so the chooser opens only from that confirmation's explicit Share ([share]), with the
 * saved file's bytes, never a fresh sample. Nothing is ever sent by Nova.
 *
 * A newer press retires an older handoff; [retire] (teardown) retires every handoff and closes
 * any log still held.
 */
internal class NovaMomentReporter(
    private val runtime: NovaCommandCenterRuntime,
    private val freeze: () -> Frozen?,
    private val prepare: (Frozen) -> File?,
    private val saved: (Saved) -> Unit,
    private val offer: (File) -> NovaMomentShare.Result,
    private val feedback: (Outcome) -> Unit,
    private val isActive: () -> Boolean = { true },
) {
    /** Everything the report says, fixed at the press. [log] is held open until read or retired. */
    class Frozen(
        val snapshot: NovaMomentReport.Snapshot,
        val known: Map<String, String>,
        val log: NovaMomentLog.Marked,
    )

    /** A report saved for one moment, ready for the user to share or not. */
    data class Saved(val file: File, val markedAtUtc: String)

    enum class Outcome { NOT_ACTIVE, STORAGE_FAILED, OFFERED, NO_SHARE_TARGET }

    private var generation = 0L
    private val held: MutableSet<NovaMomentLog.Marked> = Collections.synchronizedSet(LinkedHashSet())

    /** Main thread, from the user's press. Saves; never opens another app. */
    fun report() {
        if (!isActive()) {
            feedback(Outcome.NOT_ACTIVE)
            return
        }
        // A new mark owns publication and releases any older held descriptors,
        // including IO work that was cancelled before entering its body.
        retire()
        val frozen = freeze()
        if (frozen == null) {
            feedback(Outcome.NOT_ACTIVE)
            return
        }
        held += frozen.log
        val mine = ++generation
        runtime.launchIo(TASK) {
            val file = try {
                prepare(frozen)
            } catch (error: Exception) {
                null
            } finally {
                frozen.log.close()
                held -= frozen.log
            }
            runtime.onMain {
                // A newer press, or teardown, retired this handoff; its file stays unshared.
                if (mine != generation || !isActive()) return@onMain
                if (file == null) feedback(Outcome.STORAGE_FAILED) else saved(Saved(file, frozen.snapshot.markedAtUtc))
            }
        }
    }

    /** Main thread, from the confirmation's explicit Share. Shares the saved bytes as they are. */
    fun share(saved: Saved) {
        if (!isActive()) {
            feedback(Outcome.NOT_ACTIVE)
            return
        }
        feedback(
            when (val result = offer(saved.file)) {
                is NovaMomentShare.Result.Offered -> Outcome.OFFERED
                is NovaMomentShare.Result.Failed -> when (result.reason) {
                    NovaMomentShare.Reason.STORAGE -> Outcome.STORAGE_FAILED
                    NovaMomentShare.Reason.NO_SHARE_TARGET -> Outcome.NO_SHARE_TARGET
                }
            }
        )
    }

    /** Retire every handoff in flight and close any log still held, for a stream going away. */
    fun retire() {
        generation++
        synchronized(held) {
            held.forEach { it.close() }
            held.clear()
        }
    }

    companion object {
        const val TASK = "NovaMomentReport"

        /** The IO half: the bounded log up to the frozen ends, the rendered report, its own file. */
        @JvmStatic
        fun prepare(directory: File, frozen: Frozen): File? {
            val tail = frozen.log.read(frozen.known)
            val text = NovaMomentReport.render(frozen.snapshot, tail)
            return NovaMomentShare.write(directory, frozen.snapshot.markedAtUtc, text.toByteArray(StandardCharsets.UTF_8))
        }
    }
}
