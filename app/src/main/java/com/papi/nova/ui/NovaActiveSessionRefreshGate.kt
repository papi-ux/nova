package com.papi.nova.ui

internal class NovaActiveSessionRefreshGate {
    private val lock = Any()
    private var generation = 0L
    private var refreshOnResumeNeeded = false

    fun begin(): Long = synchronized(lock) {
        generation += 1
        generation
    }

    fun invalidate(): Long = begin()

    fun invalidateForStop(): Long = synchronized(lock) {
        refreshOnResumeNeeded = true
        generation += 1
        generation
    }

    fun shouldRefreshOnResume(isInitialLoading: Boolean): Boolean = synchronized(lock) {
        val shouldRefresh = refreshOnResumeNeeded || !isInitialLoading
        refreshOnResumeNeeded = false
        shouldRefresh
    }

    fun isCurrent(candidate: Long): Boolean = synchronized(lock) {
        candidate == generation
    }

    fun publishIfCurrent(candidate: Long, publish: () -> Unit): Boolean = synchronized(lock) {
        if (candidate != generation) return@synchronized false
        publish()
        true
    }
}
