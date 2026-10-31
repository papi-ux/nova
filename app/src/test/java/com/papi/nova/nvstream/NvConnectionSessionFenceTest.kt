package com.papi.nova.nvstream

import androidx.test.core.app.ApplicationProvider
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.LimelightCryptoProvider
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.http.PairingManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises startApp and its real resume/replace decision, without a network or native stream. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [com.papi.nova.shadows.ShadowMoonBridge::class])
class NvConnectionSessionFenceTest {
    @Test fun theSameGamesReplacementSessionIsRefusedAtTheFinalConnectionDecision() = refused(token = "session-B")
    @Test fun anotherGameCannotBeQuitAndLaunchedFromTheObservedResume() = refused(game = 8)
    @Test fun unknownSessionIdentityIsRefused() = refused(token = null)
    @Test fun unknownOwnershipIsRefused() = refused(owned = null)
    @Test fun changedOwnershipDoesNotTurnResumeIntoWatch() = refused(owned = false)
    @Test fun autoSafeCannotReplaceTheObservedSession() = refused(forceFresh = true)
    @Test fun unchangedOwnedResumeUsesTheCapturedToken() {
        val result = start()
        assertTrue(result.started)
        assertEquals(listOf("resume:session-A:false"), result.requests)
    }
    @Test fun aCapturedOwnerResumeCannotBecomeWatch() {
        val result = start(owned = false, watch = true, expectedOwned = false)
        assertFalse(result.started)
        assertTrue(result.requests.isEmpty())
    }
    @Test fun unrelatedLegacyWatchKeepsItsExistingOwnershipRules() {
        val result = start(owned = false, watch = true, fenced = false)
        assertTrue(result.started)
        assertEquals(listOf("resume:session-A:true"), result.requests)
    }
    @Test fun replacementAfterServerInfoStillReceivesOnlyTheCapturedResumeToken() {
        val result = start(replaceBeforeRequest = true)
        assertFalse("the host rejects the stale token instead of resuming B", result.started)
        assertEquals(listOf("resume:session-A:false"), result.requests)
    }
    @Test fun unrelatedLegacyEntryPointsKeepTheirExistingBehavior() {
        val result = start(token = "session-B", fenced = false)
        assertTrue(result.started)
        assertEquals(listOf("resume:session-B:false"), result.requests)
    }

    private data class Result(val started: Boolean, val requests: List<String>)
    private fun refused(game: Int = 7, token: String? = "session-A", owned: Boolean? = true, forceFresh: Boolean = false) {
        val result = start(game, token, owned, forceFresh = forceFresh)
        assertFalse("the observed Resume must fail closed", result.started)
        assertEquals("neither resume, launch nor cancel may target a replacement", emptyList<String>(), result.requests)
    }
    private fun start(game: Int = 7, token: String? = "session-A", owned: Boolean? = true,
        watch: Boolean = false, expectedOwned: Boolean = true, fenced: Boolean = true, forceFresh: Boolean = false, replaceBeforeRequest: Boolean = false): Result {
        val builder = StreamConfiguration.Builder().setApp(NvApp("Hades", "game-A", 7, false))
            .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_LOCAL).setResumeExistingOnly(true).setForceFreshLaunch(forceFresh)
        if (fenced) builder.setExpectedSession("session-A", expectedOwned)
        val connection = NvConnection(ApplicationProvider.getApplicationContext(), ComputerDetails.AddressTuple("127.0.0.1", 47989),
            47984, "synthetic-client", builder.build(), mock(LimelightCryptoProvider::class.java), null)
        val context = NvConnection::class.java.getDeclaredField("context").run { isAccessible = true; get(connection) as ConnectionContext }
        context.connListener = mock(NvConnectionListener::class.java)
        context.watchOnlyRequested = watch
        val requests = ArrayList<String>()
        val settings = withSettings().defaultAnswer { call ->
            when (call.method.name) {
                "getServerInfo" -> "<root status_code=\"200\"/>"
                "getServerVersion" -> "7.1.0.0"
                "getComputerDetails" -> ComputerDetails()
                "getPairState" -> PairingManager.PairState.PAIRED
                "getServerCodecModeSupport" -> 1L
                "getCurrentGameSessionToken" -> token
                "getCurrentGameOwned" -> owned
                "getCurrentGame" -> game
                "getCurrentGameUUID" -> if (game == 7) "game-A" else "game-B"
                "launchApp" -> {
                    val ctx = call.getArgument<ConnectionContext>(0)
                    requests += "${call.getArgument<String>(1)}:${ctx.sessionToken}:${call.getArgument<Boolean>(5)}"
                    // Existing /resume rejects a differing nonempty token within launch admission.
                    !replaceBeforeRequest || ctx.sessionToken == "session-B"
                }
                "quitApp" -> { requests += "cancel"; false }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        }
        mockConstruction(NvHTTP::class.java, settings) { _, _ -> }.use {
            val started = NvConnection::class.java.getDeclaredMethod("startApp").run { isAccessible = true; invoke(connection) as Boolean }
            return Result(started, requests)
        }
    }
}
