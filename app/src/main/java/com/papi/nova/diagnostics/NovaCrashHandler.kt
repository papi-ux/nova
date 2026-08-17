package com.papi.nova.diagnostics

import java.io.File

/**
 * Records an uncaught exception before the process goes down, then hands the
 * crash back to whoever was handling it before.
 *
 * Delegating rather than swallowing matters: Android's own handler is what
 * shows the user the crash dialog and reports to the platform. This only adds a
 * durable local record so the next launch can offer to report it, and so a
 * client-side crash has something a host bundle can be correlated against.
 *
 * Everything here is defensive. A failure while recording a crash must never
 * replace the original crash, because the original one is the useful one.
 */
class NovaCrashHandler(
    private val markerFile: File,
    private val describe: () -> CrashContext,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val clock: () -> String,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val context = describe()
            val record = CrashRecord(
                occurredAt = clock(),
                version = context.version,
                device = context.device,
                androidRelease = context.androidRelease,
                thread = thread.name ?: "",
                stackTrace = stackTraceOf(error),
            )
            markerFile.parentFile?.mkdirs()
            markerFile.writeText(CrashMarker.format(record))
        } catch (ignored: Throwable) {
            // Recording is best effort. Losing the marker is survivable; losing
            // the platform's own crash handling is not.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        @JvmStatic
        fun stackTraceOf(error: Throwable): String {
            val writer = java.io.StringWriter()
            java.io.PrintWriter(writer).use { error.printStackTrace(it) }
            return writer.toString()
        }
    }
}

/** The device facts worth recording alongside a crash. */
data class CrashContext(
    val version: String,
    val device: String,
    val androidRelease: String,
)
