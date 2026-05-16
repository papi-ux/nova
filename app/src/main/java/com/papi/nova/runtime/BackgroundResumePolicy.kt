package com.papi.nova.runtime

internal object BackgroundResumePolicy {
    fun shouldSyncDisconnectTimeout(
        preferencesReady: Boolean,
        watchOnlyRequested: Boolean,
        keepStreamAlive: Boolean,
        alreadySynced: Boolean
    ): Boolean =
        preferencesReady &&
            !watchOnlyRequested &&
            keepStreamAlive &&
            !alreadySynced

    fun shouldPrepareResumeWindow(
        alreadyPrepared: Boolean,
        preferencesReady: Boolean,
        quitOnStop: Boolean,
        watchOnlyRequested: Boolean,
        keepStreamAlive: Boolean
    ): Boolean =
        !alreadyPrepared &&
            preferencesReady &&
            !quitOnStop &&
            !watchOnlyRequested &&
            keepStreamAlive
}
