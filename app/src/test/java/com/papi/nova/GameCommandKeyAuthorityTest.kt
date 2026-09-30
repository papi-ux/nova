package com.papi.nova

import android.os.Looper
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.binding.input.capture.InputCaptureProvider
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.input.KeyboardPacket
import com.papi.nova.ui.CommandCenterPage
import com.papi.nova.ui.NovaQuickMenu
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.shadows.ShadowMoonBridge
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
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

    private data class Reading(val code: Int, val body: String)

    private fun status(role: String, id: String = "stream-a", generation: Long = 7) = JSONObject()
        .put("state", "streaming").put("streaming_active", true)
        .put("client_role", role).put("owned_by_client", role == "owner")
        .put("app_session_id", id).put("session_generation", generation)
        .put("controls", JSONObject().put("host_tuning_allowed", role == "owner"))
        .toString()

    private fun client(game: Game, reading: AtomicReference<Reading>, beforeResponse: () -> Unit = {}): PolarisApiClient {
        val api = PolarisApiClient(game, "127.0.0.1", 47984)
        val intercepted = OkHttpClient.Builder().addInterceptor { chain ->
            beforeResponse()
            val next = reading.get()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(next.code).message("fixture").body(ResponseBody.create(null, next.body)).build()
        }.build()
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, intercepted)
        return api
    }

    private fun stream(): Game {
        val controller = Robolectric.buildActivity(Game::class.java)
        val game = controller.get()
        field(game, "inputCaptureProvider", mock(InputCaptureProvider::class.java))
        field(game, "conn", mock(NvConnection::class.java))
        controller.visible()
        return game
    }

    private fun beginRefresh(game: Game) {
        Game::class.java.getDeclaredMethod("refreshPolarisLiveSessionStatus").apply { isAccessible = true }.invoke(game)
    }

    private fun awaitRefresh(game: Game) {
        val inFlight = Game::class.java.getDeclaredField("polarisSessionStatusRefreshInFlight")
            .apply { isAccessible = true }.get(game) as AtomicBoolean
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (inFlight.get() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        assertFalse("the production refresh completed", inFlight.get())
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun refresh(game: Game) { beginRefresh(game); awaitRefresh(game) }

    /** Capture the production Keys page's Action, rather than constructing a test-only key callback. */
    private fun existingKeyAction(game: Game): () -> Unit {
        val menu = NovaQuickMenu(game)
        val sessionType = Class.forName("com.papi.nova.ui.NovaQuickMenu\$MenuSession")
        val session = sessionType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(null, CommandCenterPage.KeysKey)
        val page = NovaQuickMenu::class.java.getDeclaredMethod("keysPage", sessionType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(menu, session, false) as CommandCenterPage.Keys
        return page.sections.flatMap { it.items }.filterIsInstance<NovaMenuItem.Action>().first().onClick
    }

    private fun drainKeys() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    @Test fun viewerThenFailedGetKeepsAnExistingKeysPageBlockedUntilTheSameOwnerIsConfirmed() {
        val game = stream()
        val reading = AtomicReference(Reading(200, status("viewer")))
        val api = client(game, reading)
        game.novaApiClient = api
        refresh(game)
        val action = existingKeyAction(game)
        assertFalse(game.canSendCommandKeys())
        action(); drainKeys()
        verifyNoInteractions(game.conn)

        reading.set(Reading(404, "{}"))
        refresh(game)
        assertNull("failed telemetry stays unavailable", api.sessionStatusUpdates.value)
        assertNull(Game::class.java.getDeclaredField("lastPolarisSessionStatus").apply { isAccessible = true }.get(game))
        action(); drainKeys()
        verifyNoInteractions(game.conn)

        reading.set(Reading(200, status("owner")))
        refresh(game)
        action(); drainKeys()
        verify(game.conn!!, times(1)).sendKeyboardInput(anyShort(), eq(KeyboardPacket.KEY_DOWN), anyByte(), anyByte())
        verify(game.conn!!, times(1)).sendKeyboardInput(anyShort(), eq(KeyboardPacket.KEY_UP), anyByte(), anyByte())
    }

    @Test fun delayedKeyDispatchDoesNotGainPermissionAfterAViewerReadAndItsFailedSuccessor() {
        val game = stream()
        val reading = AtomicReference(Reading(200, status("owner")))
        game.novaApiClient = client(game, reading)
        refresh(game)
        existingKeyAction(game)() // The production 25ms focus delay is still pending.
        reading.set(Reading(200, status("viewer")))
        refresh(game)
        reading.set(Reading(404, "{}"))
        refresh(game)
        drainKeys()
        verifyNoInteractions(game.conn)
    }

    @Test fun anotherSessionOrMalformedOwnerCannotRemoveTheViewerFence() {
        val game = stream()
        val reading = AtomicReference(Reading(200, status("viewer")))
        game.novaApiClient = client(game, reading)
        refresh(game)
        val action = existingKeyAction(game)
        val refused = listOf(
            status("owner", id = "stream-b"),
            status("owner", generation = 8),
            JSONObject(status("owner")).put("owned_by_client", false).toString(),
            JSONObject(status("owner")).apply { remove("controls") }.toString(),
        )
        for (body in refused) {
            reading.set(Reading(200, body)); refresh(game)
            action(); drainKeys()
            verifyNoInteractions(game.conn)
        }
        reading.set(Reading(200, status("owner"))); refresh(game)
        action(); drainKeys()
        verify(game.conn!!, times(1)).sendKeyboardInput(anyShort(), eq(KeyboardPacket.KEY_DOWN), anyByte(), anyByte())
    }

    @Test fun replacementClientDropsAnOldReadingAndAnOldPageCannotSendIntoTheNewStream() {
        val game = stream()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = client(game, AtomicReference(Reading(200, status("viewer")))) {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        }
        game.novaApiClient = old
        val oldAction = existingKeyAction(game)
        beginRefresh(game)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val replacement = client(game, AtomicReference(Reading(200, status("owner", id = "stream-b", generation = 8))))
            game.novaApiClient = replacement
            assertNotNull(replacement.getSessionStatus())
            release.countDown(); awaitRefresh(game)
            assertNull("the old refresh cannot write the new stream's fallback", Game::class.java
                .getDeclaredField("lastPolarisSessionStatus").apply { isAccessible = true }.get(game))
            assertTrue("the new client's owner permission is independent", game.canSendCommandKeys())
            oldAction(); drainKeys()
            verifyNoInteractions(game.conn)
            existingKeyAction(game)(); drainKeys()
            verify(game.conn!!, times(1)).sendKeyboardInput(anyShort(), eq(KeyboardPacket.KEY_DOWN), anyByte(), anyByte())
        } finally { release.countDown() }
    }

    @Test fun connectionReplacementDuringTheFocusDelayDoesNotRedirectKeys() {
        val game = stream()
        val original = game.conn!!
        existingKeyAction(game)()
        game.conn = mock(NvConnection::class.java)
        drainKeys()
        verifyNoInteractions(original, game.conn)
    }

    @Test fun aViewerPollAndFailedPollStillReleaseKeysOnTheirOriginalConnection() {
        val game = stream()
        val original = game.conn!!
        val reading = AtomicReference(Reading(200, status("owner")))
        game.novaApiClient = client(game, reading)
        refresh(game)
        game.sendKeys(shortArrayOf(27))
        reading.set(Reading(200, status("viewer"))); refresh(game)
        reading.set(Reading(404, "{}")); refresh(game)
        game.sendKeys(shortArrayOf(27))
        game.conn = mock(NvConnection::class.java)
        drainKeys()
        verify(original, times(1)).sendKeyboardInput(eq(27.toShort()), eq(KeyboardPacket.KEY_DOWN), anyByte(), anyByte())
        verify(original, times(1)).sendKeyboardInput(eq(27.toShort()), eq(KeyboardPacket.KEY_UP), anyByte(), anyByte())
        verifyNoInteractions(game.conn)
    }

    @Test fun legacyMissingEndpointIsAllowedUntilAnActualViewerObservationAndWatchOnlyIsAlwaysRefused() {
        val game = stream()
        val reading = AtomicReference(Reading(404, "{}"))
        game.novaApiClient = client(game, reading)
        refresh(game)
        assertTrue(game.canSendCommandKeys())
        field(game, "watchOnlyRequested", true)
        assertFalse(game.canSendCommandKeys())
        field(game, "watchOnlyRequested", false)
        reading.set(Reading(200, status("viewer", id = ""))); refresh(game)
        reading.set(Reading(200, status("owner"))); refresh(game)
        existingKeyAction(game)(); drainKeys()
        verifyNoInteractions(game.conn)
    }
}
