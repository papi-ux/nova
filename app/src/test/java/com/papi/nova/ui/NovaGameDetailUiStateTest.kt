package com.papi.nova.ui

import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shared.polaris.model.PolarisGame
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaGameDetailUiStateTest {
    @Test
    fun launchPreflightGateWaitsRetriesAndOnlyThenAllowsLaunch() {
        assertEquals(
            NovaLaunchPreflightGate.WAIT,
            NovaGameDetailOptimizationState(preflightInFlight = true).launchPreflightGate(),
        )
        assertEquals(
            NovaLaunchPreflightGate.RETRY,
            NovaGameDetailOptimizationState(preflightFailed = true).launchPreflightGate(),
        )
        assertEquals(
            NovaLaunchPreflightGate.READY,
            NovaGameDetailOptimizationState().launchPreflightGate(),
        )
        assertEquals(
            NovaLaunchPreflightGate.READY,
            NovaGameDetailOptimizationState(
                rawOptimization = JSONObject(),
                preflightInFlight = true,
            ).launchPreflightGate(),
        )
    }

    @Test
    fun agreeingWithTheHostIsNotAnOverride() {
        val state = NovaGameDetailUiState.from(
            game = game(
                launchMode = PolarisGame.LaunchModeContract(
                    preferredMode = "headless",
                    allowedModes = listOf("headless", "virtual_display")
                )
            ),
            defaultToVirtualDisplay = false,
            // The host says headless in its own spelling; the game resolves to the
            // contract's. These are two vocabularies for one answer, and comparing them
            // raw reported an override on every game -- including the ones that agree.
            clientSettings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(streamDisplayMode = "headless_stream")
            ),
            profilePreference = "quality"
        )

        assertEquals("headless_stream", state.playMode)
        assertFalse("agreeing with the host is not overriding it", state.overridesHostMode)
    }

    @Test
    fun choosingSomewhereElseThanTheHostIsAnOverride() {
        val state = NovaGameDetailUiState.from(
            game = game(
                launchMode = PolarisGame.LaunchModeContract(
                    preferredMode = "headless",
                    allowedModes = listOf("headless", "virtual_display")
                )
            ),
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(streamDisplayMode = "headless_stream")
            ),
            profilePreference = "quality",
            launchModeOverride = "virtual_display",
        )

        assertEquals("host_virtual_display", state.playMode)
        assertTrue(state.overridesHostMode)
    }

    @Test
    fun virtualRecommendedModeUsesVirtualPlayMode() {
        val state = NovaGameDetailUiState.from(
            game = game(
                launchMode = PolarisGame.LaunchModeContract(
                    preferredMode = "headless",
                    recommendedMode = "virtual_display",
                    allowedModes = listOf("headless", "virtual_display")
                )
            ),
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(streamDisplayMode = "virtual_display")
            ),
            profilePreference = "quality"
        )

        assertEquals("host_virtual_display", state.playMode)
        assertTrue(state.playUsesVirtualDisplay)
        assertTrue(state.playEnabled)
        assertTrue(state.launchOptionsEnabled)
        assertTrue(state.showLaunchOptionsButton)
        assertFalse(state.showLaunchModeSummary)
        assertEquals("quality", state.profilePreference)
    }

    @Test
    fun virtualRecommendationWinsWhenClientSettingsHaveNoDisplayMode() {
        val state = NovaGameDetailUiState.from(
            game = game(
                launchMode = PolarisGame.LaunchModeContract(
                    preferredMode = "headless",
                    recommendedMode = "virtual_display",
                    allowedModes = listOf("headless", "virtual_display")
                )
            ),
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(),
            profilePreference = "auto"
        )

        assertEquals("host_virtual_display", state.playMode)
        assertTrue(state.playUsesVirtualDisplay)
        assertTrue(state.playEnabled)
    }

    @Test
    fun unavailableVirtualDisplayFallsBackToHeadlessAndShowsUnavailableState() {
        val state = NovaGameDetailUiState.from(
            game = game(
                launchMode = PolarisGame.LaunchModeContract(
                    preferredMode = "virtual_display",
                    recommendedMode = "virtual_display",
                    allowedModes = listOf("headless", "virtual_display")
                )
            ),
            defaultToVirtualDisplay = true,
            clientSettings = PolarisClientSettings(
                capabilities = PolarisClientSettings.Capabilities(
                    modes = listOf(
                        PolarisClientSettings.ModeOption(value = "headless", available = true),
                        PolarisClientSettings.ModeOption(
                            value = "virtual_display",
                            available = false,
                            reason = "Host virtual display disabled"
                        )
                    )
                )
            ),
            profilePreference = "invalid"
        )

        assertEquals("headless_stream", state.playMode)
        assertFalse(state.playUsesVirtualDisplay)
        assertTrue(state.virtualDisplayUnavailable)
        assertTrue(state.showVirtualUnavailableHint)
        assertFalse(state.showLaunchOptionsButton)
        assertTrue(state.showLaunchModeSummary)
        assertEquals("Host virtual display disabled", state.virtualDisplayUnavailableReason)
        assertEquals("auto", state.profilePreference)
    }

    @Test
    fun noAllowedModesDisablesLaunchButtonsAndRecommendationBadge() {
        val state = NovaGameDetailUiState.from(
            game = game(
                launchMode = PolarisGame.LaunchModeContract(
                    preferredMode = "headless",
                    recommendedMode = "virtual_display",
                    allowedModes = emptyList()
                )
            ),
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(
                capabilities = PolarisClientSettings.Capabilities(
                    modes = listOf(
                        PolarisClientSettings.ModeOption(value = "headless", available = false),
                        PolarisClientSettings.ModeOption(value = "virtual_display", available = false)
                    )
                )
            ),
            profilePreference = "auto"
        )

        assertFalse(state.playEnabled)
        assertFalse(state.launchOptionsEnabled)
        assertFalse(state.showLaunchOptionsButton)
        assertTrue(state.showLaunchModeSummary)
        assertFalse(state.showRecommendedModeBadge)
    }

    @Test
    fun mangoHudRiskDistinguishesBigPictureFromSteamProtonGames() {
        assertEquals(
            NovaGameDetailUiState.MangoHudRisk.BIG_PICTURE,
            NovaGameDetailUiState.from(
                game = game(name = "Steam Big Picture"),
                defaultToVirtualDisplay = false,
                clientSettings = null,
                profilePreference = "auto"
            ).mangoHudRisk
        )
        assertEquals(
            NovaGameDetailUiState.MangoHudRisk.STEAM,
            NovaGameDetailUiState.from(
                game = game(runtime = "proton", source = "steam", steamAppid = "1234"),
                defaultToVirtualDisplay = false,
                clientSettings = null,
                profilePreference = "auto"
            ).mangoHudRisk
        )
    }

    @Test
    fun steamLaunchModeShowsForSteamGamesWithServerSupport() {
        val state = NovaGameDetailUiState.from(
            game = game(
                source = "steam",
                steamAppid = "400",
                steamLaunch = PolarisGame.SteamLaunchContract(
                    available = true,
                    mode = "direct",
                    recommendedMode = "direct",
                    allowedModes = listOf("direct", "big-picture")
                )
            ),
            defaultToVirtualDisplay = false,
            clientSettings = null,
            profilePreference = "auto"
        )

        assertTrue(state.showSteamLaunchMode)
        assertEquals("direct", state.steamLaunchMode)
        assertFalse(state.steamLaunchWarning)
    }

    @Test
    fun steamLaunchModeHidesForNonSteamGamesAndWarnsForBigPicture() {
        val nonSteam = NovaGameDetailUiState.from(
            game = game(
                source = "manual",
                steamAppid = "",
                steamLaunch = PolarisGame.SteamLaunchContract(
                    available = true,
                    mode = "direct",
                    allowedModes = listOf("direct", "big-picture")
                )
            ),
            defaultToVirtualDisplay = false,
            clientSettings = null,
            profilePreference = "auto"
        )

        val bigPicture = NovaGameDetailUiState.from(
            game = game(
                source = "steam",
                steamAppid = "883710",
                steamLaunch = PolarisGame.SteamLaunchContract(
                    available = true,
                    mode = "big-picture",
                    allowedModes = listOf("direct", "big-picture")
                )
            ),
            defaultToVirtualDisplay = false,
            clientSettings = null,
            profilePreference = "auto"
        )

        assertFalse(nonSteam.showSteamLaunchMode)
        assertTrue(bigPicture.showSteamLaunchMode)
        assertTrue(bigPicture.steamLaunchWarning)
    }

    @Test
    fun desktopSteamPolicyRequiresDecisionForPrivatePhysicalDisplayRisk() {
        val state = NovaGameDetailUiState.from(
            game = game(name = "Steam Big Picture"),
            defaultToVirtualDisplay = false,
            clientSettings = null,
            profilePreference = "auto"
        )
        val optimization = JSONObject()
            .put(
                "launchPolicy",
                JSONObject()
                    .put("desktopSteamActive", true)
                    .put("physicalDisplayRisk", true)
                    .put("canLaunchPrivateStream", false)
                    .put("canMirrorDesktop", true)
                    .put("recommendedAction", "refuse_private_stream")
            )

        val decision = NovaDesktopSteamLaunchDecision.from(state, optimization)

        assertTrue(decision.required)
        assertFalse(decision.privateStreamEnabled)
        assertTrue(decision.mirrorDesktopEnabled)
        assertEquals("Private stream is unavailable while desktop Steam is active.", decision.privateStreamUnavailableReason)
    }

    @Test
    fun desktopSteamPolicyOffersExplicitForcePrivateShutdown() {
        val state = NovaGameDetailUiState.from(
            game = game(name = "MOUSE:PI"),
            defaultToVirtualDisplay = false,
            clientSettings = null,
            profilePreference = "auto"
        )
        val optimization = JSONObject()
            .put(
                "launchPolicy",
                JSONObject()
                    .put("desktopSteamActive", true)
                    .put("physicalDisplayRisk", true)
                    .put("canLaunchPrivateStream", false)
                    .put("canMirrorDesktop", true)
                    .put("canForceCloseDesktopSteamForPrivateStream", true)
                    .put("forcePrivateStreamLabel", "Close desktop Steam and start private stream")
            )

        val decision = NovaDesktopSteamLaunchDecision.from(state, optimization)

        assertTrue(decision.required)
        assertTrue(decision.forcePrivateAfterSteamCloseEnabled)
        assertEquals("Close desktop Steam and start private stream", decision.forcePrivateAfterSteamCloseLabel)
    }

    @Test
    fun desktopSteamPolicyDoesNotInterruptVirtualDisplayLaunch() {
        val state = NovaGameDetailUiState.from(
            game = game(name = "Steam Big Picture"),
            defaultToVirtualDisplay = true,
            clientSettings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(streamDisplayMode = "virtual_display")
            ),
            profilePreference = "auto"
        )
        val optimization = JSONObject()
            .put(
                "launchPolicy",
                JSONObject()
                    .put("desktopSteamActive", true)
                    .put("physicalDisplayRisk", true)
            )

        val decision = NovaDesktopSteamLaunchDecision.from(state, optimization)

        assertFalse(decision.required)
    }

    @Test
    fun hostModesOutsideThePairFlowThroughAsPlayModeWithoutAnOverride() {
        val state = NovaGameDetailUiState.from(
            game = game(
                launchMode = PolarisGame.LaunchModeContract(
                    preferredMode = "headless_stream",
                    recommendedMode = "headless_stream",
                    allowedModes = listOf(
                        "headless_stream",
                        "host_virtual_display",
                        PolarisClientSettings.MODE_GPU_NATIVE_TEST,
                    )
                )
            ),
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(
                    streamDisplayMode = PolarisClientSettings.MODE_GPU_NATIVE_TEST
                )
            ),
            profilePreference = "auto"
        )

        // No override: the game follows the host's real mode instead of coercing it
        // into the legacy pair -- the coercion is what made every game claim
        // "This game overrides it" whenever the host ran GPU-native or Mirror.
        assertEquals(PolarisClientSettings.MODE_GPU_NATIVE_TEST, state.playMode)
        assertFalse(state.playUsesVirtualDisplay)
        assertFalse("following the host is not overriding it", state.overridesHostMode)
        assertEquals(PolarisClientSettings.MODE_GPU_NATIVE_TEST, state.hostStreamDisplayMode)
        assertEquals("Private Stream (GPU-native)", state.hostStreamDisplayModeLabel)
        assertEquals("", state.launchStreamMode)
    }

    @Test
    fun stalePerGameHeadlessDongleOverrideFallsBackToTheHostDefault() {
        val state = NovaGameDetailUiState.from(
            game = game(),
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(
                    streamDisplayMode = PolarisClientSettings.MODE_DESKTOP_DISPLAY,
                ),
            ),
            profilePreference = "auto",
            launchModeOverride = PolarisClientSettings.MODE_HEADLESS_DONGLE,
        )

        assertEquals(PolarisClientSettings.MODE_DESKTOP_DISPLAY, state.playMode)
        assertFalse(state.hasExplicitOverride)
        assertFalse(state.overridesHostMode)
    }

    @Test
    fun unavailableNonPairHostDefaultUsesAndCarriesTheHostsSafeRecommendation() {
        val state = NovaGameDetailUiState.from(
            game = game(
                launchMode = PolarisGame.LaunchModeContract(
                    preferredMode = PolarisGame.MODE_WINDOWED_STREAM,
                    recommendedMode = PolarisGame.MODE_DESKTOP_DISPLAY,
                    allowedModes = listOf(PolarisGame.MODE_DESKTOP_DISPLAY),
                ),
            ),
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(streamDisplayMode = PolarisGame.MODE_WINDOWED_STREAM),
                capabilities = PolarisClientSettings.Capabilities(
                    modes = listOf(
                        PolarisClientSettings.ModeOption(
                            value = PolarisGame.MODE_WINDOWED_STREAM,
                            available = false,
                            unavailableReason = "labwc and wlr-randr are not installed",
                        ),
                        PolarisClientSettings.ModeOption(
                            value = PolarisGame.MODE_DESKTOP_DISPLAY,
                            available = true,
                            sessionOverridable = true,
                        ),
                    ),
                ),
            ),
            profilePreference = "auto",
        )

        assertEquals(PolarisGame.MODE_DESKTOP_DISPLAY, state.playMode)
        assertEquals(PolarisGame.MODE_DESKTOP_DISPLAY, state.launchStreamMode)
        assertEquals("Mirror Desktop", state.playModeLabel)
        assertFalse(state.hasExplicitOverride)
        assertTrue(state.usesSafeHostFallback)
        assertEquals(
            "labwc and wlr-randr are not installed",
            state.hostStreamDisplayModeUnavailableReason,
        )
    }

    @Test
    fun availableNonPairHostDefaultStillLaunchesAsAnUnpinnedHostDefault() {
        val state = NovaGameDetailUiState.from(
            game = game(
                launchMode = PolarisGame.LaunchModeContract(
                    recommendedMode = PolarisGame.MODE_GAMESCOPE_STREAM,
                    allowedModes = listOf(PolarisGame.MODE_GAMESCOPE_STREAM),
                ),
            ),
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(streamDisplayMode = PolarisGame.MODE_GAMESCOPE_STREAM),
                capabilities = PolarisClientSettings.Capabilities(
                    modes = listOf(
                        PolarisClientSettings.ModeOption(
                            value = PolarisGame.MODE_GAMESCOPE_STREAM,
                            available = true,
                            sessionOverridable = true,
                        ),
                    ),
                ),
            ),
            profilePreference = "auto",
        )

        assertEquals(PolarisGame.MODE_GAMESCOPE_STREAM, state.playMode)
        assertEquals("Gamescope Stream", state.playModeLabel)
        assertEquals("", state.launchStreamMode)
        assertFalse(state.usesSafeHostFallback)
    }

    @Test
    fun availableNonPairChoicesKeepPlayOptionsVisible() {
        val state = NovaGameDetailUiState.from(
            game = game(
                launchMode = PolarisGame.LaunchModeContract(
                    recommendedMode = PolarisGame.MODE_DESKTOP_DISPLAY,
                    allowedModes = listOf(
                        PolarisGame.MODE_DESKTOP_DISPLAY,
                        PolarisGame.MODE_GAMESCOPE_STREAM,
                    ),
                ),
            ),
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(streamDisplayMode = PolarisGame.MODE_DESKTOP_DISPLAY),
                capabilities = PolarisClientSettings.Capabilities(
                    modes = listOf(
                        PolarisClientSettings.ModeOption(
                            value = PolarisGame.MODE_DESKTOP_DISPLAY,
                            available = true,
                            sessionOverridable = true,
                        ),
                        PolarisClientSettings.ModeOption(
                            value = PolarisGame.MODE_GAMESCOPE_STREAM,
                            available = true,
                            sessionOverridable = true,
                        ),
                    ),
                ),
            ),
            profilePreference = "auto",
        )

        assertEquals(2, state.actionableLaunchModeCount)
        assertTrue(state.launchOptionsEnabled)
        assertTrue(state.showLaunchOptionsButton)
    }

    @Test
    fun staleUnavailableNonPairPerGameOverrideIsIgnored() {
        val state = NovaGameDetailUiState.from(
            game = game(
                launchMode = PolarisGame.LaunchModeContract(
                    recommendedMode = PolarisGame.MODE_DESKTOP_DISPLAY,
                    allowedModes = listOf(PolarisGame.MODE_DESKTOP_DISPLAY),
                ),
            ),
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(streamDisplayMode = PolarisGame.MODE_DESKTOP_DISPLAY),
                capabilities = PolarisClientSettings.Capabilities(
                    modes = listOf(
                        PolarisClientSettings.ModeOption(value = PolarisGame.MODE_DESKTOP_DISPLAY, available = true),
                        PolarisClientSettings.ModeOption(value = PolarisGame.MODE_GAMESCOPE_STREAM, available = false),
                    ),
                ),
            ),
            profilePreference = "auto",
            launchModeOverride = PolarisGame.MODE_GAMESCOPE_STREAM,
        )

        assertEquals(PolarisGame.MODE_DESKTOP_DISPLAY, state.playMode)
        assertEquals("", state.launchStreamMode)
        assertFalse(state.hasExplicitOverride)
    }

    private fun game(
        name: String = "Portal",
        runtime: String = "native",
        source: String = "steam",
        steamAppid: String = "",
        launchMode: PolarisGame.LaunchModeContract? = null,
        steamLaunch: PolarisGame.SteamLaunchContract? = null
    ) = PolarisGame(
        id = "game-1",
        name = name,
        source = source,
        launcherSource = source,
        runtime = runtime,
        steamAppid = steamAppid,
        launchMode = launchMode,
        steamLaunch = steamLaunch
    )
}
