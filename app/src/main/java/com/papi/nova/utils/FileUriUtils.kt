package com.papi.nova.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object FileUriUtils {
    @JvmStatic
    fun openUriForRead(context: Context, uri: Uri?): String {
        if (uri == null) return ""

        val result = StringBuilder()
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { result.append(it) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result.toString()
    }

    @JvmStatic
    fun openUriForWrite(context: Context, uri: Uri?, content: String): Boolean {
        if (uri == null) return false

        return try {
            val outputStream = context.contentResolver.openOutputStream(uri) ?: return false
            outputStream.use {
                outputStream.write(content.toByteArray())
                outputStream.flush()
            }
            true
        } catch (e: Exception) {
            e.localizedMessage
            false
        }
    }

    @JvmStatic
    fun writerFileString(file: File, content: String): Boolean {
        var fileOutputStream: FileOutputStream? = null
        return try {
            fileOutputStream = FileOutputStream(file)
            fileOutputStream.write(content.toByteArray())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }
}
