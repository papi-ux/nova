package com.papi.nova.api

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import kotlin.reflect.KClass

class PolarisApiClientArtworkCancellationTest {
    @Test
    fun cancellingAwaitArtworkResponseCancelsCallAndClosesLateResponse() = runBlocking {
        val call = RecordingCall()
        val job = launch {
            PolarisApiClient.awaitArtworkResponse(call).close()
        }
        yield()
        assertTrue(call.isExecuted())

        job.cancelAndJoin()
        assertTrue(call.isCanceled())

        val lateResponse = mock(Response::class.java)
        call.callback.onResponse(call, lateResponse)
        verify(lateResponse).close()
    }

    private class RecordingCall : Call {
        lateinit var callback: Callback
        private var cancelled = false
        private val request = Request.Builder()
            .url("https://polaris.invalid/artwork/icon")
            .build()

        override fun request(): Request = request

        override fun execute(): Response = error("Synchronous execution is forbidden")

        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }

        override fun cancel() {
            cancelled = true
        }

        override fun isExecuted(): Boolean = ::callback.isInitialized

        override fun isCanceled(): Boolean = cancelled

        override fun timeout(): Timeout = Timeout.NONE

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun clone(): Call = RecordingCall()
    }
}
