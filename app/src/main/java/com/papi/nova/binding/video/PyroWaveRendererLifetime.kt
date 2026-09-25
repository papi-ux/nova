package com.papi.nova.binding.video

/**
 * Owns a native renderer until its last admitted call returns.
 *
 * Surface teardown can arrive before the network decode thread stops. Closing first prevents new
 * calls, then waits for the current call before destroying the handle. Cleanup may close it again.
 */
internal class PyroWaveRendererLifetime(private val destroy: (Long) -> Unit) {
    private val lock = Any()
    @Volatile private var stopping = false
    private var handle = 0L

    fun create(factory: () -> Long): Long = synchronized(lock) {
        if (stopping) return@synchronized 0L
        check(handle == 0L) { "Renderer already created" }
        factory().also { handle = it }
    }

    fun <T> useOrNull(action: (Long) -> T): T? {
        if (stopping) return null
        return synchronized(lock) {
            if (stopping || handle == 0L) null else action(handle)
        }
    }

    fun close() {
        stopping = true
        synchronized(lock) {
            val owned = handle
            handle = 0L
            if (owned != 0L) destroy(owned)
        }
    }
}
