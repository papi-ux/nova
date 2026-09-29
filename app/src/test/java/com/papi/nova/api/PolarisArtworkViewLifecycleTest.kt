package com.papi.nova.api

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import com.papi.nova.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PolarisArtworkViewLifecycleTest {
    private fun view() = ImageView(Robolectric.buildActivity(Activity::class.java).setup().get())

    @Test
    fun disposingAViewCancelsItsActualArtworkCallAndClosesALateResponse() = runBlocking {
        val target = view()
        val call = RecordingCall()
        val marker = Any()
        target.setTag(R.id.nova_artwork_request_key, marker)
        target.setImageDrawable(ColorDrawable(0xff112233.toInt()))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            PolarisApiClient.awaitArtworkResponse(call).close()
        }
        target.setTag(R.id.nova_artwork_job, job)
        assertTrue(call.isExecuted())

        PolarisApiClient.releaseArtworkView(target)
        job.join()
        assertTrue(call.isCanceled())
        assertNull(target.getTag(R.id.nova_artwork_request_key))
        assertNull(target.getTag(R.id.nova_artwork_job))
        assertNull(target.drawable)

        val late = mock(Response::class.java)
        call.callback.onResponse(call, late)
        verify(late).close()
        assertNull(target.drawable)
    }

    @Test
    fun retiringOneViewDoesNotCancelAnotherImageInTheSameScope() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val first = view()
        val second = view()
        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) { gate.await() }
        val secondJob = launch(start = CoroutineStart.UNDISPATCHED) { gate.await() }
        val secondKey = Any()
        first.setTag(R.id.nova_artwork_job, firstJob)
        second.setTag(R.id.nova_artwork_job, secondJob)
        second.setTag(R.id.nova_artwork_request_key, secondKey)
        PolarisApiClient.releaseArtworkView(first)
        firstJob.join()
        assertTrue(firstJob.isCancelled)
        assertFalse(secondJob.isCancelled)
        assertSame(secondKey, second.getTag(R.id.nova_artwork_request_key))
        gate.complete(Unit)
        secondJob.join()
    }

    @Test
    fun markersAndDrawableRetireBeforeCancellationHandlersRun() {
        val target = view()
        val job = Job()
        target.setTag(R.id.nova_artwork_job, job)
        target.setTag(R.id.nova_artwork_request_key, "old-game:poster")
        target.setImageDrawable(ColorDrawable(0xff112233.toInt()))
        job.invokeOnCompletion {
            assertNull(target.getTag(R.id.nova_artwork_request_key))
            assertNull(target.getTag(R.id.nova_artwork_job))
            assertNull(target.drawable)
        }
        PolarisApiClient.releaseArtworkView(target)
        PolarisApiClient.releaseArtworkView(target)
        assertTrue(job.isCancelled)
    }

    private class RecordingCall : Call {
        lateinit var callback: Callback
        private var cancelled = false
        private val request = Request.Builder().url("https://polaris.invalid/artwork/icon").build()
        override fun request(): Request = request
        override fun execute(): Response = error("Synchronous execution is forbidden")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
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
