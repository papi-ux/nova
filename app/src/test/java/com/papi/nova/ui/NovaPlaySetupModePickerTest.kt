package com.papi.nova.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaPlaySetupModePickerTest {

    private fun mode(
        id: String,
        group: String = "",
        available: Boolean = true,
        desired: Boolean = false,
        effective: Boolean = false,
        label: String = id,
        reason: String = "",
        unavailableReason: String = "",
    ) = NovaPolarisModeUiState(
        mode = id,
        label = label,
        selected = desired,
        selectedDesired = desired,
        selectedEffective = effective,
        enabled = available,
        available = available,
        reason = reason,
        restartRequired = true,
        statusLabel = "",
        group = group,
        unavailableReason = unavailableReason,
    )

    @Test
    fun pickerOpensOnlyBeyondTheClassicPair() {
        assertFalse(novaModePickerEligible(0))
        assertFalse(novaModePickerEligible(2))
        assertTrue(novaModePickerEligible(3))
        assertTrue(novaModePickerEligible(6))
    }

    @Test
    fun bandsOrderPrivateThenHostThenAnythingAFutureHostInvents() {
        val choices = buildHostModePickerState(
            modes = listOf(
                mode("headless_dongle", group = "host"),
                mode("containerized", group = "sandbox"),
                mode("headless_stream", group = "private"),
                mode("desktop_display", group = "host"),
                mode("windowed_stream", group = "private"),
            ),
            title = "Default Display",
        ).choices

        val bands = novaModePickerBands(choices)
        assertEquals(listOf("private", "host", "sandbox"), bands.map { it.group })
        assertEquals(listOf("headless_stream", "windowed_stream"), bands[0].choices.map { it.id })
        assertEquals(listOf("headless_dongle", "desktop_display"), bands[1].choices.map { it.id })
    }

    @Test
    fun legacyCatalogWithoutGroupsIsOneUnlabeledBand() {
        val bands = novaModePickerBands(
            buildHostModePickerState(
                modes = listOf(mode("headless_stream"), mode("host_virtual_display")),
                title = "Default Display",
            ).choices
        )
        assertEquals(1, bands.size)
        assertEquals("", bands.single().group)
    }

    @Test
    fun hostChoicesCarryDesiredEffectiveAndTheHostsUnavailableReason() {
        val state = buildHostModePickerState(
            modes = listOf(
                mode("headless_stream", group = "private", desired = true, reason = "Recommended for handhelds"),
                mode("desktop_display", group = "host", effective = true, reason = "Streams the visible desktop"),
                mode(
                    "host_virtual_display",
                    group = "host",
                    available = false,
                    reason = "Creates a host-visible output",
                    unavailableReason = "EVDI module is loaded but no device can be created",
                ),
            ),
            title = "Default Display",
        )

        assertEquals("Default Display", state.title)
        assertEquals(null, state.hostDefaultLabel)
        val byId = state.choices.associateBy { it.id }
        assertTrue(byId.getValue("headless_stream").current)
        assertFalse(byId.getValue("headless_stream").active)
        // Effective-but-not-desired is the fallback edge, never the selection tint.
        assertTrue(byId.getValue("desktop_display").active)
        assertFalse(byId.getValue("desktop_display").current)
        // The rejection a client would get is the story the card tells.
        assertEquals(
            "EVDI module is loaded but no device can be created",
            byId.getValue("host_virtual_display").detail,
        )
        assertFalse(byId.getValue("host_virtual_display").enabled)
        assertEquals("Recommended for handhelds", byId.getValue("headless_stream").detail)
    }

    @Test
    fun gameChoicesAreTheCatalogCutToTheContractIncludingLegacySpellings() {
        val state = buildGameModePickerState(
            modes = listOf(
                mode("headless_stream", group = "private"),
                mode("windowed_stream", group = "private"),
                mode("host_virtual_display", group = "host"),
                mode("headless_dongle", group = "host"),
            ),
            // The contract speaks the old vocabulary; the cut still has to land.
            allowedModes = listOf("headless", "virtual_display", "windowed_stream"),
            playMode = "headless_stream",
            hasExplicitOverride = false,
            title = "Where It Runs",
            hostDefaultLabel = "Follow the host — currently Private Stream",
        )

        assertEquals(
            listOf("headless_stream", "windowed_stream", "host_virtual_display"),
            state.choices.map { it.id },
        )
        assertEquals("Follow the host — currently Private Stream", state.hostDefaultLabel)
    }

    @Test
    fun withoutAnOverrideTheHostDefaultEntryIsCurrentAndThePlayModeOnlyActive() {
        val state = buildGameModePickerState(
            modes = listOf(mode("headless_stream", group = "private"), mode("windowed_stream", group = "private"), mode("desktop_display", group = "host")),
            allowedModes = emptyList(),
            playMode = "windowed_stream",
            hasExplicitOverride = false,
            title = "Where It Runs",
            hostDefaultLabel = "Follow the host",
        )

        assertTrue(state.hostDefaultCurrent)
        val byId = state.choices.associateBy { it.id }
        assertFalse(byId.getValue("windowed_stream").current)
        assertTrue(byId.getValue("windowed_stream").active)
    }

    @Test
    fun anExplicitOverrideOwnsTheSelectionTint() {
        val state = buildGameModePickerState(
            modes = listOf(mode("headless_stream", group = "private"), mode("host_virtual_display", group = "host")),
            allowedModes = emptyList(),
            playMode = "host_virtual_display",
            hasExplicitOverride = true,
            title = "Where It Runs",
            hostDefaultLabel = "Follow the host",
        )

        assertFalse(state.hostDefaultCurrent)
        val byId = state.choices.associateBy { it.id }
        assertTrue(byId.getValue("host_virtual_display").current)
        assertFalse(byId.getValue("host_virtual_display").active)
    }

    @Test
    fun theAdvisoryAiPickLandsOnItsAvailableCardOnly() {
        val state = buildGameModePickerState(
            modes = listOf(
                mode("headless_stream", group = "private"),
                mode("gamescope_stream", group = "private"),
                mode("headless_dongle", group = "private", available = false, unavailableReason = "No dongle."),
            ),
            allowedModes = emptyList(),
            playMode = "headless_stream",
            hasExplicitOverride = false,
            title = "Where It Runs",
            hostDefaultLabel = "Follow the host",
            aiRecommendedMode = "gamescope_stream",
        )

        assertEquals(listOf(false, true, false), state.choices.map { it.aiRecommended })

        val unavailablePick = buildGameModePickerState(
            modes = listOf(
                mode("headless_stream", group = "private"),
                mode("headless_dongle", group = "private", available = false, unavailableReason = "No dongle."),
            ),
            allowedModes = emptyList(),
            playMode = "headless_stream",
            hasExplicitOverride = false,
            title = "Where It Runs",
            hostDefaultLabel = "Follow the host",
            aiRecommendedMode = "headless_dongle",
        )

        assertTrue(unavailablePick.choices.none { it.aiRecommended })
    }
}
