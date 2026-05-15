package com.papi.nova.grid.assets

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import com.papi.nova.LimeLog
import com.papi.nova.utils.CacheHelper
import java.io.File
import java.io.IOException
import java.io.InputStream

class DiskAssetLoader(context: Context) {
    private val isLowRamDevice: Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    private val cacheDir: File = context.cacheDir

    fun checkCacheExists(tuple: CachedAppAssetLoader.LoaderTuple): Boolean =
        CacheHelper.cacheFileExists(cacheDir, "boxart", tuple.computer.uuid, tuple.app.appId.toString() + ".png")

    fun loadBitmapFromCache(tuple: CachedAppAssetLoader.LoaderTuple, sampleSize: Int): ScaledBitmap? {
        val file = getFile(tuple.computer.uuid, tuple.app.appId)

        // Don't bother with anything if it doesn't exist.
        if (!file.exists()) {
            return null
        }

        // Make sure the cached asset doesn't exceed the maximum size.
        if (file.length() > MAX_ASSET_SIZE) {
            LimeLog.warning("Removing cached tuple exceeding size threshold: $tuple")
            file.delete()
            return null
        }

        // For OSes prior to P, we have to use the ugly BitmapFactory API.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val decodeOnlyOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOnlyOptions)
            if (decodeOnlyOptions.outWidth <= 0 || decodeOnlyOptions.outHeight <= 0) {
                return null
            }

            LimeLog.info("Tuple $tuple has cached art of size: ${decodeOnlyOptions.outWidth}x${decodeOnlyOptions.outHeight}")

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(
                    decodeOnlyOptions,
                    STANDARD_ASSET_WIDTH / sampleSize,
                    STANDARD_ASSET_HEIGHT / sampleSize
                )
                if (isLowRamDevice) {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inDither = true
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    inPreferredConfig = Bitmap.Config.HARDWARE
                }
            }

            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            if (bitmap != null) {
                LimeLog.info("Tuple $tuple decoded from disk cache with sample size: ${options.inSampleSize}")
                return ScaledBitmap(decodeOnlyOptions.outWidth, decodeOnlyOptions.outHeight, bitmap)
            }
        } else {
            val scaledBitmap = ScaledBitmap()
            try {
                scaledBitmap.bitmap = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(file)
                ) { imageDecoder, imageInfo, _ ->
                    scaledBitmap.originalWidth = imageInfo.size.width
                    scaledBitmap.originalHeight = imageInfo.size.height

                    val aspectRatio = scaledBitmap.originalWidth.toFloat() / scaledBitmap.originalHeight
                    val standardAspectRatio = STANDARD_ASSET_WIDTH.toFloat() / STANDARD_ASSET_HEIGHT
                    var targetWidth = STANDARD_ASSET_WIDTH
                    var targetHeight = STANDARD_ASSET_HEIGHT

                    if (aspectRatio < standardAspectRatio) {
                        targetHeight = (standardAspectRatio / aspectRatio * targetHeight).toInt()
                    } else {
                        targetWidth = (aspectRatio / standardAspectRatio * targetWidth).toInt()
                    }
                    imageDecoder.setTargetSize(targetWidth, targetHeight)
                    if (isLowRamDevice) {
                        imageDecoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
                    }
                }
                return scaledBitmap
            } catch (e: IOException) {
                e.printStackTrace()
                return null
            }
        }

        return null
    }

    fun getFile(computerUuid: String, appId: Int): File =
        CacheHelper.openPath(false, cacheDir, "boxart", computerUuid, "$appId.png")

    fun deleteAssetsForComputer(computerUuid: String) {
        val dir = CacheHelper.openPath(false, cacheDir, "boxart", computerUuid)
        val files = dir.listFiles()
        if (files != null) {
            for (file in files) {
                file.delete()
            }
        }
    }

    fun populateCacheWithStream(tuple: CachedAppAssetLoader.LoaderTuple, input: InputStream) {
        var success = false
        try {
            CacheHelper.openCacheFileForOutput(
                cacheDir,
                "boxart",
                tuple.computer.uuid,
                tuple.app.appId.toString() + ".png"
            ).use { output ->
                CacheHelper.writeInputStreamToOutputStream(input, output, MAX_ASSET_SIZE)
                success = true
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            if (!success) {
                LimeLog.warning("Unable to populate cache with tuple: $tuple")
                CacheHelper.deleteCacheFile(cacheDir, "boxart", tuple.computer.uuid, tuple.app.appId.toString() + ".png")
            }
        }
    }

    companion object {
        private const val MAX_ASSET_SIZE = 5L * 1024L * 1024L
        private const val STANDARD_ASSET_WIDTH = 300
        private const val STANDARD_ASSET_HEIGHT = 400

        // https://developer.android.com/topic/performance/graphics/load-bitmap.html
        @JvmStatic
        fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val height = options.outHeight
            val width = options.outWidth
            var inSampleSize = 1

            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2

                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            return inSampleSize
        }
    }
}
