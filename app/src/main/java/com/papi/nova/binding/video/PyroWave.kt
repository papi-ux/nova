package com.papi.nova.binding.video

import android.content.Context
import android.os.Build
import com.papi.nova.LimeLog

/**
 * PyroWave, an intra-only wavelet codec that decodes as plain Vulkan compute.
 *
 * Nothing here decodes yet. What it can do is answer whether this device could, which is the one
 * question every later piece depends on and the one a device can fail at silently.
 */
object PyroWave {

    /** What the device said when it was asked. */
    enum class Probe {
        /** Not asked yet. */
        UNKNOWN,

        /** No library on this ABI, or no Vulkan device the codec will accept. */
        UNUSABLE,

        /** Usable, and the compute path is the one to take. */
        COMPUTE,

        /**
         * Usable, and upstream recommends its fragment path for this GPU.
         *
         * Recorded, not obeyed. On a Mali G715 and an Adreno 650 that path decodes with about a
         * tenth of full scale of luma error, so a decoder here forces compute and this value exists
         * to say that the recommendation was seen and overruled.
         */
        FRAGMENT,
    }

    private const val PREFS_NAME = "nova_pyrowave"
    private const val KEY_FINGERPRINT = "probe_fingerprint"
    private const val KEY_RESULT = "probe_result"

    private val loaded: Boolean = try {
        System.loadLibrary("pyrowave-jni")
        true
    } catch (e: UnsatisfiedLinkError) {
        // An ABI without the library, or a device that cannot map it. Not fatal and not worth a
        // crash: every caller treats the codec as absent and the stream uses the normal ladder.
        LimeLog.warning("PyroWave: native library unavailable: " + e.message)
        false
    }

    @Volatile
    private var cached: Probe = Probe.UNKNOWN

    /** Whether the native library is present and loadable on this device. */
    @JvmStatic
    val isLibraryAvailable: Boolean
        get() = loaded

    /**
     * The version of the codec this build linked, as MAJOR.MINOR.PATCH, or empty when it is absent.
     *
     * Read from the library rather than written down beside it. Its C ABI is not stable before 1.0,
     * so a header and a binary that drifted apart is a real failure mode, and this is where it
     * shows up rather than in a stream that decodes to noise.
     */
    @JvmStatic
    fun apiVersion(): String = if (loaded) nativeApiVersion() else ""

    /**
     * Whether this device can decode, remembered across launches.
     *
     * Making a Vulkan device costs about a tenth of a second and the answer only changes when the
     * driver does, so it is cached against the build fingerprint, the same way the GL renderer
     * string already is.
     */
    @JvmStatic
    fun probe(context: Context): Probe {
        cached.takeIf { it != Probe.UNKNOWN }?.let { return it }
        if (!loaded) {
            cached = Probe.UNUSABLE
            return cached
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val fingerprint = Build.FINGERPRINT ?: ""
        if (prefs.getString(KEY_FINGERPRINT, null) == fingerprint) {
            val remembered = prefs.getInt(KEY_RESULT, Int.MIN_VALUE)
            fromNative(remembered)?.let {
                cached = it
                return it
            }
        }

        val answer = nativeProbeDecoder()
        val probe = fromNative(answer) ?: Probe.UNUSABLE
        prefs.edit()
            .putString(KEY_FINGERPRINT, fingerprint)
            .putInt(KEY_RESULT, answer)
            .apply()
        LimeLog.info("PyroWave: probe says $probe (version ${apiVersion()})")
        cached = probe
        return probe
    }

    /** The probe result if one has been taken, without taking one. */
    @JvmStatic
    val lastProbe: Probe
        get() = cached

    // Kept in step with the constants in pyrowave_jni.c.
    private fun fromNative(value: Int): Probe? = when (value) {
        -1 -> Probe.UNUSABLE
        0 -> Probe.COMPUTE
        1 -> Probe.FRAGMENT
        else -> null
    }

    @JvmStatic
    private external fun nativeApiVersion(): String

    @JvmStatic
    private external fun nativeProbeDecoder(): Int
}
