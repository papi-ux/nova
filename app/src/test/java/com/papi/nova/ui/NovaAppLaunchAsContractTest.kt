package com.papi.nova.ui

import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisGameJsonAdapter
import com.papi.nova.shared.polaris.model.PolarisGame
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaAppLaunchAsContractTest {
    private val modes = listOf("headless_stream", "windowed_stream", "gamescope_stream", "host_virtual_display", "desktop_takeover", "desktop_display")
    private val settings = PolarisClientSettings(
        desired = PolarisClientSettings.Desired(streamDisplayMode = "desktop_display"),
        capabilities = PolarisClientSettings.Capabilities(modes = modes.map {
            PolarisClientSettings.ModeOption(value = it, available = true, sessionOverridable = true)
        }),
    )
    private fun game(pin: Any, available: Any? = true, allowed: List<String>? = null): PolarisGame {
        val contract = JSONObject().put("launch_as", pin)
            .put("preferred_mode", pin).put("recommended_mode", pin)
            .put("follows_host_default", false)
            .put("launch_as_unavailable_reason", "The app's compositor is unavailable.")
            .put("allowed_modes", org.json.JSONArray(allowed ?: listOf(pin.toString())))
        if (available != null) contract.put("launch_as_available", available)
        return PolarisGameJsonAdapter.fromJson(JSONObject().put("id", "app").put("name", "Pinned game").put("launch_mode", contract))
    }
    private fun state(game: PolarisGame, catalog: PolarisClientSettings? = settings, override: String? = null) =
        NovaGameDetailUiState.from(game, false, catalog, "auto", override)

    @Test fun availableFixedPinWinsOverHostAndSavedOverride() {
        for (pin in modes.filter { it != "desktop_display" }) {
            val result = state(game(pin), override = "desktop_display")
            assertTrue(pin, result.playEnabled)
            assertEquals(pin, result.playMode)
            assertFalse(pin, result.hasExplicitOverride)
            assertFalse(pin, result.showLaunchOptionsButton)
        }
    }
    @Test fun perAppDenialWinsOverGloballyAvailableModeAndSavedOverride() {
        for (pin in modes) {
            val result = state(game(pin, false), override = pin)
            assertFalse(pin, result.playEnabled)
            assertEquals(pin, "", result.playMode)
            assertFalse(pin, result.hasExplicitOverride)
            assertEquals(pin, 0, result.actionableLaunchModeCount)
        }
    }
    @Test fun unknownOrMalformedPinCannotUseLegacyNoCatalogFallback() {
        for (pin in listOf("turbo", "Headless_Stream", " headless_stream", "headless_dongle", 3, true, JSONObject.NULL)) {
            assertFalse(pin.toString(), state(game(pin), catalog = null).playEnabled)
        }
    }
    @Test fun newContractRequiresTypedAvailability() {
        for (available in listOf(null, "true", 1, JSONObject.NULL)) {
            assertFalse(available.toString(), state(game("windowed_stream", available)).playEnabled)
        }
    }
    @Test fun fixedPinCannotFallBackThroughAnEmptyLegacyAllowedList() {
        val result = state(game("windowed_stream", allowed = emptyList()), catalog = null)
        assertEquals("windowed_stream", result.playMode)
        assertFalse(result.showLaunchOptionsButton)
    }
    @Test fun mirrorDesktopKeepsItsHostAllowedYieldingModes() {
        val allowed = listOf("desktop_display", "host_virtual_display", "desktop_takeover")
        for (override in allowed) {
            val result = state(game("desktop_display", allowed = allowed), override = override)
            assertTrue(result.playEnabled)
            assertEquals(override, result.playMode)
            assertTrue(result.hasExplicitOverride)
        }
        assertEquals("desktop_display", state(game("desktop_display", allowed = allowed)).playMode)
    }
    @Test fun absentNewFieldsRetainOlderHostFallbackAndOverrides() {
        val legacy = PolarisGameJsonAdapter.fromJson(JSONObject("""{"id":"legacy","name":"Legacy","launch_mode":{"preferred_mode":"headless_stream","recommended_mode":"headless_stream","allowed_modes":[]}}"""))
        assertEquals("headless_stream", state(legacy).playMode)
        assertEquals("headless_stream", state(legacy, catalog = null).playMode)
        assertEquals("host_virtual_display", state(legacy, override = "host_virtual_display").playMode)
    }
}
