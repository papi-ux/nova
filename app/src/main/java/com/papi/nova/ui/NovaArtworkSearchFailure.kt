package com.papi.nova.ui

import com.papi.nova.R
import org.json.JSONObject

/**
 * Maps the coded failure Polaris returns for an artwork search onto the message
 * Nova shows. Polaris answers a failed search with `{"status": false, "code":
 * "...", "error": "..."}`; the code is the contract, the English is the host's.
 */
object NovaArtworkSearchFailure {
    const val CODE_KEY_MISSING = "steamgriddb_key_missing"
    const val CODE_UNAUTHORIZED = "steamgriddb_unauthorized"
    const val CODE_RATE_LIMITED = "steamgriddb_rate_limited"

    /** The failure code from a non-2xx search body, or null when the host sent none. */
    fun codeFrom(body: JSONObject?): String? = normalizeCode(body?.optString("code"))

    /** Trim and lowercase a code; blank means the host sent none. */
    fun normalizeCode(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun messageRes(code: String?): Int = when (normalizeCode(code)) {
        CODE_KEY_MISSING -> R.string.nova_artwork_search_no_key
        CODE_UNAUTHORIZED -> R.string.nova_artwork_search_key_rejected
        CODE_RATE_LIMITED -> R.string.nova_artwork_search_rate_limited
        else -> R.string.nova_artwork_search_failed
    }
}
