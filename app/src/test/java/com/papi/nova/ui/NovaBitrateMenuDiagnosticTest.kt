package com.papi.nova.ui

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.*
import com.papi.nova.manager.*
import com.papi.nova.preferences.NovaDistance
import com.papi.nova.ui.panel.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Real panel lifetime and real owner tokens: no alternative ownership/admission model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaBitrateMenuDiagnosticTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun stop() { scope.cancel(); ShadowLog.setLoggable(NovaBitrateMenuDiagnostic.TAG, Log.INFO) }
    private val api = PolarisApiClient(ApplicationProvider.getApplicationContext(), "127.0.0.1", 47984)
    private val connection = Any()
    private var inputs = NovaLiveStreamInputs(1920, 1080, 120, NovaDistance.HAND)
    private val owner = NovaStreamBitrateOwner(scope, { api }, { connection }, { true }, { inputs })
    private val caps = PolarisCapabilities("Polaris", "fixture", PolarisCapabilities.Features(
        bitrateUnitsV1 = true, manualBitrateMaxKbps = 500000), PolarisCapabilities.CaptureInfo())
    private fun status() = PolarisSessionStatus(state = "streaming", streamingActive = true,
        appSessionId = "PRIVATE-APP", appSessionIdPresent = true, sessionGeneration = 7,
        ownedByClient = true, clientRole = "owner", encoder = PolarisSessionStatus.EncoderStatus(codec = "hevc"),
        liveTuning = LiveTuningStatus(false, "stable", true, "PRIVATE-REASON", 300000,
            26000, 26000, "a".repeat(64), "PRIVATE-HOST", 4, 7, "PRIVATE-APP"))
    private val panel = NovaPanelState().apply { open(CommandCenterPage.Root("fixture")) }
    private var validation = true
    private var enabled = true
    private val events = mutableListOf<JSONObject>()
    private fun diagnostic() = NovaBitrateMenuDiagnostic(owner, panel, 12, { validation },
        { panel.contains(CommandCenterPage.RootKey) }, { true }, { true }, { enabled },
        { events += JSONObject(it) })
    private fun form() = NovaCommonPage.Form("stream-bitrate-exact", "fixture", emptyList(), "fixture", onSubmit = { null })
    private fun observe() = owner.observe(api, connection, status(), caps)

    @Test fun realFormPopAndReplacementWithSameKeyNeverBorrowTheOldFormsLifetime() {
        observe()
        val diagnostic = diagnostic()
        diagnostic.pictureRendered(owner.state.value)
        val first = form()
        panel.push(first); diagnostic.formOpened(first, owner.state.value)
        assertTrue(events.last().getBoolean("formOnStack"))
        assertTrue(events.last().getBoolean("formOnTop"))
        val captured = events.last().getJSONObject("form").toString()
        panel.replaceTop(form())
        diagnostic.record(NovaBitrateMenuDiagnostic.Event.FORM_LIFETIME)
        assertFalse(events.last().getBoolean("formOnStack"))
        assertFalse(events.last().getBoolean("formOnTop"))
        assertEquals(captured, events.last().getJSONObject("form").toString())
        assertTrue(panel.pop())
        panel.close(); validation = false
        diagnostic.record(NovaBitrateMenuDiagnostic.Event.MENU_CLOSED)
        assertFalse(events.last().getBoolean("menuShowing"))
        assertFalse(events.last().getBoolean("menuValidationCurrent"))
        panel.open(CommandCenterPage.Root("reopened"))
        val reopened = diagnostic()
        reopened.pictureRendered(owner.state.value)
        assertNotEquals(events.first().getLong("menuId"), events.last().getLong("menuId"))
        assertTrue(events.last().isNull("form"))
    }

    @Test fun formLifetimeAndMenuGenerationStayCoherentThroughSharedStreamDetails() {
        observe()
        val diagnostic = diagnostic()
        // The exact form opens from the shared Stream details page, one page above the root.
        val details = CommandCenterPage.StreamDetails(true)
        panel.push(details)
        diagnostic.pictureRendered(owner.state.value)
        val first = form()
        panel.push(first); diagnostic.formOpened(first, owner.state.value)
        assertTrue(events.last().getBoolean("formOnStack"))
        assertTrue(events.last().getBoolean("formOnTop"))
        val captured = events.last().getJSONObject("form").toString()
        // Back from the form lands on Stream details, still inside this opening of the menu.
        assertTrue(panel.pop())
        assertEquals(details, panel.top)
        diagnostic.record(NovaBitrateMenuDiagnostic.Event.FORM_LIFETIME)
        assertFalse(events.last().getBoolean("formOnStack"))
        assertFalse(events.last().getBoolean("formOnTop"))
        assertTrue(events.last().getBoolean("menuShowing"))
        assertEquals(captured, events.last().getJSONObject("form").toString())
        // Another exact form with the same key, from the same page, does not revive the closed one.
        val second = form()
        panel.push(second)
        diagnostic.record(NovaBitrateMenuDiagnostic.Event.FORM_LIFETIME)
        assertFalse(events.last().getBoolean("formOnStack"))
        diagnostic.formOpened(second, owner.state.value)
        assertTrue(events.last().getBoolean("formOnStack"))
        assertEquals(2L, events.last().getLong("formRevision"))
        // Leaving Stream details for the root keeps the same menu and generation; the form is gone.
        assertTrue(panel.pop()); assertTrue(panel.pop())
        diagnostic.record(NovaBitrateMenuDiagnostic.Event.FORM_LIFETIME)
        assertTrue(events.last().getBoolean("menuShowing"))
        assertFalse(events.last().getBoolean("formOnStack"))
        assertFalse(events.last().getBoolean("formOnTop"))
        assertEquals(setOf(12L), events.map { it.getLong("menuGeneration") }.toSet())
        assertEquals(1, events.map { it.getLong("menuId") }.toSet().size)
        assertEquals((1..events.size).map { it.toLong() }, events.map { it.getLong("emissionRevision") })
    }

    @Test fun sameAppGenerationFingerprintDoesNotHideStaleFormAndRenderedToken() {
        observe()
        val diagnostic = diagnostic()
        val rendered = owner.state.value
        diagnostic.pictureRendered(rendered)
        val form = form(); panel.push(form); diagnostic.formOpened(form, rendered)
        val original = events.last().getJSONObject("form")
        inputs = inputs.copy(fps = 60)
        diagnostic.record(NovaBitrateMenuDiagnostic.Event.OWNER)
        assertFalse(events.last().getJSONObject("owner").getBoolean("inputsMatchObservation"))
        observe()
        diagnostic.record(NovaBitrateMenuDiagnostic.Event.OWNER)
        assertTrue(events.last().getBoolean("formScopeMatchesCurrent"))
        assertFalse(events.last().getBoolean("formTokenMatchesCurrent"))
        assertFalse(events.last().getBoolean("pictureTokenMatchesCurrent"))
        assertEquals(original.toString(), events.last().getJSONObject("form").toString())
        // An old callback observed for the first time after the invalidation cannot acquire
        // the new scope fingerprint merely because its app and generation happen to match.
        diagnostic.pictureRendered(rendered)
        assertTrue(events.last().getJSONObject("picture").isNull("scopeFingerprint"))
        assertTrue(events.last().isNull("pictureScopeMatchesCurrent"))
    }

    @Test fun missingStatusIsExplicitAndNeverReusesCapturedCurrentScopeOrRate() {
        observe()
        val diagnostic = diagnostic()
        diagnostic.pictureRendered(owner.state.value)
        owner.observe(api, connection, null)
        diagnostic.record(NovaBitrateMenuDiagnostic.Event.STATUS)
        val current = events.last()
        assertFalse(current.getJSONObject("owner").getBoolean("statusAvailable"))
        assertTrue(current.getJSONObject("owner").isNull("scopeFingerprint"))
        assertTrue(current.getJSONObject("owner").isNull("publishedRequestedKbps"))
        assertTrue(current.isNull("pictureScopeMatchesCurrent"))
        assertFalse(current.toString().contains("PRIVATE"))
        assertFalse(current.toString().contains("127.0.0.1"))
        assertTrue(current.toString().length < 3900)
    }

    @Test fun defaultGateNeedsExplicitDebugTagAndDisabledObserverReadsNothing() {
        ShadowLog.setLoggable(NovaBitrateMenuDiagnostic.TAG, Log.INFO)
        assertFalse(NovaBitrateMenuDiagnostic.enabled())
        ShadowLog.setLoggable(NovaBitrateMenuDiagnostic.TAG, Log.DEBUG)
        assertTrue(NovaBitrateMenuDiagnostic.enabled())
        var supplierReads = 0
        val unreadOwner = NovaStreamBitrateOwner(scope, { supplierReads++; api }, { connection }, { true }, { inputs })
        val diagnostic = NovaBitrateMenuDiagnostic(unreadOwner, panel, 1, { true }, { true }, { true }, { true },
            { false }, { error("disabled diagnostics must not emit") })
        diagnostic.pictureRendered(NovaLiveBitratePresentation())
        diagnostic.formOpened(form(), NovaLiveBitratePresentation())
        diagnostic.record(NovaBitrateMenuDiagnostic.Event.STATUS)
        assertEquals(0, supplierReads)
        enabled = false
        diagnostic().record(NovaBitrateMenuDiagnostic.Event.STATUS)
        assertTrue(events.isEmpty())
    }
}
