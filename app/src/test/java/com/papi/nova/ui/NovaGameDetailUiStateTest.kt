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

        assertEquals("virtual_display", state.playMode)
        assertTrue(state.playUsesVirtualDisplay)
        assertTrue(state.playEnabled)
        assertTrue(state.launchOptionsEnabled)
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

        assertEquals("virtual_display", state.playMode)
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

        assertEquals("headless", state.playMode)
        assertFalse(state.playUsesVirtualDisplay)
        assertTrue(state.virtualDisplayUnavailable)
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
