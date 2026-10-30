package com.papi.nova

import android.view.SurfaceHolder
import com.papi.nova.binding.input.ControllerHandler
import com.papi.nova.binding.video.NovaVideoRenderer
import com.papi.nova.diagnostics.NovaStreamRecap
import com.papi.nova.diagnostics.NovaStreamRecap.End
import com.papi.nova.diagnostics.NovaStreamRecapStore
import com.papi.nova.diagnostics.NovaStreamRecapTest
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Why the recap says a connection ended, through Game's own stop paths. Android destroys a
 * finishing Activity's surface before its onStop, so on a real End the surface callback is what
 * stops the stream. RP6, 2026-10-30: three deliberate Ends all read "Video surface lost" (#285).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameStreamRecapEndReasonTest {
    private val host = "end-reason-host"
    private val holder = Mockito.mock(SurfaceHolder::class.java)

    @Test fun endSessionIsRecordedAsTheUsersEndWhenTheSurfaceGoesFirst() {
        val game = connectedGame()
        game.endSession()
        game.surfaceDestroyed(holder)
        assertEquals(End.END_REQUESTED, recordedEnd())
    }

    @Test fun aSurfaceLostWithNothingElseKnownStaysASurfaceLoss() {
        val game = connectedGame()
        game.surfaceDestroyed(holder)
        assertEquals(End.SURFACE_LOST, recordedEnd())
    }

    // Disconnect and Back both finish Game with neither an End request nor a host report (#285).
    @Test fun aFinishingGameIsRecordedAsLeavingTheStreamWhenTheSurfaceGoesFirst() {
        val game = connectedGame()
        game.finish()
        game.surfaceDestroyed(holder)
        assertEquals(End.LEFT_STREAM, recordedEnd())
    }

    // Home and backgrounding take the surface without finishing, so the session is only hidden.
    @Test fun aSurfaceLostWithoutFinishingStaysASurfaceLoss() {
        val game = connectedGame()
        assertEquals(false, game.isFinishing)
        game.surfaceDestroyed(holder)
        assertEquals(End.SURFACE_LOST, recordedEnd())
    }

    @Test fun endSessionIsStillRecordedAsTheUsersEndWhenGameIsFinishing() {
        val game = connectedGame()
        game.endSession()
        game.finish()
        game.surfaceDestroyed(holder)
        assertEquals(End.END_REQUESTED, recordedEnd())
    }

    @Test fun theHostReportingItsSessionEndedIsPreferredOverTheSurfaceGoing() {
        val game = connectedGame()
        setField(game, "hostSessionEnded", true)
        game.finish()
        game.surfaceDestroyed(holder)
        assertEquals(End.HOST_END_REPORTED, recordedEnd())
    }

    @Test fun theHostReportingItsSessionEndedIsPreferredOverAnEndRequest() {
        val game = connectedGame()
        game.endSession()
        setField(game, "hostSessionEnded", true)
        game.surfaceDestroyed(holder)
        assertEquals(End.HOST_END_REPORTED, recordedEnd())
    }

    @Test fun aLateSurfaceCallbackCannotRewriteAnEndAlreadyRecorded() {
        val game = connectedGame()
        game.endSession()
        game.surfaceDestroyed(holder)
        game.surfaceDestroyed(holder)
        assertEquals(End.END_REQUESTED, recordedEnd())

        // onStop stopping the stream first, then the surface going, keeps its reason too.
        val other = connectedGame()
        other.endSession()
        call(other, "noteStreamRecapEnd", End.END_REQUESTED)
        call(other, "stopConnection")
        other.surfaceDestroyed(holder)
        assertEquals(End.END_REQUESTED, recordedEnd())
    }

    private fun recordedEnd(): End = NovaStreamRecapStore.process.forHost(host)!!.end

    /** A Game past connectionStarted, with what its stop path touches and nothing behind it. */
    private fun connectedGame(): Game {
        val game = Robolectric.buildActivity(Game::class.java).get()
        game.prefConfig = PreferenceConfiguration()
        setField(game, "conn", Mockito.mock(NvConnection::class.java))
        setField(game, "controllerHandler", Mockito.mock(ControllerHandler::class.java))
        setField(game, "decoderRenderer", Mockito.mock(NovaVideoRenderer::class.java))
        setField(game, "surfaceCreated", true)
        setField(game, "attemptedConnection", true)
        setField(game, "connected", true)
        val recap = field(game, "streamRecap") as NovaStreamRecap.Accumulator
        recap.launch(NovaStreamRecapTest.launch(host, false))
        recap.connected(1_000L)
        return game
    }

    private fun field(game: Game, name: String): Any? =
        Game::class.java.getDeclaredField(name).apply { isAccessible = true }.get(game)

    private fun setField(game: Game, name: String, value: Any) {
        Game::class.java.getDeclaredField(name).apply { isAccessible = true }.set(game, value)
    }

    private fun call(game: Game, name: String, vararg arguments: Any) {
        val method = Game::class.java.declaredMethods.first { it.name == name }.apply { isAccessible = true }
        val padded = arrayOfNulls<Any>(method.parameterCount)
        arguments.copyInto(padded)
        method.invoke(game, *padded)
    }
}
