package com.papi.nova.nvstream.http

import com.papi.nova.nvstream.FrameSubmissionLaunchIdentity
import okhttp3.Interceptor
import okhttp3.Response
import java.security.SecureRandom

/** One launch/resume call. The network interceptor runs again on actual HTTP retries. */
internal class FrameSubmissionLaunchAttempt(
    private val entropy: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) },
) {
    private var issued: String? = null
    private var successfulRequest: String? = null

    fun intercept(chain: Interceptor.Chain, expectedTarget: (okhttp3.HttpUrl) -> Boolean): Response {
        issued = null
        successfulRequest = null
        val original = chain.request()
        if (!expectedTarget(original.url)) return chain.proceed(original)
        val nonce = try {
            val bytes = ByteArray(16)
            entropy(bytes)
            FrameSubmissionLaunchIdentity.parseNonce(bytes.joinToString("") { "%02x".format(it.toInt() and 255) })
        } catch (_: Exception) { null }
        issued = nonce
        val url = original.url.newBuilder().removeAllQueryParameters(QUERY)
        if (nonce != null) url.addQueryParameter(QUERY, nonce)
        val request = original.newBuilder().url(url.build()).build()
        val response = chain.proceed(request)
        if (response.isSuccessful && response.request.url.queryParameter(QUERY) == nonce) successfulRequest = nonce
        return response
    }

    fun complete(echo: String?, sessionToken: String?): FrameSubmissionLaunchIdentity? {
        val nonce = issued ?: return null
        return if (successfulRequest == nonce && FrameSubmissionLaunchIdentity.parseNonce(echo) == nonce &&
            !sessionToken.isNullOrBlank()) FrameSubmissionLaunchIdentity(nonce, sessionToken) else null
    }

    companion object { const val QUERY = "frameSubmissionLaunchNonce" }
}
