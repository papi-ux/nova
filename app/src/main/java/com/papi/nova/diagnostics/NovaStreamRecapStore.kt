package com.papi.nova.diagnostics

/**
 * The one After-stream recap this process holds: in memory only, so it is lost when the process
 * dies, replaced by the next connection's recap, and gone when dismissed.
 *
 * Every Game instance takes a generation when it begins ([begin]). Only the newest begun
 * generation may publish, and only once, so a late publication from an older Game (one that a
 * newer stream has already started after) can never replace what the newer one shows.
 *
 * A recap is shown only for the requested host it was launched for. A missing or blank identity
 * on either side matches nothing; there is no any-host fallback.
 *
 * A Game publishes from its onStop, which Android runs after the screen it returns to has
 * resumed, so a reader that is already showing listens ([addListener]) as well as reading.
 */
class NovaStreamRecapStore {
    private var latestBegun = 0L
    private var current: NovaStreamRecap.Recap? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    /** A new Game instance begins: its generation, which fences every older one. */
    @Synchronized
    fun begin(): Long = ++latestBegun

    /** Returns whether [recap] became the current one. */
    fun publish(recap: NovaStreamRecap.Recap): Boolean {
        val heard = synchronized(this) {
            if (recap.generation != latestBegun) return false
            if ((current?.generation ?: 0L) >= recap.generation) return false
            current = recap
            listeners.toList()
        }
        heard.forEach { it() }
        return true
    }

    /** The recap for this Library's requested host, if there is one. Read-only. */
    @Synchronized
    fun forHost(host: String?): NovaStreamRecap.Recap? {
        val wanted = host?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val recap = current ?: return null
        val launched = recap.launch.requestedHost?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return recap.takeIf { launched == wanted }
    }

    /** Dismisses the recap of [generation] only, so a stale card can't remove a newer one. */
    fun dismiss(generation: Long): Boolean {
        val heard = synchronized(this) {
            if (current?.generation != generation) return false
            current = null
            listeners.toList()
        }
        heard.forEach { it() }
        return true
    }

    /**
     * Calls [listener] after each change to the current recap, outside the store's lock, until it
     * is removed. It is told only that something changed: it reads [forHost] for what it may show.
     */
    @Synchronized
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    @Synchronized
    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    companion object {
        /** The process's own store. */
        val process = NovaStreamRecapStore()
    }
}
