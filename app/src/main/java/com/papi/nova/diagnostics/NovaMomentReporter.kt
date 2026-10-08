package com.papi.nova.diagnostics

import com.papi.nova.ui.NovaCommandCenterRuntime
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Report This Moment, from press to share sheet.
 *
 * The press freezes the moment on the main thread ([freeze]): the snapshot, the values to scrub
 * and where each log file ended. Only then does work leave the main thread, to read the bounded
 * log and write the report file ([prepare]). The share sheet opens back on the main thread while
 * the stream's runtime still stands ([offer]); a newer press retires an older handoff, and a
 * stream that ended or an Activity that is finishing retires it too. Nothing is ever sent: the
 * user picks a recipient, or doesn't.
 */
internal class NovaMomentReporter(
    private val runtime: NovaCommandCenterRuntime,
    private val freeze: () -> Frozen?,
    private val prepare: (Frozen) -> File?,
    private val offer: (File) -> NovaMomentShare.Result,
    private val feedback: (Outcome) -> Unit,
) {
    /** Everything the report says, fixed at the press. */
    data class Frozen(
        val snapshot: NovaMomentReport.Snapshot,
        val known: Map<String, String>,
        val log: List<NovaMomentLog.Part>,
    )

    enum class Outcome { NOT_ACTIVE, OFFERED, STORAGE_FAILED, NO_SHARE_TARGET }

    private var generation = 0L

    /** Main thread, from the user's press. */
    fun report() {
        val frozen = freeze()
        if (frozen == null) {
            feedback(Outcome.NOT_ACTIVE)
            return
        }
        val mine = ++generation
        runtime.launchIo(TASK) {
            val file = try {
                prepare(frozen)
            } catch (error: Exception) {
                null
            }
            runtime.onMain {
                // A newer press owns the share sheet now; this one's file stays unshared.
                if (mine != generation) return@onMain
                if (file == null) {
                    feedback(Outcome.STORAGE_FAILED)
                    return@onMain
                }
                feedback(
                    when (val result = offer(file)) {
                        is NovaMomentShare.Result.Offered -> Outcome.OFFERED
                        is NovaMomentShare.Result.Failed -> when (result.reason) {
                            NovaMomentShare.Reason.STORAGE -> Outcome.STORAGE_FAILED
                            NovaMomentShare.Reason.NO_SHARE_TARGET -> Outcome.NO_SHARE_TARGET
                        }
                    }
                )
            }
        }
    }

    /** Retire any handoff still in flight, for a stream that is going away. */
    fun retire() {
        generation++
    }

    companion object {
        const val TASK = "NovaMomentReport"

        /** The IO half: the bounded log up to the frozen ends, the rendered report, its own file. */
        @JvmStatic
        fun prepare(directory: File, frozen: Frozen): File? {
            val tail = NovaMomentLog.read(frozen.log, frozen.known)
            val text = NovaMomentReport.render(frozen.snapshot, tail)
            return NovaMomentShare.write(directory, frozen.snapshot.markedAtUtc, text.toByteArray(StandardCharsets.UTF_8))
        }
    }
}
