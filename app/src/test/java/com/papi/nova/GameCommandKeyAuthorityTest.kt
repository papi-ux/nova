package com.papi.nova

import android.os.Looper
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.nvstream.NvConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameCommandKeyAuthorityTest {
    private fun field(game: Game, key: String, value: Any?) {
        Game::class.java.getDeclaredField(key).apply { isAccessible = true }.set(game, value)
    }

    @Test fun finalSenderRejectsViewerWatchOnlyAndClosedStreams() {
        val game = Robolectric.buildActivity(Game::class.java).get()
        val connection = mock(NvConnection::class.java)
        field(game, "conn", connection)
        field(game, "lastPolarisSessionStatus", PolarisSessionStatus("streaming", clientRole = "viewer"))
        assertFalse(game.canSendCommandKeys())
        game.sendKeys(shortArrayOf(27))
        verifyNoInteractions(connection)
        field(game, "lastPolarisSessionStatus", null)
        field(game, "watchOnlyRequested", true)
        game.sendKeys(shortArrayOf(27))
        verifyNoInteractions(connection)
        field(game, "watchOnlyRequested", false)
        assertTrue("legacy owner launch permits keys without Polaris status", game.canSendCommandKeys())
        game.finish()
        game.sendKeys(shortArrayOf(27))
        verifyNoInteractions(connection)
    }

    @Test fun authorityChangeStopsNewKeyDownsButReleasesKeysAlreadySent() {
        val game = Robolectric.buildActivity(Game::class.java).get()
        val connection = mock(NvConnection::class.java)
        field(game, "conn", connection)
        game.sendKeys(shortArrayOf(27))
        verify(connection, times(1)).sendKeyboardInput(anyShort(), anyByte(), anyByte(), anyByte())
        field(game, "lastPolarisSessionStatus", PolarisSessionStatus("streaming", clientRole = "viewer"))
        game.sendKeys(shortArrayOf(27))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        verify(connection, times(2)).sendKeyboardInput(anyShort(), anyByte(), anyByte(), anyByte())
    }
}
