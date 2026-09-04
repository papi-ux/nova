package com.papi.nova.manager

import com.papi.nova.api.PolarisClientSettings
import org.json.JSONObject

/** Validates the encoder portion of Polaris' deterministic per-launch profile. */
object NovaEncoderLaunchContract {

    fun honors(optimization: JSONObject, requestedBackend: String): Boolean {
        val requested = PolarisClientSettings.normalizeEncoderBackend(requestedBackend)
            ?: return requestedBackend.isBlank()
        val field = optimization.optJSONObject("resolved_profile")
            ?.optJSONObject("fields")
            ?.optJSONObject("encoder_backend")
            ?: return false
        val resolution = optimization.optJSONObject("encoder_resolution") ?: return false
        val fieldValue = PolarisClientSettings.normalizeEncoderBackend(
            field.opt("value") as? String,
        )
        val resolutionRequested = PolarisClientSettings.normalizeEncoderBackend(
            resolution.opt("requested") as? String,
        )
        val resolutionResolved = PolarisClientSettings.normalizeEncoderBackend(
            resolution.opt("resolved") as? String,
        )
        return fieldValue == requested &&
            resolutionRequested == requested &&
            resolutionResolved == requested &&
            field.opt("locked") == true &&
            resolution.opt("locked") == true &&
            (resolution.opt("fallback_allowed") == (requested == "auto"))
    }
}
