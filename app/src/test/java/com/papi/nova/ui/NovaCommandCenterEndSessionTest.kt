package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import org.junit.Assert.*
import org.junit.Test

class NovaCommandCenterEndSessionTest {
    private val owner = PolarisSessionStatus(
        state = "streaming", streamingActive = true, ownedByClient = true,
        clientRole = "owner", appSessionId = "session-1", appSessionIdPresent = true,
        sessionGeneration = 41L,
        controls = PolarisSessionStatus.ControlsStatus(hostTuningAllowed = true, quitAllowed = true),
    )

    @Test fun confirmationRechecksAuthorityAfterTheSplitArms() {
        assertTrue("the original reading independently permits End", owner.canQuit)
        var reading: PolarisSessionStatus? = owner
        var closed = 0
        var ended = 0
        var left = 0
        var refused = 0
        val action = NovaCommandCenterEndSession(
            polaris = { true }, status = { reading }, space = { false }, standing = { true },
            close = { closed++ }, end = { ended++ }, disconnect = { left++ },
            unavailable = { refused++ }, ending = { fail("not shutting down") },
        )
        assertTrue(NovaCommandCenterEndSession.enabled(true, reading, false))
        reading = null
        action.perform()
        assertEquals(1, refused)
        assertEquals(0, closed)
        assertEquals(0, ended)
        assertEquals(0, left)
        reading = owner
        action.perform()
        assertEquals(1, closed)
        assertEquals(1, ended)
    }

    @Test fun viewerLeaveAndSpaceLeaveRemainLocalChoicesAndLegacyHostsStillEnd() {
        fun run(polaris: Boolean, reading: PolarisSessionStatus?, space: Boolean): List<String> {
            val events = mutableListOf<String>()
            NovaCommandCenterEndSession(
                polaris = { polaris }, status = { reading }, space = { space }, standing = { true },
                close = { events += "close" }, end = { events += "end" }, disconnect = { events += "disconnect" },
                unavailable = { events += "unavailable" }, ending = { events += "ending" },
            ).perform()
            return events
        }
        val viewer = owner.copy(clientRole = "viewer", ownedByClient = false,
            controls = owner.controls.copy(quitAllowed = false))
        assertFalse(viewer.canQuit)
        assertEquals(listOf("close", "disconnect"), run(true, viewer, false))
        assertEquals(listOf("unavailable"), run(true, null, false))
        assertEquals(listOf("close", "end"), run(false, null, false))
        assertEquals(listOf("close", "end"), run(true, null, true))
    }

    @Test fun anOldOpeningCannotActAfterItCloses() {
        NovaCommandCenterEndSession(
            polaris = { true }, status = { owner }, space = { false }, standing = { false },
            close = { fail("old opening closed again") }, end = { fail("old opening ended session") },
            disconnect = { fail("old opening disconnected") }, unavailable = { fail("old opening answered") },
            ending = { fail("old opening answered") },
        ).perform()
    }
}
