package com.papi.nova.utils

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

object CacheHelper {
    @JvmStatic
    fun openPath(createPath: Boolean, root: File?, vararg path: String?): File {
        val nonNullRoot = requireNotNull(root) { "Root cannot be null" }

        val canonicalRoot = try {
            nonNullRoot.canonicalFile
        } catch (e: IOException) {
            throw IllegalArgumentException("Unable to resolve cache root", e)
        }

        var file = canonicalRoot
        path.forEachIndexed { index, component ->
            val safeComponent = component
            if (
                safeComponent == null ||
                safeComponent.isEmpty() ||
                safeComponent == "." ||
                safeComponent.contains("..") ||
                safeComponent.indexOf('/') != -1 ||
                safeComponent.indexOf('\\') != -1
            ) {
                throw IllegalArgumentException("Invalid cache path component")
            }

            if (index == path.lastIndex && createPath) {
                file.mkdirs()
            }
            file = File(file, safeComponent)
        }

        return try {
            val canonicalFile = file.canonicalFile
            val rootPath = canonicalRoot.path
            val filePath = canonicalFile.path
            if (filePath != rootPath && !filePath.startsWith(rootPath + File.separator)) {
                throw IllegalArgumentException("Cache path escapes root")
            }
            canonicalFile
        } catch (e: IOException) {
            throw IllegalArgumentException("Unable to resolve cache path", e)
        }
    }

    @JvmStatic
    fun getFileSize(root: File, vararg path: String): Long {
        return openPath(false, root, *path).length()
    }

    @JvmStatic
    fun deleteCacheFile(root: File, vararg path: String): Boolean {
        return openPath(false, root, *path).delete()
    }

    @JvmStatic
    fun cacheFileExists(root: File, vararg path: String): Boolean {
        return openPath(false, root, *path).exists()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun openCacheFileForInput(root: File, vararg path: String?): InputStream {
        val canonicalRoot = root.canonicalFile
        var file = canonicalRoot
        for (component in path) {
            val safeComponent = component
            if (
                safeComponent == null ||
                safeComponent.isEmpty() ||
                safeComponent == "." ||
                safeComponent.contains("..") ||
                safeComponent.indexOf('/') != -1 ||
                safeComponent.indexOf('\\') != -1
            ) {
                throw FileNotFoundException("Invalid cache path component")
            }
            file = File(file, safeComponent)
        }

        val canonicalFile = file.canonicalFile
        val rootPath = canonicalRoot.path
        val filePath = canonicalFile.path
        if (filePath != rootPath && !filePath.startsWith(rootPath + File.separator)) {
            throw FileNotFoundException("Cache path escapes root")
        }

        return BufferedInputStream(FileInputStream(canonicalFile))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun openCacheFileForOutput(root: File, vararg path: String?): OutputStream {
        val canonicalRoot = root.canonicalFile
        var file = canonicalRoot
        for (component in path) {
            val safeComponent = component
            if (
                safeComponent == null ||
                safeComponent.isEmpty() ||
                safeComponent == "." ||
                safeComponent.contains("..") ||
                safeComponent.indexOf('/') != -1 ||
                safeComponent.indexOf('\\') != -1
            ) {
                throw FileNotFoundException("Invalid cache path component")
            }
            file = File(file, safeComponent)
        }

        val canonicalFile = file.canonicalFile
        val rootPath = canonicalRoot.path
        val filePath = canonicalFile.path
        if (filePath != rootPath && !filePath.startsWith(rootPath + File.separator)) {
            throw FileNotFoundException("Cache path escapes root")
        }

        val parent = canonicalFile.parentFile
        if (parent == null || (!parent.isDirectory && !parent.mkdirs())) {
            throw FileNotFoundException("Unable to create cache parent path")
        }

        return BufferedOutputStream(FileOutputStream(canonicalFile))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun writeInputStreamToOutputStream(input: InputStream, output: OutputStream, maxLength: Long) {
        val buffer = ByteArray(4096)
        var remaining = maxLength
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) {
                return
            }
            remaining -= bytesRead.toLong()
            if (remaining <= 0) {
                throw IOException("Stream exceeded max size")
            }
            output.write(buffer, 0, bytesRead)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readInputStreamToString(input: InputStream): String {
        val reader = InputStreamReader(input)
        val builder = StringBuilder()
        val buffer = CharArray(256)
        while (true) {
            val bytesRead = reader.read(buffer)
            if (bytesRead == -1) {
                break
            }
            builder.append(buffer, 0, bytesRead)
        }

        try {
            input.close()
        } catch (_: IOException) {
        }

        return builder.toString()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun writeStringToOutputStream(output: OutputStream, value: String) {
        output.write(value.toByteArray(Charsets.UTF_8))
    }
}
