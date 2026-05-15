package com.papi.nova

import java.io.IOException
import java.util.logging.FileHandler
import java.util.logging.Logger

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
}
