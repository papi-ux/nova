package com.papi.nova

import java.io.IOException
import java.util.logging.FileHandler
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

object LimeLog {
    private val logger: Logger = Logger.getLogger(LimeLog::class.java.name)

    @JvmStatic
    fun info(msg: String) {
        logger.info(msg)
    }

    @JvmStatic
    fun warning(msg: String) {
        logger.warning(msg)
    }

    @JvmStatic
    fun severe(msg: String) {
        logger.severe(msg)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun setFileHandler(fileName: String) {
        logger.addHandler(FileHandler(fileName))
    }

    /**
     * Log to a size-bounded, rotating file.
     *
     * The unbounded [setFileHandler] is kept for callers that want one file, but
     * a handheld cannot afford a log that grows for the life of the install, and
     * an unbounded log is also unattachable to a bug report. The default
     * formatter here is XML, which no one reading a support report wants, so
     * this uses the plain one.
     *
     * @param pathPrefix Base path; rotation suffixes are appended to it.
     * @param maxBytes Size bound per file.
     * @param fileCount How many rotated files to retain.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun setRotatingFileHandler(pathPrefix: String, maxBytes: Int, fileCount: Int) {
        val handler = FileHandler("$pathPrefix.%g", maxBytes, fileCount, true)
        handler.formatter = SimpleFormatter()
        logger.addHandler(handler)
    }
}
