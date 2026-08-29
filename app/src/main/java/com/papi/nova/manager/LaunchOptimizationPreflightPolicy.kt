package com.papi.nova.manager

import org.json.JSONObject

/** Immutable caller-owned fields used when a rejected preflight must be re-resolved. */
internal data class LaunchOptimizationRequestEnvelope(
    val width: Int,
    val height: Int,
    val fps: Float,
    val displayLocked: Boolean,
    val bitrateKbps: Int,
    val bitrateLocked: Boolean,
)

internal data class LaunchOptimizationPreflightSelection(
    val trustedPreflight: JSONObject?,
    val resolverRequest: LaunchOptimizationRequestEnvelope?,
)

/**
 * Keeps a rejected preflight from becoming input to the replacement /optimize request.
 * Only a fully accepted preflight is retained; every rejection returns the original
 * caller envelope byte-for-byte.
 */
internal object LaunchOptimizationPreflightPolicy {
    fun select(
        callerRequest: LaunchOptimizationRequestEnvelope,
        candidate: JSONObject?,
        accepted: Boolean,
    ): LaunchOptimizationPreflightSelection =
        if (accepted && candidate != null) {
            LaunchOptimizationPreflightSelection(candidate, null)
        } else {
            LaunchOptimizationPreflightSelection(null, callerRequest)
        }
}
