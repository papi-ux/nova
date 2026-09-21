package com.papi.nova.nvstream.http

import java.io.IOException

/**
 * A non-200 status from the host's launch, resume, quit or app-list response.
 *
 * Polaris adds two root attributes Moonlight never had: error_code, a stable
 * name for what refused the launch, and error_action, the one change that
 * fixes it. Both are optional so Sunshine and older Polaris hosts still parse.
 */
class HostHttpResponseException(
    private val errorCode: Int,
    private val errorMsg: String,
    private val hostCode: String? = null,
    private val hostAction: String? = null,
    private val watchProfile: com.papi.nova.nvstream.NovaWatchProfile? = null,
) : IOException() {
    fun getErrorCode(): Int = errorCode

    fun getErrorMessage(): String = errorMsg

    /** The host's stable refusal name, such as encoder_probe_failed; null when the host sent none. */
    fun getHostCode(): String? = hostCode

    /** The host's one-line fix; null when the host sent none. */
    fun getHostAction(): String? = hostAction

    /** The mode a refused watcher should ask for instead; null when the host named none as fields. */
    fun getWatchProfile(): com.papi.nova.nvstream.NovaWatchProfile? = watchProfile

    override val message: String
        get() = "Host PC returned error: $errorMsg (Error code: $errorCode)"

    companion object {
        private const val serialVersionUID = 1543508830807804222L
    }
}
