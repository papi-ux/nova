package com.papi.nova.nvstream.http

import com.papi.nova.nvstream.FrameSubmissionLaunchIdentity
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

class FrameSubmissionLaunchAttemptTest {
    private val requests = mutableListOf<Request>()
    private fun attempt(a: FrameSubmissionLaunchAttempt, path: String = "launch", fail: Boolean = false): String? {
        val request = Request.Builder().url("https://fixture.invalid/$path?appid=7&frameSubmissionLaunchNonce=stale").build()
        val chain = Proxy.newProxyInstance(Interceptor.Chain::class.java.classLoader,
            arrayOf(Interceptor.Chain::class.java)) { _, method, args ->
            when (method.name) {
                "request" -> request
                "proceed" -> {
                    val sent = args!![0] as Request
                    requests += sent
                    if (fail) throw IOException("fixture send failure")
                    Response.Builder().request(sent).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("ok".toResponseBody()).build()
                }
                else -> error("unexpected chain method: ${method.name}")
            }
        } as Interceptor.Chain
        a.intercept(chain) { it.host == "fixture.invalid" && it.encodedPath == "/$path" }.close()
        return requests.last().url.queryParameter(FrameSubmissionLaunchAttempt.QUERY)
    }

    @Test fun realInterceptorUsesFreshNonceForEachActualRetryAndRefusesTheEarlierEcho() {
        var count = 0
        val a = FrameSubmissionLaunchAttempt { it.fill((++count).toByte()) }
        val first = attempt(a)!!
        assertNotNull(a.complete(first, "same-app-token"))
        val retry = attempt(a)!!
        assertNotEquals(first, retry)
        assertNull(a.complete(first, "same-app-token"))
        assertEquals(FrameSubmissionLaunchIdentity(retry, "same-app-token"), a.complete(retry, "same-app-token"))
        assertEquals(listOf("7"), requests.last().url.queryParameterValues("appid"))
        assertEquals(listOf(retry), requests.last().url.queryParameterValues(FrameSubmissionLaunchAttempt.QUERY))
    }

    @Test fun failedRetryClearsEarlierSuccessfulAttemptIdentity() {
        var count = 0
        val a = FrameSubmissionLaunchAttempt { it.fill((++count).toByte()) }
        val first = attempt(a)!!
        assertNotNull(a.complete(first, "token"))
        assertThrows(IOException::class.java) { attempt(a, fail = true) }
        assertNull(a.complete(first, "token"))
        assertNull(a.complete(requests.last().url.queryParameter(FrameSubmissionLaunchAttempt.QUERY), "token"))
    }

    @Test fun sameAppTokenResumeHasADifferentSuccessfulAttemptNonce() {
        var count = 0
        val a = FrameSubmissionLaunchAttempt { it.fill((++count).toByte()) }
        val launch = a.complete(attempt(a), "same-app-token")!!
        val resume = a.complete(attempt(a, "resume"), "same-app-token")!!
        assertNotEquals(launch.nonce, resume.nonce)
        assertEquals(launch.sessionToken, resume.sessionToken)
    }

    @Test fun missingMalformedOrMismatchedEchoAndEntropyFailureOnlyRemoveTheExtension() {
        val a = FrameSubmissionLaunchAttempt { it.fill(1) }
        val sent = attempt(a)!!
        for (echo in listOf(null, "", "0".repeat(32), "A".repeat(32), "1".repeat(32)))
            assertNull(a.complete(echo, "token"))
        assertNull(a.complete(sent, null))
        assertNull(a.complete(sent, ""))
        assertNotNull(a.complete(sent, "token"))
        for (provider in listOf<(ByteArray) -> Unit>({ throw IllegalStateException("fixture entropy failure") }, { it.fill(0) })) {
            val unavailable = FrameSubmissionLaunchAttempt(provider)
            assertNull(attempt(unavailable))
            assertNull(unavailable.complete(sent, "token"))
            assertEquals("7", requests.last().url.queryParameter("appid"))
        }
    }
}
