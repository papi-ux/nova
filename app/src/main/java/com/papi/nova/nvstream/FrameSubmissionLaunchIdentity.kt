package com.papi.nova.nvstream

/** Successful HTTP attempt identity, independent of the native DR connection nonce. */
data class FrameSubmissionLaunchIdentity(val nonce: String, val sessionToken: String) {
    init { require(parseNonce(nonce) != null && sessionToken.isNotBlank()) }
    companion object {
        fun parseNonce(value: Any?): String? = (value as? String)?.takeIf {
            it.matches(Regex("[0-9a-f]{32}")) && it.any { char -> char != '0' }
        }
    }
}

/** Captured by this renderer during its native DR setup, rather than from Game's latest connection. */
data class FrameSubmissionProducer(val connection: Any, val launch: FrameSubmissionLaunchIdentity?, val nativeEpoch: Long)
