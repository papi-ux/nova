package com.papi.nova.binding.video

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class PyroWaveRendererLifetimeTest {
    @Test fun closeWaitsForAnAdmittedFrameAndRejectsLaterCalls() {
        val destroyed = AtomicInteger()
        val lifetime = PyroWaveRendererLifetime { handle ->
            assertEquals(17L, handle)
            destroyed.incrementAndGet()
        }
        assertEquals(17L, lifetime.create { 17L })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val frame = pool.submit<Long?> {
                lifetime.useOrNull { handle ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    assertEquals("Native resource must survive the whole frame", 0, destroyed.get())
                    handle
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val close = pool.submit {
                closing.countDown()
                lifetime.close()
            }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            try {
                close.get(100, TimeUnit.MILLISECONDS)
                fail("Close returned while the frame still owned the renderer")
            } catch (_: TimeoutException) {
                // Closing must wait for the frame, without destroying its resource.
            }
            assertEquals(0, destroyed.get())
            release.countDown()
            assertEquals(17L, frame.get(5, TimeUnit.SECONDS))
            close.get(5, TimeUnit.SECONDS)
            assertEquals(1, destroyed.get())
            assertNull(lifetime.useOrNull { fail("Late frame reached native code"); true })
            assertEquals(0L, lifetime.create { fail("Closed renderer was recreated"); 99L })
            lifetime.close()
            assertEquals(1, destroyed.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun closeAlsoWaitsForCreationAndDestroysItsResultOnce() {
        val destroyed = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val lifetime = PyroWaveRendererLifetime { destroyed.incrementAndGet() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val create = pool.submit<Long> {
                lifetime.create {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    23L
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val close = pool.submit { closing.countDown(); lifetime.close() }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            try {
                close.get(100, TimeUnit.MILLISECONDS)
                fail("Close returned while creation could still publish a handle")
            } catch (_: TimeoutException) {}
            release.countDown()
            assertEquals(23L, create.get(5, TimeUnit.SECONDS))
            close.get(5, TimeUnit.SECONDS)
            assertEquals(1, destroyed.get())
            assertNull(lifetime.useOrNull { true })
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun failedCreationAndClosingBeforeSetupDoNotDestroyZero() {
        val lifetime = PyroWaveRendererLifetime { fail("No renderer was created") }
        assertEquals(0L, lifetime.create { 0L })
        assertNull(lifetime.useOrNull { true })
        lifetime.close()
        lifetime.close()
        assertEquals(0L, lifetime.create { fail("Factory called after close"); 1L })
    }
}
