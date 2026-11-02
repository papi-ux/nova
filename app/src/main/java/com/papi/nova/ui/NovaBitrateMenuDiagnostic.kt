package com.papi.nova.ui

import android.os.SystemClock
import android.util.Log
import com.papi.nova.BuildConfig
import com.papi.nova.manager.NovaLiveBitratePresentation
import com.papi.nova.manager.NovaLiveBitrateToken
import com.papi.nova.manager.NovaStreamBitrateOwner
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPanelState
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Observes an actual menu opening; none of these values participate in action admission. */
internal class NovaBitrateMenuDiagnostic(
    private val owner: NovaStreamBitrateOwner,
    private val panel: NovaPanelState,
    private val menuGeneration: Long,
    private val menuValidationCurrent: () -> Boolean,
    private val menuShowing: () -> Boolean,
    private val menuApiMatches: () -> Boolean,
    private val menuConnectionMatches: () -> Boolean,
    private val enabled: () -> Boolean = Companion::enabled,
    private val emit: (String) -> Unit = { Log.d(TAG, it) },
) {
    enum class Event { PICTURE, FORM_OPEN, FORM_LIFETIME, OWNER, STATUS, MENU_CLOSED }
    private data class Capture(val token: NovaLiveBitrateToken?, val fields: Map<String, Any?>)
    private val menuId = nextMenu.incrementAndGet()
    private var emissionRevision = 0L
    private var picture: Capture? = null
    private var form: Capture? = null
    private var formPage: NovaPage? = null
    private var formRevision = 0L

    fun pictureRendered(value: NovaLiveBitratePresentation) {
        if (!enabled()) return
        val current = owner.diagnosticSnapshot()
        picture = capture(value, current)
        record(Event.PICTURE, current)
    }

    fun formOpened(page: NovaPage, value: NovaLiveBitratePresentation) {
        if (!enabled()) return
        val current = owner.diagnosticSnapshot()
        formPage = page
        formRevision++
        form = capture(value, current)
        record(Event.FORM_OPEN, current)
    }

    fun record(event: Event) {
        if (enabled()) record(event, owner.diagnosticSnapshot())
    }

    private fun capture(value: NovaLiveBitratePresentation, current: Map<String, Any?>): Capture {
        val aligned = tokenMatches(value.token, current) == true && current["inputsMatchObservation"] == true &&
            current["apiMatches"] == true && current["connectionMatches"] == true
        return Capture(value.token, linkedMapOf(
            "token" to value.token?.let { mapOf("stream" to it.stream, "inputs" to it.inputs) },
            "scopeFingerprint" to current["scopeFingerprint"].takeIf { aligned },
            "generation" to current["observedGeneration"].takeIf { aligned },
            "ownerObservationRevision" to current["observationRevision"],
            "ownerObservationElapsedMs" to current["observationElapsedMs"],
            "canChange" to value.rate.canChange,
            "busy" to value.rate.busy,
            "units" to value.rate.units.name,
            "requestedKnown" to (value.rate.requestedKbps != null),
            "requestedKbps" to value.rate.requestedKbps,
            "minimumKbps" to value.rate.minimumKbps,
            "maximumKbps" to value.rate.maximumKbps,
        ))
    }

    private fun tokenMatches(token: NovaLiveBitrateToken?, current: Map<String, Any?>): Boolean? =
        token?.let { it.stream == current["streamRevision"] && it.inputs == current["inputRevision"] }

    private fun scopeMatches(capture: Capture?, current: Map<String, Any?>): Boolean? {
        val captured = capture?.fields?.get("scopeFingerprint") ?: return null
        return current["scopeFingerprint"]?.let { captured == it }
    }

    private fun record(event: Event, current: Map<String, Any?>) {
        emit(JSONObject(linkedMapOf(
            "schema" to 1,
            "event" to event.name,
            "menuId" to menuId,
            "menuGeneration" to menuGeneration,
            "emissionRevision" to ++emissionRevision,
            "emissionElapsedMs" to SystemClock.elapsedRealtime(),
            "menuValidationCurrent" to menuValidationCurrent(),
            "menuShowing" to menuShowing(),
            "menuApiMatches" to menuApiMatches(),
            "menuConnectionMatches" to menuConnectionMatches(),
            "owner" to current,
            "picture" to picture?.fields,
            "pictureTokenMatchesCurrent" to tokenMatches(picture?.token, current),
            "pictureScopeMatchesCurrent" to scopeMatches(picture, current),
            "formRevision" to formRevision.takeIf { form != null },
            "formOnStack" to formPage?.let { panel.contains(it) },
            "formOnTop" to formPage?.let { panel.top === it },
            "form" to form?.fields,
            "formTokenMatchesCurrent" to tokenMatches(form?.token, current),
            "formScopeMatchesCurrent" to scopeMatches(form, current),
        )).toString())
    }

    companion object {
        const val TAG = "NovaBitrateScope"
        private val nextMenu = AtomicLong()
        fun enabled(): Boolean = BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG)
    }
}
