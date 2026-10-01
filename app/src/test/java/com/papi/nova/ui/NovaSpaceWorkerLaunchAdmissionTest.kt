package com.papi.nova.ui

import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.isLaunchModeAvailable
import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.Assert.*
import org.junit.Test

/** The Space worker advertises its own capture mode separately from the desktop. */
class NovaSpaceWorkerLaunchAdmissionTest {
    private val space = PolarisGame(
        id = "space.living-room.123",
        name = "A Space game",
        space = PolarisGame.SpaceContext("living-room", "Living room", "123"),
        launchMode = PolarisGame.LaunchModeContract(
            preferredMode = PolarisGame.MODE_GAMESCOPE_STREAM,
            recommendedMode = PolarisGame.MODE_GAMESCOPE_STREAM,
            allowedModes = listOf(PolarisGame.MODE_GAMESCOPE_STREAM),
        ),
    )
    private fun host(gamescope: Boolean?) = PolarisClientSettings(
        desired = PolarisClientSettings.Desired(streamDisplayMode = PolarisGame.MODE_HEADLESS_STREAM),
        capabilities = PolarisClientSettings.Capabilities(modes = buildList {
            add(PolarisClientSettings.ModeOption(value = PolarisGame.MODE_HEADLESS_STREAM, available = true))
            if (gamescope != null) add(PolarisClientSettings.ModeOption(
                value = PolarisGame.MODE_GAMESCOPE_STREAM, available = gamescope, sessionOverridable = true))
        }),
    )
    private fun detail(game: PolarisGame, host: PolarisClientSettings) = NovaGameDetailUiState.from(
        game, defaultToVirtualDisplay = false, clientSettings = host, profilePreference = "auto")

    @Test fun anOpenableWorkerModeIsNotBlockedByUnavailableDesktopGamescope() {
        val state = detail(space, host(false))
        assertTrue(state.playEnabled)
        assertTrue(state.runsInSpace)
        assertEquals(PolarisGame.MODE_GAMESCOPE_STREAM, state.playMode)
        assertEquals(PolarisGame.MODE_GAMESCOPE_STREAM, state.launchStreamMode)
        assertFalse(state.usesSafeHostFallback)
    }

    @Test fun anOmittedDesktopGamescopeStillResolvesTheSpaceWorker() {
        val state = detail(space, host(null))
        assertTrue(state.playEnabled)
        assertEquals(PolarisGame.MODE_GAMESCOPE_STREAM, state.playMode)
    }

    @Test fun ordinaryGamesStillRespectUnavailableAndOmittedDesktopModes() {
        for (catalog in listOf(host(false), host(null))) {
            val state = detail(space.copy(id = "ordinary", space = null), catalog)
            assertFalse(state.playEnabled)
            assertEquals("", state.playMode)
        }
    }

    @Test fun aSpaceCannotInventAPermissionAbsentFromItsEntryContract() {
        for (contract in listOf(null, space.launchMode!!.copy(allowedModes = listOf(PolarisGame.MODE_HEADLESS_STREAM)))) {
            val game = space.copy(launchMode = contract)
            assertFalse(game.isLaunchModeAvailable(PolarisGame.MODE_GAMESCOPE_STREAM, host(false)))
        }
    }

    @Test fun malformedOrMismatchedSpaceIdentityCannotBypassTheDesktopCatalog() {
        for (game in listOf(
            space.copy(space = space.space!!.copy(id = "")),
            space.copy(space = space.space!!.copy(target = "")),
            space.copy(id = "ordinary"),
            space.copy(space = space.space!!.copy(id = "other")),
        )) assertFalse(game.isLaunchModeAvailable(PolarisGame.MODE_GAMESCOPE_STREAM, host(false)))
    }

    @Test fun workerPermissionDoesNotAdmitOtherUnavailableDesktopModes() {
        val game = space.copy(launchMode = space.launchMode!!.copy(
            allowedModes = listOf(PolarisGame.MODE_GAMESCOPE_STREAM, PolarisGame.MODE_HOST_VIRTUAL_DISPLAY)))
        assertFalse(game.isLaunchModeAvailable(PolarisGame.MODE_HOST_VIRTUAL_DISPLAY, host(false)))
    }
}
