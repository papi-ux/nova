package com.papi.nova

import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisCapabilities
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.manager.FeatureFlagManager
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import kotlin.concurrent.thread
import org.json.JSONObject
import java.util.logging.LogRecord
import java.util.logging.Logger
import com.papi.nova.preferences.PreferenceConfiguration
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class GamePresentationSyncTest {
    private val messages: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val logHandler = object : Handler() {
        override fun publish(record: LogRecord) { messages.add(record.message) }
        override fun flush() {}
        override fun close() {}
    }

    @Before
    fun captureLogs() {
        Logger.getLogger(LimeLog::class.java.name).addHandler(logHandler)
    }

    @After
    fun tearDown() {
        FeatureFlagManager.reset()
        Logger.getLogger(LimeLog::class.java.name).removeHandler(logHandler)
    }

    @Test
    fun unsupportedClientSettingsDoesNotEmitRepeatedFailureWarnings() {
        val game = gameWithClient(success = true)
        publishCapabilities(game, false)
        repeat(3) { report(game) }
        assertEquals(0, reportCount(game))
        assertFalse(messages.any { it.contains("Client presentation sync failed") })
    }

    @Test
    fun capabilityAppearingEnablesReportingAndSuccessfulReportsAreDeduplicated() {
        val game = gameWithClient(success = true)
        publishCapabilities(game, false)
        report(game)
        publishCapabilities(game, true)
        report(game)
        report(game)
        assertEquals(1, reportCount(game))
        assertTrue(messages.any { it.contains("Client presentation synced") })
    }

    @Test
    fun failedSupportedReportCanRetry() {
        val game = gameWithClient(success = false)
        publishCapabilities(game, true)
        repeat(2) { report(game) }
        assertEquals(2, reportCount(game))
        assertTrue(messages.any { it.contains("Client presentation sync failed") })
    }

    @Test
    fun stoppedStreamDoesNotReport() {
        val game = gameWithClient(success = true)
        publishCapabilities(game, true)
        report(game, PolarisSessionStatus(state = "idle"))
        assertEquals(0, reportCount(game))
    }

    @Test(timeout = 60_000)
    fun anEndWhileAPollReportIsOnTheWireIsSentOnceAfterItWithItsOwnSnapshot() {
        // W34 D3 through Game: a status-poll report is on the wire when the stream ends. The end's
        // payload is captured at once, waits for that report, and goes exactly once after it returns.
        val onTheWire = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val presentations = Collections.synchronizedList(ArrayList<JSONObject?>())
        val client = Mockito.mock(PolarisApiClient::class.java, Answer<Any?> { call ->
            if (call.method.name == "reportClientSettings") {
                val first = presentations.isEmpty()
                presentations += call.arguments[5] as JSONObject?
                if (first) {
                    onTheWire.countDown()
                    answer.await(20, TimeUnit.SECONDS)
                }
                PolarisSessionStatus.SyncStatus()
            } else {
                Mockito.RETURNS_DEFAULTS.answer(call)
            }
        })
        val game = Robolectric.buildActivity(Game::class.java).get()
        setField(game, "novaApiClient", client)
        setField(game, "prefConfig", PreferenceConfiguration())
        publishCapabilities(game, true)
        messages.clear()

        val poll = thread { report(game, streaming(60.0)) }
        assertTrue(onTheWire.await(20, TimeUnit.SECONDS))
        val end = endReport(game, streaming(120.0))!!
        end.join(300)
        assertTrue("the end waits behind the report on the wire", end.isAlive)
        assertEquals(1, presentations.size)

        answer.countDown()
        poll.join(20_000)
        end.join(20_000)
        assertFalse(end.isAlive)
        assertEquals("the poll's report, then exactly one end", 2, reportCount(game))
        assertEquals(60.0, presentations[0]!!.getDouble("target_refresh_rate_hz"), 0.0)
        assertEquals("the end's own snapshot", 120.0, presentations[1]!!.getDouble("target_refresh_rate_hz"), 0.0)
        assertTrue(messages.any { it.contains("Client presentation synced (end)") })
        // Nothing more after the end, however the status changes.
        report(game, streaming(90.0))
        assertEquals(2, reportCount(game))
    }

    private fun streaming(targetHz: Double) = PolarisSessionStatus(
        state = "streaming", sessionToken = "presentation-test-session",
        presentationPolicy = PolarisSessionStatus.PresentationPolicy(targetRefreshRateHz = targetHz),
    )

    private fun endReport(game: Game, status: PolarisSessionStatus): Thread? =
        Game::class.java.getDeclaredMethod("endClientPresentationReport", PolarisSessionStatus::class.java)
            .apply { isAccessible = true }.invoke(game, status) as Thread?

    private fun gameWithClient(success: Boolean): Game {
        val game = Robolectric.buildActivity(Game::class.java).get()
        val client = Mockito.mock(PolarisApiClient::class.java, Answer<Any?> { call ->
            if (call.method.name == "reportClientSettings") {
                if (success) PolarisSessionStatus.SyncStatus() else null
            } else {
                Mockito.RETURNS_DEFAULTS.answer(call)
            }
        })
        setField(game, "novaApiClient", client)
        setField(game, "prefConfig", PreferenceConfiguration())
        messages.clear()
        return game
    }

    private fun publishCapabilities(game: Game, supported: Boolean) {
        val scope = FeatureFlagManager.beginScope()
        setField(game, "novaFeatureScope", scope)
        assertTrue(FeatureFlagManager.publishForScope(scope, PolarisCapabilities(
            server = "polaris",
            version = "1.0.0",
            features = PolarisCapabilities.Features(clientSettings = supported),
            capture = PolarisCapabilities.CaptureInfo(),
        )))
    }

    private fun report(game: Game, status: PolarisSessionStatus = PolarisSessionStatus(
        state = "streaming", sessionToken = "presentation-test-session",
    )) {
        Game::class.java.getDeclaredMethod(
            "reportClientPresentationIfNeeded", PolarisSessionStatus::class.java,
        ).apply { isAccessible = true }.invoke(game, status)
    }

    private fun reportCount(game: Game): Int {
        val client = Game::class.java.getDeclaredField("novaApiClient")
            .apply { isAccessible = true }.get(game)
        return Mockito.mockingDetails(client).invocations.count { it.method.name == "reportClientSettings" }
    }

    private fun setField(game: Game, name: String, value: Any?) {
        Game::class.java.getDeclaredField(name).apply { isAccessible = true }.set(game, value)
    }
}
