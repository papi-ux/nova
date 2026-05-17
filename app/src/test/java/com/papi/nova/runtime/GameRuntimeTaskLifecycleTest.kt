package com.papi.nova.runtime

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class GameRuntimeTaskLifecycleTest {
    @Test
    fun runtimeTasksCancelWhenLifecycleIsDestroyed() {
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED)
        val tasks = NovaRuntimeTasks(owner)
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)

        tasks.launchIo("blocked") {
            started.countDown()
            try {
                awaitCancellation()
            } finally {
                cancelled.countDown()
            }
        }

        assertTrue(started.await(1, TimeUnit.SECONDS))

        owner.currentState = Lifecycle.State.DESTROYED

        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        assertNoActiveJobs(tasks, "blocked")
    }

    @Test
    fun explicitCancelRemovesNamedRuntimeTasks() {
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED)
        val tasks = NovaRuntimeTasks(owner)
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)

        tasks.launchIo("session") {
            started.countDown()
            try {
                awaitCancellation()
            } finally {
                cancelled.countDown()
            }
        }

        assertTrue(started.await(1, TimeUnit.SECONDS))

        tasks.cancel("session")

        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        assertNoActiveJobs(tasks, "session")
    }

    @Test
    fun launchIoReplacingCancelsPreviousNamedRuntimeTask() {
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED)
        val tasks = NovaRuntimeTasks(owner)
        val firstStarted = CountDownLatch(1)
        val firstCancelled = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)

        tasks.launchIoReplacing("bitrate") {
            firstStarted.countDown()
            try {
                awaitCancellation()
            } finally {
                firstCancelled.countDown()
            }
        }

        assertTrue(firstStarted.await(1, TimeUnit.SECONDS))

        tasks.launchIoReplacing("bitrate") {
            secondStarted.countDown()
            awaitCancellation()
        }

        assertTrue(firstCancelled.await(1, TimeUnit.SECONDS))
        assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
        assertEquals(1, tasks.activeJobCount("bitrate"))

        tasks.cancel("bitrate")
        assertNoActiveJobs(tasks, "bitrate")
    }

    private fun assertNoActiveJobs(tasks: NovaRuntimeTasks, name: String) {
        repeat(20) {
            if (tasks.activeJobCount(name) == 0) {
                assertEquals(0, tasks.activeJobCount(name))
                return
            }
            Thread.sleep(25)
        }
        assertEquals(0, tasks.activeJobCount(name))
    }
}
