package com.papi.nova.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.papi.nova.R
import java.io.File
import java.util.UUID

/**
 * Hands a moment report to the user through the share sheet, and only there.
 *
 * Each report is its own file, written once and never rewritten, so a recipient still holding a
 * read grant for one moment never receives a different moment's bytes under the same name. The
 * folder is Nova's own cache and keeps a bounded number of the newest reports. Nova never picks a
 * recipient; nothing is sent until the user chooses one.
 */
object NovaMomentShare {
    const val DIRECTORY = "moment-reports"
    const val MIME_TYPE = "text/markdown"
    /** Reports kept in the cache, newest first; older ones are removed when a new one is written. */
    const val MAX_RETAINED = 6

    sealed class Result {
        /** The chooser is open with [file]; nothing has been sent. */
        data class Offered(val file: File) : Result()
        data class Failed(val reason: Reason) : Result()
    }

    enum class Reason { STORAGE, NO_SHARE_TARGET }

    @JvmStatic
    fun directory(context: Context): File = File(context.cacheDir, DIRECTORY)

    /**
     * Write [bytes] as a new report file. Never replaces an existing file; prunes older reports past
     * [MAX_RETAINED]. Returns null when storage refuses.
     */
    @JvmStatic
    fun write(directory: File, markedAtUtc: String, bytes: ByteArray): File? = try {
        if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("no report directory")
        val stamp = markedAtUtc.filter { it.isLetterOrDigit() }.take(16).ifEmpty { "unknown" }
        val file = File(directory, "nova-moment-$stamp-${UUID.randomUUID()}.md")
        // createNewFile is the guarantee that this never lands on another moment's file.
        if (!file.createNewFile()) throw java.io.IOException("report name taken")
        file.writeBytes(bytes)
        file.setReadOnly()
        prune(directory, keep = file)
        file
    } catch (error: Exception) {
        null
    }

    /** Keep the newest [MAX_RETAINED] report files, always including [keep]. */
    @JvmStatic
    fun prune(directory: File, keep: File) {
        val reports = directory.listFiles { file -> file.isFile && file.name.startsWith("nova-moment-") && file.name.endsWith(".md") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        reports.filter { it != keep }.drop(MAX_RETAINED - 1).forEach { it.delete() }
    }

    /** The share intent for [uri]: one attachment, read-only grant, a user-chosen recipient. */
    @JvmStatic
    fun sendIntent(context: Context, uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.nova_moment_report_subject))
        // ClipData carries the grant through the chooser to whichever app the user picks.
        clipData = ClipData.newUri(context.contentResolver, context.getString(R.string.nova_moment_report_subject), uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Open the chooser for an already written report. Only ever called from a user's press. */
    @JvmStatic
    fun offer(activity: Activity, file: File): Result {
        val uri = try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        } catch (error: Exception) {
            return Result.Failed(Reason.STORAGE)
        }
        val chooser = Intent.createChooser(sendIntent(activity, uri), activity.getString(R.string.nova_moment_report_share_title))
        return try {
            activity.startActivity(chooser)
            Result.Offered(file)
        } catch (error: android.content.ActivityNotFoundException) {
            Result.Failed(Reason.NO_SHARE_TARGET)
        } catch (error: SecurityException) {
            Result.Failed(Reason.NO_SHARE_TARGET)
        }
    }
}
