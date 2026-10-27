package com.papi.nova.manager

import com.papi.nova.shared.polaris.contract.PolarisV1
import com.papi.nova.api.PolarisClientSettings
import org.json.JSONObject

/** Validates the encoder portion of Polaris' deterministic per-launch profile. */
object NovaEncoderLaunchContract {

    fun honors(optimization: JSONObject, requestedBackend: String): Boolean {
        val requested = PolarisClientSettings.normalizeEncoderBackend(requestedBackend)
            ?: return requestedBackend.isBlank()
        val field = optimization.optJSONObject(PolarisV1.Optimization.RESOLVED_PROFILE)
            ?.optJSONObject("fields")
            ?.optJSONObject("encoder_backend")
            ?: return false
        val resolution = optimization.optJSONObject(PolarisV1.Optimization.ENCODER_RESOLUTION) ?: return false
        val fieldValue = PolarisClientSettings.normalizeEncoderBackend(
            field.opt(PolarisV1.OptimizationResolvedField.VALUE) as? String,
        )
        val resolutionRequested = PolarisClientSettings.normalizeEncoderBackend(
            resolution.opt(PolarisV1.OptimizationEncoderResolution.REQUESTED) as? String,
        )
        val resolutionResolved = PolarisClientSettings.normalizeEncoderBackend(
            resolution.opt(PolarisV1.OptimizationEncoderResolution.RESOLVED) as? String,
        )
        return fieldValue == requested &&
            resolutionRequested == requested &&
            resolutionResolved == requested &&
            field.opt(PolarisV1.OptimizationResolvedField.LOCKED) == true &&
            resolution.opt(PolarisV1.OptimizationEncoderResolution.LOCKED) == true &&
            (resolution.opt(PolarisV1.OptimizationEncoderResolution.FALLBACK_ALLOWED) == (requested == "auto"))
    }
}
