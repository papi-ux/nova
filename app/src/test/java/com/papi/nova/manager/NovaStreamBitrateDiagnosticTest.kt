package com.papi.nova.manager

import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.*
import com.papi.nova.preferences.NovaDistance
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real owner/controller with a recording API. The diagnostic must never refresh or write. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaStreamBitrateDiagnosticTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun stop() { scope.cancel() }
    private var connection: Any? = Any()
    private var inputs = NovaLiveStreamInputs(1920, 1080, 120, NovaDistance.HAND)
    private val methods = mutableListOf<String>()
    private var api: PolarisApiClient? = PolarisApiClient(ApplicationProvider.getApplicationContext(), "127.0.0.1", 47984).also {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            methods += chain.request().method
            error("A read-only snapshot must not make an HTTP request")
        }.build()
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(it, http)
    }
    private val owner = NovaStreamBitrateOwner(scope, { api }, { connection }, { true }, { inputs })
    private val caps = PolarisCapabilities("Polaris", "fixture", PolarisCapabilities.Features(
        bitrateUnitsV1 = true, manualBitrateMaxKbps = 500000), PolarisCapabilities.CaptureInfo())
    private fun status() = PolarisSessionStatus(state = "streaming", streamingActive = true,
        appSessionId = "SECRET-APP-SESSION", appSessionIdPresent = true, sessionGeneration = 7,
        sessionToken = "SECRET-SESSION-TOKEN", ownerDeviceName = "SECRET-DEVICE-NAME",
        game = "SECRET-LIBRARY-GAME", ownedByClient = true, clientRole = "owner",
        controls = PolarisSessionStatus.ControlsStatus(hostTuningAllowed = true),
        encoder = PolarisSessionStatus.EncoderStatus(codec = "hevc"),
        liveTuning = LiveTuningStatus(false, "stable", true, "SECRET-REASON", 300000,
            26000, 26000, "a".repeat(64), "SECRET-HOST-INSTANCE", 4, 7, "SECRET-APP-SESSION"))
    private fun observe(s: PolarisSessionStatus? = status()) = owner.observe(api, connection, s, caps)

    // Reflection keeps this regression runnable on the original source: absence is an assertion
    // failure, rather than a Kotlin compilation failure or a replacement owner implementation.
    @Suppress("UNCHECKED_CAST")
    private fun snapshot(): Map<String, Any?> {
        val method = owner.javaClass.methods.singleOrNull { it.name == "diagnosticSnapshot" && it.parameterCount == 0 }
        assertNotNull("the actual owner must expose a read-only diagnostic snapshot", method)
        return method!!.invoke(owner) as Map<String, Any?>
    }

    @Test fun snapshotsExposeRealScopeWithoutRequestsMutationOrPrivateValues() {
        observe()
        val before = owner.state.value
        val first = snapshot()
        val second = snapshot()
        assertEquals(true, first["apiMatches"])
        assertEquals(true, first["connectionMatches"])
        assertEquals(true, first["attachedAppMatches"])
        assertEquals(true, first["attachedGenerationMatches"])
        assertEquals(7L, first["observedGeneration"])
        assertEquals("VIDEO", first["publishedUnits"])
        assertEquals(26000, first["publishedRequestedKbps"])
        assertEquals(1000, first["publishedMinimumKbps"])
        assertEquals(500000, first["publishedMaximumKbps"])
        assertEquals(true, first["publishedCanChange"])
        assertEquals(first, second)
        assertSame(before, owner.state.value)
        assertTrue(methods.isEmpty())
        val output = JSONObject(first).toString()
        assertFalse(output.contains("SECRET"))
        assertFalse(output.contains("127.0.0.1"))
        assertTrue((first["scopeFingerprint"] as String).matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun sameAppAndGenerationDoNotHideUnobservedInputOrLocalTokenInvalidation() {
        observe()
        val before = snapshot()
        inputs = inputs.copy(fps = 60)
        val unobserved = snapshot()
        assertEquals(false, unobserved["inputsMatchObservation"])
        assertEquals(before["inputRevision"], unobserved["inputRevision"])
        assertEquals(before["observationRevision"], unobserved["observationRevision"])
        observe()
        val after = snapshot()
        assertEquals(before["observedGeneration"], after["observedGeneration"])
        assertEquals(before["scopeFingerprint"], after["scopeFingerprint"])
        assertNotEquals(before["inputRevision"], after["inputRevision"])
        assertEquals(true, after["inputsMatchObservation"])
        assertTrue(methods.isEmpty())
    }

    @Test fun ownerChangeAndCurrentConnectionReplacementRemainVisibleBeforePublication() {
        observe()
        observe(status().copy(clientRole = "viewer", ownedByClient = false))
        assertEquals(false, snapshot()["observedOwned"])
        assertEquals(true, snapshot()["denied"])
        assertEquals(false, snapshot()["publishedCanChange"])
        connection = Any()
        val changed = snapshot()
        assertEquals(false, changed["connectionMatches"])
        assertEquals(true, changed["apiMatches"])
        api = null
        assertEquals(false, snapshot()["currentApiPresent"])
        assertEquals(false, snapshot()["apiMatches"])
        assertTrue(methods.isEmpty())
    }

    @Test fun missingStatusAndInvalidScopeStayUnknownWithoutCachedFingerprintOrNumericRate() {
        observe()
        observe(null)
        val unknown = snapshot()
        assertNull(unknown["scopeFingerprint"])
        assertNull(unknown["observedGeneration"])
        assertNull(unknown["observedOwned"])
        assertNull(unknown["publishedRequestedKbps"])
        assertEquals("UNKNOWN", unknown["publishedUnits"])
        val json = JSONObject(unknown)
        assertTrue(json.has("scopeFingerprint"))
        assertTrue(json.isNull("scopeFingerprint"))
        observe(status().copy(appSessionId = "", sessionGeneration = 0))
        assertEquals(false, snapshot()["observedGenerationPositive"])
        assertNull(snapshot()["scopeFingerprint"])
        assertEquals(false, snapshot()["attachedAppMatches"])
        assertTrue(methods.isEmpty())
    }

    @Test fun sameGenerationDifferentAppAndUnavailableUnitsCannotMasqueradeAsAttachedScope() {
        observe()
        val before = snapshot()
        // A keyed denial for a different app cannot replace the real attachment identity.
        observe(status().copy(appSessionId = "OTHER-APP", ownedByClient = false, clientRole = "viewer"))
        assertEquals(false, snapshot()["attachedAppMatches"])
        assertEquals(true, snapshot()["attachedGenerationMatches"])
        assertNotEquals(before["scopeFingerprint"], snapshot()["scopeFingerprint"])
        owner.retire()
        observe(status().copy(encoder = PolarisSessionStatus.EncoderStatus(codec = "pyrowave")))
        assertEquals("UNKNOWN", snapshot()["publishedUnits"])
        assertEquals(false, snapshot()["publishedCanChange"])
        assertTrue(methods.isEmpty())
    }
}
