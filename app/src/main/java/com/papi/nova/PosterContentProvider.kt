package com.papi.nova

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

class PosterContentProvider : ContentProvider() {
    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uriMatcher.match(uri) != BOXART_URI_ID) {
            throw FileNotFoundException()
        }
        return openBoxArtFile(uri, mode)
    }

    @Throws(FileNotFoundException::class)
    fun openBoxArtFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw UnsupportedOperationException("This provider is only for read mode")
        }

        val segments = uri.pathSegments
        if (segments.size != 3) {
            throw FileNotFoundException()
        }

        val appId = segments[APP_ID_PATH_INDEX]
        val uuid = segments[COMPUTER_UUID_PATH_INDEX]
        val parsedAppId: Int
        val parsedUuid: UUID
        try {
            parsedUuid = UUID.fromString(uuid)
            parsedAppId = appId.toInt()
            if (parsedAppId < 0) {
                throw NumberFormatException("Negative app ID")
            }
        } catch (e: NumberFormatException) {
            throw FileNotFoundException()
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException()
        }

        val file: File
        try {
            val providerContext = context ?: throw IOException("Missing provider context")
            val boxArtRoot = File(providerContext.cacheDir, BOXART_PATH).canonicalFile
            val uuidDir = File(boxArtRoot, parsedUuid.toString()).canonicalFile
            file = File(uuidDir, "$parsedAppId.png").canonicalFile

            val boxArtRootPath = boxArtRoot.path
            val filePath = file.path
            if (!filePath.startsWith(boxArtRootPath + File.separator)) {
                throw IOException("Box art path escapes cache")
            }
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException()
        } catch (e: IOException) {
            throw FileNotFoundException()
        }

        if (file.isFile) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        throw FileNotFoundException()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("This provider is only for read mode")
    }

    override fun getType(uri: Uri): String = PNG_MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("This provider is only for read mode")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        throw UnsupportedOperationException("This provider doesn't support query")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("This provider is support read only")
    }

    companion object {
        @JvmField
        val AUTHORITY: String = "poster." + BuildConfig.APPLICATION_ID
        const val PNG_MIME_TYPE = "image/png"
        const val APP_ID_PATH_INDEX = 2
        const val COMPUTER_UUID_PATH_INDEX = 1

        private const val BOXART_PATH = "boxart"
        private const val BOXART_URI_ID = 1
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "$BOXART_PATH/*/*", BOXART_URI_ID)
        }

        @JvmStatic
        fun createBoxArtUri(uuid: String, appId: String): Uri =
            Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(BOXART_PATH)
                .appendPath(uuid)
                .appendPath(appId)
                .build()
    }
}
