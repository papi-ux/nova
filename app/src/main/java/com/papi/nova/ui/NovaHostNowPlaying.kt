package com.papi.nova.ui

import android.content.Context
import java.security.cert.X509Certificate
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
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
    val sessionToken: String = "",
    val gameUuid: String = "",
    val appSessionId: String = "",
    val sessionGeneration: Long = 0L,
    val ownerUniqueId: String = "",
    val host: Host? = null,
) {
    data class Host(val uuid: String, val address: String, val httpsPort: Int, val cert: X509Certificate)
}

/** The host's session, if this device owns it and can still resume it. */
internal fun novaHostNowPlaying(status: PolarisSessionStatus?): NovaHostNowPlaying? =
    status?.takeIf {
        it.isResumable && it.ownedByClient && !it.isViewer && it.authorityContractValid && it.sessionToken.isNotBlank()
    }?.let {
        NovaHostNowPlaying(it.gameId, it.game, sessionToken = it.sessionToken, gameUuid = it.gameUuid,
            appSessionId = it.appSessionId, sessionGeneration = it.sessionGeneration, ownerUniqueId = it.ownerUniqueId)
    }

/** A press belongs to what was drawn, including its host route and session, through confirmation. */
internal class NovaHostSessionAction(computer: ComputerDetails, val playing: NovaHostNowPlaying) {
    private val host = ComputerDetails(computer).apply {
        activeAddress = computer.activeAddress?.let { ComputerDetails.AddressTuple(it.address, it.port) }
    }
    val hostUuid: String get() = host.uuid

    fun computer(): ComputerDetails = ComputerDetails(host).apply {
        activeAddress = host.activeAddress?.let { ComputerDetails.AddressTuple(it.address, it.port) }
    }

    fun matches(current: ComputerDetails, observed: NovaHostNowPlaying?): Boolean =
        host.uuid.isNotBlank() && host.activeAddress != null && host.serverCert != null &&
            host.httpsPort in 1..65535 &&
            playing.host == NovaHostNowPlaying.Host(host.uuid, host.activeAddress!!.address, host.httpsPort, host.serverCert!!) &&
            current.uuid == host.uuid && current.state == ComputerDetails.State.ONLINE &&
            current.pairState == PairingManager.PairState.PAIRED && current.serverCert == host.serverCert &&
            current.activeAddress == host.activeAddress && current.httpsPort == host.httpsPort &&
            current.runningGameId == playing.gameId && current.currentGameOwnedByClient == true &&
            (playing.gameUuid.isBlank() || current.runningGameUUID == playing.gameUuid) &&
            playing.sessionToken.isNotBlank() && observed != null &&
            playing.copy(game = null) == observed.copy(game = null)
}

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
