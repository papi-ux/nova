package com.papi.nova.ui

import com.papi.nova.manager.WorkerLaunchContract
import com.papi.nova.shared.polaris.model.PolarisGame

/** Space identity comes from the paired host contract, never a display name. */
internal object NovaSpaceUiState {
    enum class Availability { AVAILABLE, RESUMABLE, IN_USE }

    fun isSpace(game: PolarisGame): Boolean = WorkerLaunchContract.isProfileApp(game.id)

    fun singleSpace(games: List<PolarisGame>): PolarisGame? =
        games.singleOrNull()?.takeIf { WorkerLaunchContract.isLegacyProfileApp(it.id) }

    fun matchingSession(
        game: PolarisGame,
        session: NovaLibraryActiveSessionUiState?,
    ): NovaLibraryActiveSessionUiState? = session?.takeIf {
        isSpace(game) && WorkerLaunchContract.isProfileApp(it.gameUuid) &&
            (WorkerLaunchContract.isLegacyProfileApp(game.id) || it.gameUuid == game.id) &&
            it.gameId == WorkerLaunchContract.APP_ID
    }

    fun availability(game: PolarisGame, session: NovaLibraryActiveSessionUiState?): Availability =
        when (matchingSession(game, session)?.ownedByClient) {
            true -> Availability.RESUMABLE
            false -> Availability.IN_USE
            null -> Availability.AVAILABLE
        }

    data class Request(val width: Int, val height: Int, val fps: Float)

    /** An explicit choice must not silently become a different stream at launch. */
    fun constrainedRequest(
        choice: NovaDisplayResolutionChoice?, fps: Int?, accepted: WorkerLaunchContract.Contract?,
    ): WorkerLaunchContract.Contract? {
        accepted ?: return null
        val mode = choice?.takeUnless { it.id == "space_device" }?.targetMode?.split('x')
        val resolutionChanged = mode != null &&
            (mode.getOrNull(0)?.toIntOrNull() != accepted.width || mode.getOrNull(1)?.toIntOrNull() != accepted.height)
        return accepted.takeIf { resolutionChanged || fps != null && fps != it.fps }
    }

    fun request(choice: NovaDisplayResolutionChoice?, fps: Int?, width: Int, height: Int, defaultFps: Float): Request {
        val mode = choice?.targetMode?.split('x').orEmpty()
        return Request(mode.getOrNull(0)?.toIntOrNull() ?: width,
            mode.getOrNull(1)?.toIntOrNull() ?: height, fps?.toFloat() ?: defaultFps)
    }

    fun resolutionPlanner(width: Int, height: Int, fps: Float): NovaDisplayResolutionPlanner {
        val deviceMode = "${width}x${height}x${fps.toInt()}"
        val choices = listOf(
            NovaDisplayResolutionChoice("space_device", "This Device", deviceMode, "", "Use this device's saved resolution.", false, false, true, true),
            NovaDisplayResolutionChoice("space_720p", "1280 × 720", "1280x720x${fps.toInt()}", "", "Use a smaller stream to reduce bandwidth.", false, false, true, false),
            NovaDisplayResolutionChoice("space_1080p", "1920 × 1080", "1920x1080x${fps.toInt()}", "", "Use a sharper picture when your connection allows it.", false, false, true, false),
        )
        return NovaDisplayResolutionPlanner(true, deviceMode, "space_device", deviceMode, choices, false)
    }

    fun streamingRows(rows: List<NovaPlaySetupRowState>): List<NovaPlaySetupRowState> =
        rows.filter { it.row in setOf(NovaPlaySetupRow.RESOLUTION, NovaPlaySetupRow.FRAME_RATE) }
}
