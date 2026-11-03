package com.papi.nova.api

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoctorAiHttpTest {
    private val sent = DoctorAiRequest("11111111-1111-4111-8111-111111111111", DoctorAiScope("session", 7, "result", "a".repeat(64), 9))
    private fun reply(body: ResponseBody, code: Int = 200) = Response.Builder().request(DoctorAiHttp.request("https://host.invalid/polaris/v1", sent))
        .protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body).build()
    private fun body() = """{"schema":1,"status":true,"fallback":false,"changed":false,"code":"explained","request_id":"${sent.requestId}","app_session_id":"session","session_generation":7,"source_result_id":"result","finding_fingerprint":"${"a".repeat(64)}","controller_revision":9,"snapshot_id":"${"b".repeat(32)}","snapshot_age_ms":1,"expires_in_ms":1000,"retry_after_ms":null,"authority":"explanation_only","may_define_settings":false,"source":{"kind":"ai-explanation","mode":"host-configured","informational":true},"explanation":{"likely_cause":"Explanation","evidence":[],"try_first":[],"advanced_detail":"","confidence":"low","destructive_action_allowed":false}}"""

    @Test fun preservesTlsMaterialButDisablesRetriesRedirectsAndBoundsTheWholeCall() {
        val base = OkHttpClient.Builder().build()
        val client = DoctorAiHttp.client(base)
        assertSame(base.sslSocketFactory, client.sslSocketFactory)
        assertFalse(client.retryOnConnectionFailure); assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
        assertEquals(35000, client.callTimeoutMillis); assertEquals(35000, client.readTimeoutMillis)
        val request = DoctorAiHttp.request("https://host.invalid/polaris/v1", sent)
        assertEquals("/polaris/v1/doctor/explanation", request.url.encodedPath)
        assertEquals("POST", request.method)
        assertEquals("close", request.header("Connection"))
    }
    @Test fun successUsesOneCallAndInvalidStandingNeverDispatches() = runBlocking {
        val call = mock(Call::class.java)
        `when`(call.execute()).thenReturn(reply(body().toResponseBody()))
        assertNotNull(DoctorAiHttp.execute(call, sent) { true }.text)
        verify(call, times(1)).execute()
        val stale = mock(Call::class.java)
        assertNull(DoctorAiHttp.execute(stale, sent) { false }.text)
        verify(stale, never()).execute(); verify(stale).cancel()
    }
    @Test fun redirectAndTransportFailureDoNotRetryOrConsumeAnotherRoute() = runBlocking {
        val redirect = mock(Call::class.java)
        `when`(redirect.execute()).thenReturn(reply(body().toResponseBody(), 302))
        assertNull(DoctorAiHttp.execute(redirect, sent) { true }.text)
        verify(redirect, times(1)).execute()
        val broken = mock(Call::class.java)
        `when`(broken.execute()).thenThrow(IOException("fixture"))
        assertNull(DoctorAiHttp.execute(broken, sent) { true }.text)
        verify(broken, times(1)).execute()
        Unit
    }
    @Test fun knownAndChunkedOversizedBodiesAndInvalidUtf8FailClosed() = runBlocking {
        for (body in listOf("x".repeat(8193).toResponseBody(), unknownLength(ByteArray(8193) { 32 }), byteArrayOf(0xc3.toByte(), 0x28).toResponseBody())) {
            val call = mock(Call::class.java); `when`(call.execute()).thenReturn(reply(body))
            assertNull(DoctorAiHttp.execute(call, sent) { true }.text)
        }
    }
    @Test fun changedStandingAfterBodyConsumptionDropsAnOtherwiseValidExplanation() = runBlocking {
        val call = mock(Call::class.java); `when`(call.execute()).thenReturn(reply(body().toResponseBody()))
        var checks = 0
        assertNull(DoctorAiHttp.execute(call, sent) { ++checks < 3 }.text)
        assertEquals(3, checks)
    }
    @Test fun cancellingWhileBodyReadIsBlockedCancelsTheActualCallAndDoesNotReturnText() = runBlocking {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val call = mock(Call::class.java)
        doAnswer { cancelled.countDown(); null }.`when`(call).cancel()
        val source = object : Source {
            override fun timeout() = Timeout.NONE
            override fun close() {}
            override fun read(sink: Buffer, byteCount: Long): Long {
                entered.countDown()
                check(cancelled.await(2, TimeUnit.SECONDS))
                throw IOException("Cancelled fixture body")
            }
        }.buffer()
        val blocked = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = source
        }
        `when`(call.execute()).thenReturn(reply(blocked))
        var returned = false
        val job = launch(Dispatchers.Default) { DoctorAiHttp.execute(call, sent) { true }; returned = true }
        assertTrue(entered.await(2, TimeUnit.SECONDS)); job.cancelAndJoin()
        assertTrue(cancelled.await(2, TimeUnit.SECONDS)); assertFalse(returned); assertTrue(job.isCancelled)
    }
    private fun unknownLength(bytes: ByteArray) = object : ResponseBody() {
        private val buffer = Buffer().write(bytes)
        override fun contentType(): MediaType? = null
        override fun contentLength() = -1L
        override fun source(): BufferedSource = buffer
    }
}
