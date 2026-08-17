package com.papi.nova.diagnostics

import android.content.Context
import android.os.Build
import com.papi.nova.LimeLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Wires Nova's local evidence: a bounded log file and a crash marker.
 *
 * Nova kept no log of its own. LimeLog wrote to java.util.logging with no file
 * handler ever installed, so everything went to logcat and vanished, and a user
 * reporting a bug had nothing to attach unless they owned a computer and knew
 * about adb. A report with a stack trace and no log around it says what broke
 * and not what led to it.
 */
object NovaDiagnostics {
    /** Two files at this size is enough to hold a session without filling a handheld. */
    const val LOG_MAX_BYTES = 512 * 1024
    const val LOG_FILE_COUNT = 2
    const val LOG_DIRECTORY = "diagnostics"
    const val LOG_BASE_NAME = "nova.log"

    private var installed = false

    /**
     * Install the log file and crash handler. Safe to call more than once.
     *
     * @param context Application context; its private files directory is used so
     *                nothing here is world readable.
     * @param version Version name to record against a crash.
     */
    @JvmStatic
    @Synchronized
    fun install(context: Context, version: String) {
        if (installed) return
        installed = true

        val directory = diagnosticsDirectory(context)
        try {
            directory.mkdirs()
            LimeLog.setRotatingFileHandler(
                File(directory, LOG_BASE_NAME).path,
                LOG_MAX_BYTES,
                LOG_FILE_COUNT,
            )
        } catch (error: Throwable) {
            // A device that will not give us a log file is not a reason to fail
            // startup, and the crash handler below is still worth having.
            LimeLog.warning("Diagnostics: could not open the local log file: ${error.message}")
        }

        val markerFile = crashMarkerFile(context)
        Thread.setDefaultUncaughtExceptionHandler(
            NovaCrashHandler(
                markerFile = markerFile,
                describe = {
                    CrashContext(
                        version = version,
                        device = "${Build.MANUFACTURER} ${Build.MODEL}",
                        androidRelease = Build.VERSION.RELEASE ?: "",
                    )
                },
                previous = Thread.getDefaultUncaughtExceptionHandler(),
                clock = { utcTimestamp() },
            )
        )
    }

    @JvmStatic
    fun diagnosticsDirectory(context: Context): File = File(context.filesDir, LOG_DIRECTORY)

    @JvmStatic
    fun crashMarkerFile(context: Context): File =
        File(diagnosticsDirectory(context), CrashMarker.FILE_NAME)

    /**
     * The crash recorded by the previous run, if there was one.
     */
    @JvmStatic
    fun previousCrash(context: Context): CrashRecord? {
        val file = crashMarkerFile(context)
        return try {
            if (!file.isFile) null else CrashMarker.parse(file.readText())
        } catch (error: Throwable) {
            null
        }
    }

    /**
     * Forget the recorded crash once the user has been offered it.
     *
     * Unlike the host, the marker is consumed here. Nova has no run-state to
     * match it against, so leaving it in place would re-offer the same crash on
     * every launch.
     */
    @JvmStatic
    fun clearPreviousCrash(context: Context) {
        try {
            crashMarkerFile(context).delete()
        } catch (ignored: Throwable) {
            // Nothing useful to do; the prompt is already dismissed.
        }
    }

    /**
     * The current bounded log, redacted, for attaching to a report.
     */
    @JvmStatic
    fun readLogTail(context: Context, maxChars: Int = LOG_MAX_BYTES): String {
        val directory = diagnosticsDirectory(context)
        val text = try {
            directory.listFiles { file -> file.name.startsWith(LOG_BASE_NAME) }
                ?.sortedByDescending { it.lastModified() }
                ?.take(LOG_FILE_COUNT)
                ?.joinToString("\n") { it.readText() }
                ?: ""
        } catch (error: Throwable) {
            ""
        }
        val bounded = if (text.length > maxChars) text.takeLast(maxChars) else text
        return DiagnosticsRedaction.redact(bounded)
    }

    @JvmStatic
    fun utcTimestamp(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
