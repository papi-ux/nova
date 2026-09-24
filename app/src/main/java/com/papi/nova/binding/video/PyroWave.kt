package com.papi.nova.binding.video

import com.papi.nova.LimeLog

/**
 * PyroWave, an intra-only wavelet codec that decodes as plain Vulkan compute.
 *
 * Nothing here decodes yet. This is the seam: it proves the library is packaged, loads, and answers,
 * which is the one thing every later piece depends on and the one thing a device can fail at
 * silently.
 */
object PyroWave {

    private val loaded: Boolean = try {
        System.loadLibrary("pyrowave-jni")
        true
    } catch (e: UnsatisfiedLinkError) {
        // An ABI without the library, or a device that cannot map it. Not fatal and not worth a
        // crash: every caller treats the codec as unavailable and the stream uses the normal ladder.
        LimeLog.warning("PyroWave: native library unavailable: " + e.message)
        false
    }

    /** Whether the native library is present and loadable on this device. */
    @JvmStatic
    val isLibraryAvailable: Boolean
        get() = loaded

    /**
     * The version of the codec this build linked, as MAJOR.MINOR.PATCH, or empty when it is absent.
     *
     * Read from the library rather than written down beside it. Its C ABI is not stable before 1.0,
     * so a header and a binary that drifted apart is a real failure mode, and this is where it shows
     * up rather than in a stream that decodes to noise.
     */
    @JvmStatic
    fun apiVersion(): String = if (loaded) nativeApiVersion() else ""

    @JvmStatic
    private external fun nativeApiVersion(): String
}
