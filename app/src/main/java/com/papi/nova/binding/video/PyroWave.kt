package com.papi.nova.binding.video

import android.content.Context
import android.os.Build
import android.view.Surface
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

        val probe = measure(context)
        prefs.edit()
            .putString(KEY_FINGERPRINT, fingerprint)
            .putInt(KEY_RESULT, toNative(probe))
            .apply()
        cached = probe
        return probe
    }

    /**
     * Decide by decoding, because a device that accepts the shaders is not the same as a device
     * that gets them right.
     *
     * Both devices this was written against create a Vulkan device happily and then decode with
     * about a tenth of full scale of luma error on the path upstream recommends for them. A probe
     * that only asked "is there a device" would have said yes to both and handed the player a
     * picture with visible banding in it.
     *
     * So: run a known frame through, and believe the pixels. Compute first, because that is the
     * path that works; the recommended path is tried only if compute fails, and never silently.
     */
    private fun measure(context: Context): Probe {
        val recommendation = fromNative(nativeProbeDecoder())
        if (recommendation == null || recommendation == Probe.UNUSABLE) {
            LimeLog.info("PyroWave: no usable Vulkan device")
            return Probe.UNUSABLE
        }

        val compute = decodeSelfTest(context, fragmentPath = false)
        if (compute != null && compute <= MAX_SELF_TEST_ERROR) {
            LimeLog.info(
                "PyroWave: compute path decodes within $compute of exact " +
                    "(upstream recommends ${recommendation.name.lowercase()}, version ${apiVersion()})"
            )
            return Probe.COMPUTE
        }

        val fragment = decodeSelfTest(context, fragmentPath = true)
        if (fragment != null && fragment <= MAX_SELF_TEST_ERROR) {
            LimeLog.info("PyroWave: only the fragment path decodes correctly here (error $fragment)")
            return Probe.FRAGMENT
        }

        LimeLog.warning(
            "PyroWave: this device decodes incorrectly on both paths " +
                "(compute $compute, fragment $fragment); the codec will not be offered"
        )
        return Probe.UNUSABLE
    }

    /**
     * How wrong a decode may be and still count as working.
     *
     * One step of an eight bit sample. The reference frame is encoded with a budget far above what
     * it needs, so an exact decoder returns zero and anything above one is a driver getting the
     * arithmetic wrong rather than a codec making a choice.
     */
    private const val MAX_SELF_TEST_ERROR = 1

    private fun toNative(probe: Probe): Int = when (probe) {
        Probe.UNUSABLE -> -1
        Probe.COMPUTE -> 0
        Probe.FRAGMENT -> 1
        Probe.UNKNOWN -> Int.MIN_VALUE
    }

    /** The probe result if one has been taken, without taking one. */
    @JvmStatic
    val lastProbe: Probe
        get() = cached

    /**
     * Decode a known frame on this device and report the worst luma error, or null when it could
     * not be run at all.
     *
     * Zero means this device decodes exactly what a host will send. Anything above it is a driver
     * that compiles the shaders and then gets the arithmetic wrong, which is a real state: both
     * devices this was written against do exactly that on the path upstream recommends for them.
     *
     * Costs a Vulkan device and a decode, so it is for diagnostics and for deciding, not for a
     * stream start.
     */
    @JvmStatic
    fun decodeSelfTest(context: Context, fragmentPath: Boolean = false): Int? {
        if (!loaded) return null
        return try {
            val bitstream = context.assets.open(SELF_TEST_ASSET).use { it.readBytes() }
            nativeDecodeSelfTest(bitstream, fragmentPath)
        } catch (e: Exception) {
            LimeLog.warning("PyroWave: self test could not run: " + e.message)
            null
        }
    }

    /**
     * Decode the bundled frame and draw it on a surface, returning 0 when one reached the screen.
     *
     * Everything the client does except the network, which is the point: if this draws, the only
     * thing between here and a stream is the protocol.
     */
    @JvmStatic
    fun presentSelfTest(context: Context, surface: Surface): Int? {
        if (!loaded) return null
        return try {
            val bitstream = context.assets.open(SELF_TEST_ASSET).use { it.readBytes() }
            nativePresentSelfTest(surface, bitstream)
        } catch (e: Exception) {
            LimeLog.warning("PyroWave: present self test could not run: " + e.message)
            null
        }
    }

    private const val SELF_TEST_ASSET = "pyrowave/selftest-34x30.pw"

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

    @JvmStatic
    private external fun nativeDecodeSelfTest(bitstream: ByteArray, fragmentPath: Boolean): Int

    @JvmStatic
    private external fun nativePresentSelfTest(surface: Surface, bitstream: ByteArray): Int
}
