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
 */
class NovaStreamRecapStore {
    private var latestBegun = 0L
    private var current: NovaStreamRecap.Recap? = null

    /** A new Game instance begins: its generation, which fences every older one. */
    @Synchronized
    fun begin(): Long = ++latestBegun

    /** Returns whether [recap] became the current one. */
    @Synchronized
    fun publish(recap: NovaStreamRecap.Recap): Boolean {
        if (recap.generation != latestBegun) return false
        if ((current?.generation ?: 0L) >= recap.generation) return false
        current = recap
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
    @Synchronized
    fun dismiss(generation: Long): Boolean {
        if (current?.generation != generation) return false
        current = null
        return true
    }

    companion object {
        /** The process's own store. */
        val process = NovaStreamRecapStore()
    }
}
