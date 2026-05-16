package com.papi.nova.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundResumePolicyTest {
    @Test
    fun syncDisconnectTimeoutRequiresReadyResumableStream() {
        assertTrue(
            BackgroundResumePolicy.shouldSyncDisconnectTimeout(
                preferencesReady = true,
                watchOnlyRequested = false,
                keepStreamAlive = true,
                alreadySynced = false
            )
        )

        assertFalse(
            BackgroundResumePolicy.shouldSyncDisconnectTimeout(
                preferencesReady = false,
                watchOnlyRequested = false,
                keepStreamAlive = true,
                alreadySynced = false
            )
        )
        assertFalse(
            BackgroundResumePolicy.shouldSyncDisconnectTimeout(
                preferencesReady = true,
                watchOnlyRequested = true,
                keepStreamAlive = true,
                alreadySynced = false
            )
        )
        assertFalse(
            BackgroundResumePolicy.shouldSyncDisconnectTimeout(
                preferencesReady = true,
                watchOnlyRequested = false,
                keepStreamAlive = false,
                alreadySynced = false
            )
        )
        assertFalse(
            BackgroundResumePolicy.shouldSyncDisconnectTimeout(
                preferencesReady = true,
                watchOnlyRequested = false,
                keepStreamAlive = true,
                alreadySynced = true
            )
        )
    }

    @Test
    fun prepareResumeWindowSkipsQuitWatchAndAlreadyPreparedSessions() {
        assertTrue(
            BackgroundResumePolicy.shouldPrepareResumeWindow(
                alreadyPrepared = false,
                preferencesReady = true,
                quitOnStop = false,
                watchOnlyRequested = false,
                keepStreamAlive = true
            )
        )

        assertFalse(
            BackgroundResumePolicy.shouldPrepareResumeWindow(
                alreadyPrepared = true,
                preferencesReady = true,
                quitOnStop = false,
                watchOnlyRequested = false,
                keepStreamAlive = true
            )
        )
        assertFalse(
            BackgroundResumePolicy.shouldPrepareResumeWindow(
                alreadyPrepared = false,
                preferencesReady = false,
                quitOnStop = false,
                watchOnlyRequested = false,
                keepStreamAlive = true
            )
        )
        assertFalse(
            BackgroundResumePolicy.shouldPrepareResumeWindow(
                alreadyPrepared = false,
                preferencesReady = true,
                quitOnStop = true,
                watchOnlyRequested = false,
                keepStreamAlive = true
            )
        )
        assertFalse(
            BackgroundResumePolicy.shouldPrepareResumeWindow(
                alreadyPrepared = false,
                preferencesReady = true,
                quitOnStop = false,
                watchOnlyRequested = true,
                keepStreamAlive = true
            )
        )
        assertFalse(
            BackgroundResumePolicy.shouldPrepareResumeWindow(
                alreadyPrepared = false,
                preferencesReady = true,
                quitOnStop = false,
                watchOnlyRequested = false,
                keepStreamAlive = false
            )
        )
    }
}
