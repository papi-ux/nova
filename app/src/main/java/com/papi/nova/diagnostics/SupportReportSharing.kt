package com.papi.nova.diagnostics

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.papi.nova.LimeLog
import java.io.File

/**
 * Hands a support report to the user, and only to the user.
 *
 * The report is written into the app's cache and offered through the system
 * share sheet, so the destination is always the user's choice. Nova never picks
 * a recipient, which is what keeps this a handoff rather than telemetry.
 */
object SupportReportSharing {
    const val REPORT_FILE_NAME = "nova-support-report.md"
    const val MIME_TYPE = "text/markdown"

    /**
     * Build the current report text for this device.
     */
    @JvmStatic
    @JvmOverloads
    fun buildReport(activity: Activity, version: String, userNotes: String = ""): String =
        NovaSupportReport.build(
            generatedAt = NovaDiagnostics.utcTimestamp(),
            version = version,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidRelease = Build.VERSION.RELEASE ?: "",
            crash = NovaDiagnostics.previousCrash(activity),
            logTail = NovaDiagnostics.readLogTail(activity),
            userNotes = userNotes,
        )

    /**
     * Write the report and open the share sheet.
     *
     * @return true when a chooser was launched.
     */
    @JvmStatic
    @JvmOverloads
    fun share(activity: Activity, version: String, userNotes: String = ""): Boolean {
        return try {
            val file = File(activity.cacheDir, REPORT_FILE_NAME)
            file.writeText(buildReport(activity, version, userNotes))

            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Nova support report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Send Nova support report"))
            true
        } catch (error: Throwable) {
            LimeLog.warning("Diagnostics: could not share the support report: ${error.message}")
            false
        }
    }
}
