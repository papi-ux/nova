package com.papi.nova.ui

import com.papi.nova.nvstream.http.ComputerDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovaHostPresenceTest {
    @Test
    fun presenceSaysOnlyWhatTheHostListKnows() {
        assertEquals(NovaHostPresence.AVAILABLE, presence(ComputerDetails.State.ONLINE, runningGameId = 0))
        assertEquals(NovaHostPresence.GAME_RUNNING, presence(ComputerDetails.State.ONLINE, runningGameId = 42))
        assertEquals(NovaHostPresence.UNKNOWN, presence(ComputerDetails.State.UNKNOWN, runningGameId = 0))
        assertNull(NovaHostPresence.from(null))
    }

    @Test
    fun aHostThatDoesNotAnswerIsOfflineNeverAsleepOrStillPlaying() {
        // Not answering cannot tell sleep from power off or a lost network, and the game the
        // host last reported is not current once the host stops answering.
        assertEquals(NovaHostPresence.OFFLINE, presence(ComputerDetails.State.OFFLINE, runningGameId = 0))
        assertEquals(NovaHostPresence.OFFLINE, presence(ComputerDetails.State.OFFLINE, runningGameId = 42))
    }

    private fun presence(state: ComputerDetails.State, runningGameId: Int) =
        NovaHostPresence.from(ComputerDetails().apply {
            this.state = state
            this.runningGameId = runningGameId
        })
}
