package com.papi.nova.runtime

import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the single best-effort Doctor telemetry upload for the current stream.
 *
 * Tokens prevent a cancelled upload from releasing a newer stream's lease.
 * Invalidating the gate is intentionally synchronous so disconnect can retire
 * work that was cancelled before its coroutine body (and finally block) ran.
 */
internal class DoctorTelemetryUploadGate {
    private val nextToken = AtomicLong(0L)
    private val activeToken = AtomicLong(NO_TOKEN)

    fun tryAcquire(): Long? {
        val token = nextToken.incrementAndGet()
        return if (activeToken.compareAndSet(NO_TOKEN, token)) token else null
    }

    fun release(token: Long): Boolean = activeToken.compareAndSet(token, NO_TOKEN)

    fun invalidate() {
        activeToken.set(NO_TOKEN)
    }

    internal fun hasActiveUpload(): Boolean = activeToken.get() != NO_TOKEN

    private companion object {
        const val NO_TOKEN = 0L
    }
}
