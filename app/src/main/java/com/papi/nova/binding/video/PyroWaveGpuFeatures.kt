package com.papi.nova.binding.video

/** Per-call native diagnosis; bits match pyrowave_probe_features.h. */
internal object PyroWaveGpuFeatures {
    const val SHADER_INT16 = 1 shl 0
    const val STORAGE_16BIT = 1 shl 1
    const val STORAGE_8BIT = 1 shl 2
    const val TIMELINE_SEMAPHORE = 1 shl 3
    const val SUBGROUP_SIZE_CONTROL = 1 shl 4
    const val COMPUTE_FULL_SUBGROUPS = 1 shl 5
    const val ALL = (1 shl 6) - 1
    private const val FAILURE_BASE = 256

    fun fromNativeFailure(result: Int): Int =
        if (result in -(FAILURE_BASE + ALL)..-(FAILURE_BASE + 1)) -result - FAILURE_BASE else 0
}
