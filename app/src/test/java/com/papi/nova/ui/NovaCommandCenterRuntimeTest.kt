package com.papi.nova.ui

import android.os.Looper
import com.papi.nova.Game
import com.papi.nova.binding.input.capture.InputCaptureProvider
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The runtime a stream gives the Command Center ([NovaCommandCenterRuntime.of]), on a stream of its
 * own. Live Tuning's save runs its request off the main thread, lands its result back on it, and
 * lets the result go after its time; each of the three had only a test's own runtime behind it,
 * so a runtime that dropped any of them passed every test while the app's row never cleared, or
 * the switch never reached the host, or its result never showed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCommandCenterRuntimeTest {
    private fun stream(): Game {
        val controller = Robolectric.buildActivity(Game::class.java)
        val game = controller.get()
        // A stream that is shown has a capture provider; this one has not been started.
        Game::class.java.getDeclaredField("inputCaptureProvider").apply { isAccessible = true }
            .set(game, Mockito.mock(InputCaptureProvider::class.java))
        controller.visible()
        return game
    }

    @Test
    fun workRunsOffTheMainThread() {
        val runtime = NovaCommandCenterRuntime.of(stream())
        val ran = CountDownLatch(1)
        var thread: Thread? = null
        runtime.launchIo("NovaCommandCenterRuntimeTest") {
            thread = Thread.currentThread()
            ran.countDown()
        }
        assertTrue("the work ran", ran.await(5, TimeUnit.SECONDS))
        assertNotSame("off the main thread", Looper.getMainLooper().thread, thread)
    }

    @Test
    fun aResultLandsOnTheMainThread() {
        val runtime = NovaCommandCenterRuntime.of(stream())
        var thread: Thread? = null
        runBlocking { runtime.onMain { thread = Thread.currentThread() } }
        assertSame("on the main thread", Looper.getMainLooper().thread, thread)
    }

    @Test
    fun aResultGoesAfterItsTime() {
        val runtime = NovaCommandCenterRuntime.of(stream())
        var gone = false
        runtime.postDelayed(NovaLiveTuningSave.SHOWN_MS) { gone = true }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NovaLiveTuningSave.SHOWN_MS - 1))
        assertFalse("not before its time", gone)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue("after it", gone)
    }
}
