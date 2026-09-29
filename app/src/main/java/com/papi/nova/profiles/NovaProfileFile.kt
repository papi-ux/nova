package com.papi.nova.profiles

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Write and close a complete snapshot before replacing the prior file in the same directory. */
internal object NovaProfileFile {
    fun write(file: File, json: String, open: (File) -> FileOutputStream) {
        val temporary = File.createTempFile("profiles-", ".tmp", file.parentFile)
        try {
            open(temporary).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // Android's same-directory File.renameTo uses the filesystem's atomic rename.
            // The old file remains intact when any write, sync, close or rename fails.
            if (!temporary.renameTo(file)) throw IOException("Could not replace saved profiles")
        } finally {
            temporary.delete()
        }
    }
}
