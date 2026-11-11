package com.papi.nova.api

/** How a paired stop of a captured session came out, as far as Nova can tell (#487). */
internal enum class PolarisSessionEnd {
    /** The host said it stopped the session, or it is gone when read again after a stop with no answer. */
    ENDED,

    /** The host answered and did not stop it: a refusal, another session, or an unreadable reply. */
    REFUSED,

    /** No answer to the stop, and the session is still there, not closing, when read again. */
    STILL_RUNNING,

    /** No answer to the stop, and the session is still closing when read again. */
    STILL_CLOSING,

    /** No answer to the stop, and none to the status reads after it either. */
    NO_ANSWER,
}

/** What a session status read says of a captured session after a stop that got no answer. */
internal enum class PolarisCapturedSession { GONE, CLOSING, RUNNING }

/**
 * Whether [status] still shows the session [sessionToken] names. A status with no token is taken
 * as possibly that session while it is alive: only a different token, or no live session, is gone.
 */
internal fun capturedSessionAfterStop(status: PolarisSessionStatus, sessionToken: String): PolarisCapturedSession {
    val maybeIt = status.sessionToken == sessionToken || status.sessionToken.isBlank()
    return when {
        maybeIt && status.isShuttingDown -> PolarisCapturedSession.CLOSING
        maybeIt && (status.isSessionAlive || status.isPausedForResume) -> PolarisCapturedSession.RUNNING
        else -> PolarisCapturedSession.GONE
    }
}
