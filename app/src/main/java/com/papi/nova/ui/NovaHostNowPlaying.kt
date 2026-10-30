package com.papi.nova.ui

import android.content.Context
import com.papi.nova.R
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.shared.polaris.model.PolarisGame

/**
 * This device's game still running on a host, for the pill on its Hosts card (#419). It is the same
 * resumable state the Library's Resume strip reads. It names no time: the host doesn't report when
 * its session started or paused, and how long the game has been up can't be worked out from here.
 */
internal data class NovaHostNowPlaying(
    val gameId: Int,
    val gameName: String,
    val game: PolarisGame? = null,
)

/** The host's session, if this device owns it and can still resume it. */
internal fun novaHostNowPlaying(status: PolarisSessionStatus?): NovaHostNowPlaying? =
    status?.takeIf { it.isResumable && it.ownedByClient }?.let { NovaHostNowPlaying(it.gameId, it.game) }

/** The library's entry for the running game, for its artwork. */
internal fun novaNowPlayingGame(games: List<PolarisGame>, playing: NovaHostNowPlaying): PolarisGame? =
    games.firstOrNull { it.appId == playing.gameId }
        ?: games.firstOrNull { it.name.equals(playing.gameName, ignoreCase = true) }

private fun NovaHostNowPlaying.name(context: Context) =
    gameName.ifBlank { context.getString(R.string.applist_menu_watch_active_name) }

/** "Hades · Resume". */
internal fun novaNowPlayingWords(context: Context, playing: NovaHostNowPlaying): String =
    context.getString(R.string.nova_host_now_playing, playing.name(context), context.getString(R.string.pcview_card_action_resume))

/** The pill as TalkBack reads it, without the dot between its parts. */
internal fun novaNowPlayingSpoken(context: Context, playing: NovaHostNowPlaying): String =
    context.getString(R.string.nova_host_now_playing_spoken, playing.name(context), context.getString(R.string.pcview_card_action_resume))
