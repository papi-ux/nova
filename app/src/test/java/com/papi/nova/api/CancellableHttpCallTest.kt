package com.papi.nova.api

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class CancellableHttpCallTest {
    @Test
    fun cancellingCoroutineCancelsTheBlockingOkHttpCall() = runBlocking {
        val call = BlockingCall()
        val job = launch(Dispatchers.Default) {
            executeCancellableHttpCall(call) { true }
        }

        assertTrue("HTTP call did not start", call.executeStarted.await(2, TimeUnit.SECONDS))
        job.cancel()
        assertTrue("Coroutine cancellation did not cancel OkHttp", call.cancelObserved.await(2, TimeUnit.SECONDS))
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(call.isCanceled())
    }

    private class BlockingCall : Call {
        val executeStarted = CountDownLatch(1)
        val cancelObserved = CountDownLatch(1)
        @Volatile private var cancelled = false

        override fun request(): Request = Request.Builder().url("https://example.invalid/").build()

        override fun execute(): Response {
            executeStarted.countDown()
            check(cancelObserved.await(2, TimeUnit.SECONDS)) { "Call was not cancelled" }
            throw IOException("Canceled")
        }

        override fun enqueue(responseCallback: Callback) = error("Not used")

        override fun cancel() {
            cancelled = true
            cancelObserved.countDown()
        }

        override fun isExecuted(): Boolean = executeStarted.count == 0L

        override fun isCanceled(): Boolean = cancelled

        override fun timeout(): Timeout = Timeout.NONE

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()

        override fun clone(): Call = BlockingCall()
    }
}
