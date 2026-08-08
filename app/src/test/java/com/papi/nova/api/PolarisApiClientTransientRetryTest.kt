package com.papi.nova.api

import java.io.IOException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PolarisApiClientTransientRetryTest {

    @Test
    fun succeedsFirstAttemptWithoutReset() {
        var resets = 0
        var attempts = 0
        val result = PolarisApiClient.runWithTransientTlsRetry(onTransient = { resets++ }, retryDelayMs = 0L) {
            attempts++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, attempts)
        assertEquals(0, resets)
    }

    @Test
    fun retriesOnceAfterSslExceptionAndResets() {
        var resets = 0
        var attempts = 0
        val result = PolarisApiClient.runWithTransientTlsRetry(onTransient = { resets++ }, retryDelayMs = 0L) {
            attempts++
            if (attempts == 1) {
                throw SSLHandshakeException("resumption refused")
            }
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(2, attempts)
        assertEquals(1, resets)
    }

    @Test
    fun secondSslFailurePropagates() {
        var resets = 0
        var attempts = 0
        try {
            PolarisApiClient.runWithTransientTlsRetry<Unit>(onTransient = { resets++ }, retryDelayMs = 0L) {
                attempts++
                throw SSLException("still broken")
            }
            fail("expected SSLException to propagate")
        } catch (expected: SSLException) {
            // second failure must surface to the caller
        }
        assertEquals(2, attempts)
        assertEquals(1, resets)
    }

    @Test
    fun nonTlsFailuresDoNotRetry() {
        var resets = 0
        var attempts = 0
        try {
            PolarisApiClient.runWithTransientTlsRetry<Unit>(onTransient = { resets++ }, retryDelayMs = 0L) {
                attempts++
                throw IOException("plain network error")
            }
            fail("expected IOException to propagate")
        } catch (expected: IOException) {
            // non-TLS failures take the normal error path, no retry
        }
        assertEquals(1, attempts)
        assertEquals(0, resets)
    }
}
