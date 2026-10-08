package com.papi.nova.diagnostics

import android.app.Activity
import android.content.Context
import com.papi.nova.R
import com.papi.nova.ui.NovaCommandCenterRuntime
import java.io.File
import java.util.UUID

/**
 * Saved stream recap reports: each in its own new, read-only file in Nova's cache
 * ([DIRECTORY]), the newest [MAX_RETAINED] kept, and shared only through the chooser from a
 * user's explicit Share. Moment reports keep their own folder and names; neither touches the
 * other's files.
 */
object NovaStreamRecapExport {
    const val DIRECTORY = "stream-recaps"
    const val MAX_RETAINED = 6
    private const val PREFIX = "nova-stream-recap-"

    @JvmStatic
    fun directory(context: Context): File = File(context.cacheDir, DIRECTORY)

    /**
     * Writes [bytes] as a new report file; never replaces one. Prunes this folder's older reports
     * past [MAX_RETAINED], and only on this explicit save. Null when storage refuses.
     */
    @JvmStatic
    fun write(directory: File, endedAtUtc: String, bytes: ByteArray): File? = try {
        if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("no report directory")
        val stamp = endedAtUtc.filter { it.isLetterOrDigit() }.take(16).ifEmpty { "unknown" }
        val file = File(directory, "$PREFIX$stamp-${UUID.randomUUID()}.md")
        if (!file.createNewFile()) throw java.io.IOException("report name taken")
        file.writeBytes(bytes)
        file.setReadOnly()
        prune(directory, keep = file)
        file
    } catch (_: Exception) {
        null
    }

    /** Keeps the newest [MAX_RETAINED] of this folder's own report files, always including [keep]. */
    @JvmStatic
    fun prune(directory: File, keep: File) {
        directory.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(".md") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
            .filter { it != keep }
            .drop(MAX_RETAINED - 1)
            .forEach { it.delete() }
    }

    /** The chooser for a saved recap report, with the recap's own subject and title. */
    @JvmStatic
    fun offer(activity: Activity, file: File): NovaMomentShare.Result = NovaMomentShare.offer(
        activity,
        file,
        subject = activity.getString(R.string.nova_recap_report_subject),
        title = activity.getString(R.string.nova_recap_report_share_title),
    )
}

/**
 * Save Report, from the recap's page, then Share from the saved confirmation.
 *
 * The press renders the report from the immutable recap on the main thread, so the bytes are fixed
 * before anything leaves it; an invalid recap is refused there and nothing is written. Only those
 * bytes go to IO work to be saved. The confirmation appears back on the main thread, and only for
 * the newest press while the screen that asked is still there ([retire] ends it). The chooser
 * opens only from that confirmation's explicit Share, with the saved file.
 */
internal class NovaStreamRecapReporter(
    private val runtime: NovaCommandCenterRuntime,
    private val render: (NovaStreamRecap.Recap) -> ByteArray?,
    private val write: (endedAtUtc: String, bytes: ByteArray) -> File?,
    private val saved: (Saved) -> Unit,
    private val offer: (File) -> NovaMomentShare.Result,
    private val feedback: (Outcome) -> Unit,
    /** The screen is still in the foreground and can open the chooser. */
    private val canShare: () -> Boolean,
) {
    data class Saved(val file: File, val endedAtUtc: String)

    enum class Outcome { INVALID, STORAGE_FAILED, OFFERED, NO_SHARE_TARGET }

    /** Main thread only. */
    private var generation = 0L

    /** Main thread, the explicit Save Report press. */
    fun report(recap: NovaStreamRecap.Recap) {
        val mine = ++generation
        val bytes = render(recap)
        if (bytes == null) {
            feedback(Outcome.INVALID)
            return
        }
        val endedAtUtc = recap.endedAtUtc
        runtime.launchIo(TASK) {
            val file = try {
                write(endedAtUtc, bytes)
            } catch (_: Exception) {
                null
            }
            runtime.onMain {
                // A newer press, or the screen going away, retired this one.
                if (mine != generation) return@onMain
                if (file == null) feedback(Outcome.STORAGE_FAILED) else saved(Saved(file, endedAtUtc))
            }
        }
    }

    /** Main thread, the saved confirmation's explicit Share. Shares the saved bytes as they are. */
    fun share(report: Saved) {
        if (!canShare()) return
        feedback(
            when (val result = offer(report.file)) {
                is NovaMomentShare.Result.Offered -> Outcome.OFFERED
                is NovaMomentShare.Result.Failed -> when (result.reason) {
                    NovaMomentShare.Reason.STORAGE -> Outcome.STORAGE_FAILED
                    NovaMomentShare.Reason.NO_SHARE_TARGET -> Outcome.NO_SHARE_TARGET
                }
            }
        )
    }

    /** The screen that asked is going: any save still under way finishes, but nothing appears. */
    fun retire() {
        generation++
    }

    companion object {
        const val TASK = "NovaStreamRecapReport"
    }
}
