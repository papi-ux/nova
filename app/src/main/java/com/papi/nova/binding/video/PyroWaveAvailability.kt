package com.papi.nova.binding.video

import android.content.Context
import com.papi.nova.R
import com.papi.nova.preferences.PreferenceConfiguration.FormatOption

/** Availability of the compute path the Android renderer actually uses. */
object PyroWaveAvailability {
    enum class Status(val reasonRes: Int) {
        CHECKING(R.string.nova_pyrowave_checking),
        AVAILABLE(0),
        LIBRARY_UNAVAILABLE(R.string.nova_pyrowave_library_unavailable),
        DECODE_UNAVAILABLE(R.string.nova_pyrowave_decode_unavailable),
        COMPUTE_UNAVAILABLE(R.string.nova_pyrowave_compute_unavailable),
        GPU_FEATURES_UNAVAILABLE(R.string.nova_pyrowave_gpu_features_unavailable),
    }

    fun evaluate(libraryAvailable: Boolean, probe: PyroWave.Probe, missingFeatures: Int = 0): Status = when {
        !libraryAvailable -> Status.LIBRARY_UNAVAILABLE
        probe == PyroWave.Probe.COMPUTE -> Status.AVAILABLE
        // A correct fragment decode cannot validate the forced-compute stream renderer.
        probe == PyroWave.Probe.FRAGMENT -> Status.COMPUTE_UNAVAILABLE
        probe == PyroWave.Probe.UNKNOWN -> Status.CHECKING
        missingFeatures != 0 -> Status.GPU_FEATURES_UNAVAILABLE
        else -> Status.DECODE_UNAVAILABLE
    }

    /** Usually warmed by PcView; a cold shortcut must measure before it can launch. */
    fun inspect(context: Context): Status = evaluate(
        PyroWave.isLibraryAvailable, PyroWave.probe(context), PyroWave.missingRequiredFeatures,
    )

    fun canSelect(value: String, status: Status): Boolean =
        value != "forcepyrowave" || status == Status.AVAILABLE

    fun canLaunch(format: FormatOption?, status: Status): Boolean =
        format != FormatOption.FORCE_PYROWAVE || status == Status.AVAILABLE

    fun reason(context: Context, status: Status, missingFeatures: Int = PyroWave.missingRequiredFeatures): String {
        if (status == Status.AVAILABLE) return ""
        if (status != Status.GPU_FEATURES_UNAVAILABLE) return context.getString(status.reasonRes)
        val names = listOf(
            PyroWaveGpuFeatures.SHADER_INT16 to R.string.nova_pyrowave_feature_shader_int16,
            PyroWaveGpuFeatures.STORAGE_16BIT to R.string.nova_pyrowave_feature_storage_16bit,
            PyroWaveGpuFeatures.STORAGE_8BIT to R.string.nova_pyrowave_feature_storage_8bit,
            PyroWaveGpuFeatures.TIMELINE_SEMAPHORE to R.string.nova_pyrowave_feature_timeline,
            PyroWaveGpuFeatures.SUBGROUP_SIZE_CONTROL to R.string.nova_pyrowave_feature_subgroup_size,
            PyroWaveGpuFeatures.COMPUTE_FULL_SUBGROUPS to R.string.nova_pyrowave_feature_full_subgroups,
        ).filter { (bit, _) -> missingFeatures and bit != 0 }
            .map { (_, res) -> context.getString(res) }
        if (names.isEmpty()) return context.getString(R.string.nova_pyrowave_decode_unavailable)
        return context.getString(status.reasonRes, names.joinToString(", "))
    }
}
