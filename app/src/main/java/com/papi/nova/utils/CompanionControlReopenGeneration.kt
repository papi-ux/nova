package com.papi.nova.utils

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic ownership token for delayed companion reopen callbacks.
 *
 * A newer notification request, a user Hide action, or a new stream invalidates
 * every previously issued token so stale delayed work cannot override newer intent.
 */
class CompanionControlReopenGeneration {
    private val generation = AtomicLong(0L)

    fun beginRequest(): Long = generation.incrementAndGet()

    fun invalidatePendingRequests() {
        generation.incrementAndGet()
    }

    fun isCurrent(requestGeneration: Long): Boolean =
        generation.get() == requestGeneration
}
