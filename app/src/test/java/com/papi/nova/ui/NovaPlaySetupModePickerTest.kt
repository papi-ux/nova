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
        sessionOverridable: Boolean = true,
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
        sessionOverridable = sessionOverridable,
    )

    @Test
    fun pickerOpensOnlyBeyondTheClassicPair() {
        assertFalse(novaModePickerEligible(0))
        assertFalse(novaModePickerEligible(2))
        assertTrue(novaModePickerEligible(3))
        assertTrue(novaModePickerEligible(6))
        assertTrue(
            "two registry choices need the full picker because neither is in the inline pair",
            novaModePickerEligible(choiceCount = 2, inlineChoiceCount = 0),
        )
        assertTrue(
            "one classic and one registry choice cannot be cycled by the classic row",
            novaModePickerEligible(choiceCount = 2, inlineChoiceCount = 1),
        )
        assertFalse(novaModePickerEligible(choiceCount = 1, inlineChoiceCount = 0))
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

    @Test
    fun perSessionPickerDisablesAModeTheHostWillNotTakeForOneSession() {
        val modes = listOf(
            mode("headless_stream", group = "private"),
            mode("headless_dongle", group = "host", sessionOverridable = false),
        )

        val game = buildGameModePickerState(
            modes = modes,
            allowedModes = emptyList(),
            playMode = "headless_stream",
            hasExplicitOverride = false,
            title = "Where It Runs",
            hostDefaultLabel = "Host default",
            hostDefaultOnlyDetail = "Set by the host.",
        )

        val dongle = game.choices.single { it.id == "headless_dongle" }
        // Available, but not selectable for one launch: offering it would let the
        // user pick something the host drops on the way through.
        assertFalse(dongle.enabled)
        assertTrue(dongle.hostDefaultOnly)
        assertEquals("Set by the host.", dongle.detail)

        assertTrue(game.choices.single { it.id == "headless_stream" }.enabled)
    }

    @Test
    fun hostDefaultPickerStillOffersIt() {
        // Choosing the host's Default Display is exactly where this mode is valid,
        // so the restriction must not leak into that picker.
        val host = buildHostModePickerState(
            modes = listOf(mode("headless_dongle", group = "host", sessionOverridable = false)),
            title = "Default Display",
        )

        assertTrue(host.choices.single { it.id == "headless_dongle" }.enabled)
    }

    @Test
    fun legacyHostsKeepOrdinaryModesSelectableButDongleFailsClosed() {
        // A legacy host lacks session_overridable. Ordinary modes keep compatibility,
        // but a physical dongle swap must never become a per-game action by omission.
        val game = buildGameModePickerState(
            modes = listOf(
                mode("headless_stream"),
                mode("desktop_display"),
                mode("headless_dongle"),
            ),
            allowedModes = emptyList(),
            playMode = "headless_stream",
            hasExplicitOverride = false,
            title = "Where It Runs",
            hostDefaultLabel = "Host default",
            hostDefaultOnlyDetail = "Set by the host.",
        )

        assertTrue(game.choices.single { it.id == "headless_stream" }.enabled)
        assertTrue(game.choices.single { it.id == "desktop_display" }.enabled)
        val dongle = game.choices.single { it.id == "headless_dongle" }
        assertFalse(dongle.enabled)
        assertTrue(dongle.hostDefaultOnly)
    }

    @Test
    fun knownModesUsePlayerFacingCopyBeforeHostBackendJargon() {
        val state = buildGameModePickerState(
            modes = listOf(
                mode("headless_stream", reason = "Runtime: labwc; capture: wlroots"),
                mode("desktop_display", reason = "Runtime: none; capture: portal"),
            ),
            allowedModes = emptyList(),
            playMode = "headless_stream",
            hasExplicitOverride = false,
            title = "Where It Runs",
            hostDefaultLabel = "Host default",
            plainModeDetails = mapOf(
                "headless_stream" to "Private display; host monitors stay untouched.",
                "desktop_display" to "Streams everything visible on the host.",
            ),
        )

        assertEquals(
            "Private display; host monitors stay untouched.",
            state.choices.single { it.id == "headless_stream" }.detail,
        )
        assertEquals(
            "Streams everything visible on the host.",
            state.choices.single { it.id == "desktop_display" }.detail,
        )
    }

    @Test
    fun desktopTakeoverIsAnExplicitSelectableHostMode() {
        val state = buildGameModePickerState(
            modes = listOf(
                mode("headless_stream", group = "private"),
                mode(
                    "desktop_takeover",
                    group = "host",
                    reason = "Runtime: Hyprland output migration",
                    label = "Desktop Takeover",
                ),
            ),
            allowedModes = listOf("desktop_takeover"),
            playMode = "desktop_takeover",
            hasExplicitOverride = true,
            title = "Where It Runs",
            hostDefaultLabel = "Follow the host",
            plainModeDetails = mapOf(
                "desktop_takeover" to "Temporarily moves the desktop to a client-sized display.",
            ),
        )

        val takeover = state.choices.single()
        assertEquals("desktop_takeover", takeover.id)
        assertEquals("host", takeover.group)
        assertEquals("Desktop Takeover", takeover.label)
        assertEquals("Temporarily moves the desktop to a client-sized display.", takeover.detail)
        assertTrue(takeover.enabled)
        assertTrue(takeover.current)
        assertFalse(takeover.active)
    }
}
