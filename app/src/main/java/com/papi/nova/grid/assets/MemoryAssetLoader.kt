package com.papi.nova.grid.assets

import android.util.LruCache
import com.papi.nova.LimeLog
import java.lang.ref.SoftReference
import java.util.HashMap

class MemoryAssetLoader {
    fun loadBitmapFromCache(tuple: CachedAppAssetLoader.LoaderTuple): ScaledBitmap? {
        val key = constructKey(tuple)

        var bitmap = memoryCache.get(key)
        if (bitmap != null) {
            LimeLog.info("LRU cache hit for tuple: $tuple")
            return bitmap
        }

        val bitmapRef = evictionCache[key]
        if (bitmapRef != null) {
            bitmap = bitmapRef.get()
            if (bitmap != null) {
                LimeLog.info("Eviction cache hit for tuple: $tuple")

                // Put this entry back into the LRU cache.
                evictionCache.remove(key)
                memoryCache.put(key, bitmap)

                return bitmap
            } else {
                // The data is gone, so remove the dangling SoftReference now.
                evictionCache.remove(key)
            }
        }

        return null
    }

    fun populateCache(tuple: CachedAppAssetLoader.LoaderTuple, bitmap: ScaledBitmap) {
        memoryCache.put(constructKey(tuple), bitmap)
    }

    fun clearCache() {
        // We must evict first because that will push all items into the eviction cache.
        memoryCache.evictAll()
        evictionCache.clear()
    }

    companion object {
        private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        private val memoryCache: LruCache<String, ScaledBitmap> =
            object : LruCache<String, ScaledBitmap>(maxMemory / 16) {
                override fun sizeOf(key: String, bitmap: ScaledBitmap): Int {
                    // Sizeof returns kilobytes.
                    return bitmap.bitmap!!.byteCount / 1024
                }

                override fun entryRemoved(
                    evicted: Boolean,
                    key: String,
                    oldValue: ScaledBitmap,
                    newValue: ScaledBitmap?
                ) {
                    super.entryRemoved(evicted, key, oldValue, newValue)

                    if (evicted) {
                        // Keep a soft reference around to the bitmap as long as we can.
                        evictionCache[key] = SoftReference(oldValue)
                    }
                }
            }
        private val evictionCache = HashMap<String, SoftReference<ScaledBitmap>>()

        private fun constructKey(tuple: CachedAppAssetLoader.LoaderTuple): String =
            tuple.computer.uuid + "-" + tuple.app.appId
    }
}
