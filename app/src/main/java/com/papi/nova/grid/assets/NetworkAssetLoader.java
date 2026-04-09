package com.papi.nova.grid.assets;

import android.content.Context;

import com.papi.nova.LimeLog;
import com.papi.nova.binding.PlatformBinding;
import com.papi.nova.nvstream.http.NvHTTP;
import com.papi.nova.utils.ServerHelper;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkAssetLoader {
    private final Context context;
    private final String uniqueId;

    // Cache NvHTTP instances per computer UUID to avoid repeated TLS/crypto setup
    private final ConcurrentHashMap<String, NvHTTP> httpCache = new ConcurrentHashMap<>();

    // Track in-flight network loads to avoid duplicate parallel fetches for the same image
    private final ConcurrentHashMap<String, Boolean> inFlightLoads = new ConcurrentHashMap<>();

    public NetworkAssetLoader(Context context, String uniqueId) {
        this.context = context;
        this.uniqueId = uniqueId;
    }

    /**
     * Check if a load is already in progress for this tuple.
     * Returns true if this caller should proceed, false if another thread is already loading it.
     */
    public boolean tryAcquire(CachedAppAssetLoader.LoaderTuple tuple) {
        String key = tuple.computer.uuid + ":" + tuple.app.getAppId();
        return inFlightLoads.putIfAbsent(key, Boolean.TRUE) == null;
    }

    /** Release the in-flight lock after loading completes (success or failure). */
    public void release(CachedAppAssetLoader.LoaderTuple tuple) {
        String key = tuple.computer.uuid + ":" + tuple.app.getAppId();
        inFlightLoads.remove(key);
    }

    public InputStream getBitmapStream(CachedAppAssetLoader.LoaderTuple tuple) {
        InputStream in = null;
        try {
            NvHTTP http = httpCache.computeIfAbsent(tuple.computer.uuid, uuid -> {
                try {
                    return new NvHTTP(ServerHelper.getCurrentAddressFromComputer(tuple.computer),
                            tuple.computer.httpsPort, uniqueId, tuple.computer.serverCert,
                            PlatformBinding.getCryptoProvider(context));
                } catch (IOException e) {
                    return null;
                }
            });
            if (http != null) {
                in = http.getBoxArt(tuple.app);
            }
        } catch (IOException ignored) {}

        if (in != null) {
            LimeLog.info("Network asset load complete: " + tuple);
        }
        else {
            LimeLog.info("Network asset load failed: " + tuple);
        }

        return in;
    }

    /** Clear cached HTTP clients (call when computer list changes). */
    public void invalidate() {
        httpCache.clear();
        inFlightLoads.clear();
    }
}
