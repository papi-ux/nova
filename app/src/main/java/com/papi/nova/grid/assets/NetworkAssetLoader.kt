package com.papi.nova.grid.assets

import android.content.Context
import com.papi.nova.LimeLog
import com.papi.nova.binding.PlatformBinding
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.utils.ServerHelper
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class NetworkAssetLoader(
    private val context: Context,
    private val uniqueId: String
) {
    // Cache NvHTTP instances per computer UUID to avoid repeated TLS/crypto setup.
    private val httpCache = ConcurrentHashMap<String, NvHTTP>()

    // Track in-flight network loads to avoid duplicate parallel fetches for the same image.
    private val inFlightLoads = ConcurrentHashMap<String, Boolean>()

    /**
     * Check if a load is already in progress for this tuple.
     * Returns true if this caller should proceed, false if another thread is already loading it.
     */
    fun tryAcquire(tuple: CachedAppAssetLoader.LoaderTuple): Boolean {
        return inFlightLoads.putIfAbsent(constructKey(tuple), true) == null
    }

    /** Release the in-flight lock after loading completes (success or failure). */
    fun release(tuple: CachedAppAssetLoader.LoaderTuple) {
        inFlightLoads.remove(constructKey(tuple))
    }

    fun getBitmapStream(tuple: CachedAppAssetLoader.LoaderTuple): InputStream? {
        var input: InputStream? = null
        try {
            val http = getHttpClient(tuple)
            if (http != null) {
                input = http.getBoxArt(tuple.app)
            }
        } catch (_: IOException) {
        }

        if (input != null) {
            LimeLog.info("Network asset load complete: $tuple")
        } else {
            LimeLog.info("Network asset load failed: $tuple")
        }

        return input
    }

    /** Clear cached HTTP clients (call when computer list changes). */
    fun invalidate() {
        httpCache.clear()
        inFlightLoads.clear()
    }

    private fun getHttpClient(tuple: CachedAppAssetLoader.LoaderTuple): NvHTTP? {
        val uuid = tuple.computer.uuid
        val cached = httpCache[uuid]
        if (cached != null) {
            return cached
        }

        val created = try {
            NvHTTP(
                ServerHelper.getCurrentAddressFromComputer(tuple.computer),
                tuple.computer.httpsPort,
                uniqueId,
                tuple.computer.serverCert,
                PlatformBinding.getCryptoProvider(context)
            )
        } catch (_: IOException) {
            null
        }

        if (created != null) {
            return httpCache.putIfAbsent(uuid, created) ?: created
        }

        return null
    }

    private fun constructKey(tuple: CachedAppAssetLoader.LoaderTuple): String =
        tuple.computer.uuid + ":" + tuple.app.appId
}
