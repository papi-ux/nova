package com.papi.nova.runtime

/**
 * Keeps Nova's live status view inside Polaris's short Doctor evidence window.
 *
 * Raw decoder counters arrive roughly once per second, and Polaris currently
 * treats network evidence as current for two seconds. Polling once per second
 * lets Command Center and NovaHUD observe the same current evidence that can
 * drive adaptive bitrate without adding idle-library traffic.
 */
internal object PolarisLiveStatusRefreshPolicy {
    const val ACTIVE_STREAM_POLL_INTERVAL_MS = 1_000L
}
