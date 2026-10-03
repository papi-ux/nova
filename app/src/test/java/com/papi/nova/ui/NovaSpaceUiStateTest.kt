package com.papi.nova.ui

import com.papi.nova.manager.WorkerLaunchContract
import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.Assert.*
import org.junit.Test

class NovaSpaceUiStateTest {
    private val space = PolarisGame(id = WorkerLaunchContract.APP_UUID, name = "Living Room")

    @Test fun singleAssignedSpaceGetsTheDedicatedHome() {
        assertEquals(space, NovaSpaceUiState.singleSpace(listOf(space)))
        val numeric = space.copy(id = WorkerLaunchContract.APP_ID.toString())
        assertEquals(numeric, NovaSpaceUiState.singleSpace(listOf(numeric)))
    }

    @Test fun ordinaryOrMixedLibrariesKeepTheirExistingHome() {
        val game = space.copy(id = "ordinary-game")
        assertNull(NovaSpaceUiState.singleSpace(emptyList()))
        assertNull(NovaSpaceUiState.singleSpace(listOf(game)))
        assertNull(NovaSpaceUiState.singleSpace(listOf(space, game)))
        assertFalse(NovaSpaceUiState.isSpace(game.copy(name = "Your Space")))
    }

    @Test fun onlyAnOwnedSpaceSessionOffersResume() {
        assertEquals(NovaSpaceUiState.Availability.RESUMABLE, NovaSpaceUiState.availability(space, session(true)))
        assertEquals(NovaSpaceUiState.Availability.IN_USE, NovaSpaceUiState.availability(space, session(false)))
        assertEquals(NovaSpaceUiState.Availability.AVAILABLE, NovaSpaceUiState.availability(space, null))
    }

    @Test fun hostSessionWithTheSameNameCannotOfferResumeOrBlockTheSpace() {
        val unrelated = session(true).copy(gameId = 10, gameUuid = "ordinary-game", gameName = space.name)
        assertNull(NovaSpaceUiState.matchingSession(space, unrelated))
        assertEquals(NovaSpaceUiState.Availability.AVAILABLE, NovaSpaceUiState.availability(space, unrelated.copy(ownedByClient = false)))
    }

    @Test fun conflictingOrPartialIdentityCannotAuthorizeASessionAction() {
        assertNull(NovaSpaceUiState.matchingSession(space, session(true).copy(gameId = 10)))
        assertNull(NovaSpaceUiState.matchingSession(space, session(true).copy(gameUuid = "")))
        assertNull(NovaSpaceUiState.matchingSession(space.copy(id = "ordinary"), session(true)))
    }

    @Test fun settingsExcludeHostTopologyAndSteamAccountControls() {
        val rows = NovaPlaySetupRow.entries.map { row ->
            NovaPlaySetupRowState(row, row.name, "", "", emptyList())
        }
        assertEquals(listOf(NovaPlaySetupRow.RESOLUTION, NovaPlaySetupRow.FRAME_RATE),
            NovaSpaceUiState.streamingRows(rows).map { it.row })
    }

    @Test fun savedFrameRateIsSentToTheHostBeforeLaunching() {
        val planner = NovaSpaceUiState.resolutionPlanner(1920, 1080, 60f)
        val request = NovaSpaceUiState.request(planner.visibleChoices[1], 120, 1920, 1080, 60f)
        assertEquals(NovaSpaceUiState.Request(1280, 720, 120f), request)
        assertEquals(60f, NovaSpaceUiState.request(planner.visibleChoices[1], null, 1920, 1080, 60f).fps)
    }

    @Test fun deviceResolutionChoiceTracksTheCurrentDeviceSettings() {
        val first = NovaSpaceUiState.resolutionPlanner(1920, 1080, 60f).visibleChoices.first()
        val next = NovaSpaceUiState.resolutionPlanner(1280, 800, 120f).visibleChoices.first()
        assertEquals(first.id, next.id)
        assertEquals(NovaSpaceUiState.Request(1280, 800, 120f), NovaSpaceUiState.request(next, null, 1280, 800, 120f))
    }

    @Test fun autoChoicesAcceptTheHostDisplayContract() {
        val accepted = WorkerLaunchContract.Contract("space", 1920, 1080, 60, 8000)
        val device = NovaSpaceUiState.resolutionPlanner(1280, 800, 120f).visibleChoices.first()
        assertNull(NovaSpaceUiState.constrainedRequest(null, null, accepted))
        assertNull(NovaSpaceUiState.constrainedRequest(device, null, accepted))
        assertNull(NovaSpaceUiState.constrainedRequest(null, 120, null))
    }

    @Test fun explicitFrameRateCannotSilentlyLaunchAtAnotherRate() {
        val accepted = WorkerLaunchContract.Contract("space", 1920, 1080, 60, 8000)
        assertEquals(accepted, NovaSpaceUiState.constrainedRequest(null, 120, accepted))
        assertNull(NovaSpaceUiState.constrainedRequest(null, 60, accepted))
    }

    @Test fun explicitResolutionCannotSilentlyLaunchAtAnotherSize() {
        val accepted = WorkerLaunchContract.Contract("space", 1920, 1080, 60, 8000)
        val choices = NovaSpaceUiState.resolutionPlanner(1920, 1080, 60f).visibleChoices
        assertEquals(accepted, NovaSpaceUiState.constrainedRequest(choices[1], null, accepted))
        assertNull(NovaSpaceUiState.constrainedRequest(choices[2], 60, accepted))
    }

    private fun session(owned: Boolean) = NovaLibraryActiveSessionUiState(
        gameId = WorkerLaunchContract.APP_ID, gameUuid = WorkerLaunchContract.APP_UUID,
        gameName = "Living Room", ownerDeviceName = "Handheld", ownedByClient = owned,
        viewerCount = 0, virtualDisplay = false, displayModeExplicit = false,
        streamWidth = 1920, streamHeight = 1080, streamFps = 120f,
    )
}
