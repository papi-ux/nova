package com.papi.nova.ui

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
class NovaDisplayResolutionPlannerTest {
    @Test
    fun plannerBuildsHandheldFirstPresetRowsAndHidesAdvancedUntilCustom() {
        val planner = NovaDisplayResolutionPlanner.from(
            contract = PolarisGame.DisplayPlannerContract(
                available = true,
                sourceMode = "2560x1600x90",
                recommendedId = "balanced",
                choices = listOf(
                    PolarisGame.DisplayPlannerChoice(id = "balanced", title = "Best for this device", targetMode = "1920x1200x90", badge = "Best for this device"),
                    PolarisGame.DisplayPlannerChoice(id = "sharp", title = "Sharp / Supersampled", targetMode = "3840x2400x90", badge = "1.5x supersample", advanced = true),
                    PolarisGame.DisplayPlannerChoice(id = "performance", title = "Performance", targetMode = "1280x800x90", badge = "0.5x downscale"),
                    PolarisGame.DisplayPlannerChoice(id = "custom", title = "Custom", targetMode = "2560x1600x90", badge = "Advanced", custom = true, advanced = true)
                )
            ),
            fallbackMode = "2560x1600x90",
            includeAdvanced = false
        )

        assertEquals("balanced", planner.recommendedId)
        assertEquals(listOf("Best for this device", "Performance"), planner.visibleChoices.map { it.title })
        assertTrue(planner.visibleChoices.none { it.badge.equals("Press A", ignoreCase = true) })
        assertTrue(planner.hasAdvancedChoices)
    }

    /** What a 1920x1080 host serves every client, whatever the client's own panel is. */
    private fun hostContract() = PolarisGame.DisplayPlannerContract(
        available = true,
        sourceMode = "1920x1080x60",
        recommendedId = "balanced",
        recommendedTitle = "Best for this device",
        recommendedMode = "1440x810x60",
        choices = listOf(
            PolarisGame.DisplayPlannerChoice(id = "native", title = "Native", targetMode = "1920x1080x60", badge = "Native",
                reason = "Match the client panel exactly.", scaleFactor = 1.0),
            PolarisGame.DisplayPlannerChoice(id = "balanced", title = "Balanced", targetMode = "1440x810x60", badge = "Best for this device",
                reason = "Best for this device: preserve aspect ratio while easing encoder and network load.", scaleFactor = 0.75),
            PolarisGame.DisplayPlannerChoice(id = "sharp", title = "Sharp / Supersampled", targetMode = "2880x1620x60", badge = "1.5x supersample",
                reason = "Render above the client panel and downscale for extra clarity when the host has headroom.", advanced = true, scaleFactor = 1.5),
            PolarisGame.DisplayPlannerChoice(id = "performance", title = "Performance", targetMode = "960x540x60", badge = "0.5x downscale",
                reason = "Favor frame pacing and bandwidth over raw pixel count.", scaleFactor = 0.5),
            PolarisGame.DisplayPlannerChoice(id = "custom", title = "Custom", targetMode = "1920x1080x60", badge = "Advanced",
                reason = "Advanced manual scale factor using the existing fallback display mode field.", advanced = true, custom = true, scaleFactor = 1.0),
        )
    )

    private fun plannedFor(width: Int, height: Int, fps: Int = 60, includeAdvanced: Boolean = true) =
        NovaDisplayResolutionPlanner.from(
            contract = hostContract(),
            fallbackMode = "",
            includeAdvanced = includeAdvanced,
            device = NovaDisplayResolutionPlanner.DeviceMode(width, height, fps),
        )

    // nova#302: a Pixel 7 set to a custom 1340x800, in Nova and in Polaris, saw "Custom 1920x1080".
    @Test
    fun aDeviceSetToItsOwnSizeSeesThatSizeFirstAndNoHostCustom() {
        val planner = plannedFor(1340, 800)

        // Native is this device's own size, so This Device is that row and it is not offered twice.
        assertEquals(
            listOf("This Device", "Balanced", "Sharp", "Performance"),
            planner.visibleChoices.map { it.title },
        )
        assertEquals(
            "every preset is its scale factor applied to this device, rounded the way the host rounds",
            listOf("1340x800x60", "1006x600x60", "2010x1200x60", "670x400x60"),
            planner.visibleChoices.map { it.targetMode },
        )
        assertTrue("the host's hand-typed scale factor is not this device's Custom resolution",
            planner.visibleChoices.none { it.custom || it.title == "Custom" || it.id == "custom" })
        assertTrue("1920x1080 is the host's panel, not this device's", planner.visibleChoices.none { it.targetMode.startsWith("1920x1080") })
    }

    @Test
    fun theDeviceSettingIsTheOneDefaultBecauseItIsWhatALaunchUses() {
        val planner = plannedFor(1340, 800)

        assertEquals(NovaDisplayResolutionPlanner.DEVICE_SETTINGS_ID, planner.recommendedId)
        assertEquals("1340x800x60", planner.recommendedMode)
        assertEquals(listOf(NovaDisplayResolutionPlanner.DEVICE_SETTINGS_ID), planner.visibleChoices.filter { it.recommended }.map { it.id })
        // Balanced is a preset here, not the answer, so nothing may call it the best for this device.
        val balanced = planner.visibleChoices.first { it.id == "balanced" }
        assertEquals("Balanced", balanced.title)
        assertEquals("Preserve aspect ratio while easing encoder and network load.", balanced.reason)
        assertTrue(planner.visibleChoices.none { it.title.contains("Best for this device") || it.reason.contains("Best for this device") })
    }

    @Test
    fun aPresetThatLandsOnTheDevicesOwnSizeIsNotOfferedTwice() {
        // Native is the device's size by definition, so This Device already is that row.
        val planner = plannedFor(1920, 1080)

        assertEquals(listOf("device_settings", "balanced", "sharp", "performance"), planner.visibleChoices.map { it.id })
        assertEquals(listOf("1920x1080x60", "1440x810x60", "2880x1620x60", "960x540x60"), planner.visibleChoices.map { it.targetMode })
    }

    @Test
    fun presetsFollowTheDevicesRateAndStayInsideTheSafeEnvelope() {
        assertEquals(listOf("1280x800x120", "960x600x120", "1920x1200x120", "640x400x120"), plannedFor(1280, 800, fps = 120).visibleChoices.map { it.targetMode })
        // 1.5x an 8K panel is past what the host would call safe.
        assertTrue(plannedFor(7680, 4320).visibleChoices.none { it.id == "sharp" })
    }

    @Test
    fun advancedPresetsStayOutUntilAskedFor() {
        val planner = plannedFor(1340, 800, includeAdvanced = false)

        assertEquals(listOf("device_settings", "balanced", "performance"), planner.visibleChoices.map { it.id })
        assertTrue(planner.hasAdvancedChoices)
    }

    @Test
    fun aSavedChoiceThatIsNoLongerOfferedFallsBackToTheDeviceSetting() {
        val visible = plannedFor(1340, 800).visibleChoices

        assertEquals(null, resolveSavedResolutionChoice("custom", visible))
        assertEquals(null, resolveSavedResolutionChoice("native", visible))
        assertEquals("1006x600x60", resolveSavedResolutionChoice("balanced", visible)?.targetMode)
    }

    @Test
    fun withoutADeviceTheHostsOwnPlanIsKeptAsServed() {
        val planner = NovaDisplayResolutionPlanner.from(contract = hostContract(), fallbackMode = "", includeAdvanced = true)

        assertEquals("balanced", planner.recommendedId)
        assertEquals(listOf("1920x1080x60", "1440x810x60", "2880x1620x60", "960x540x60", "1920x1080x60"), planner.visibleChoices.map { it.targetMode })
    }

    @Test
    fun fallbackPlannerKeepsOlderPolarisHostsCompatible() {
        val planner = NovaDisplayResolutionPlanner.from(
            contract = null,
            fallbackMode = "1920x1080x60",
            includeAdvanced = false
        )

        assertFalse(planner.available)
        assertEquals(emptyList<NovaDisplayResolutionChoice>(), planner.visibleChoices)
    }

    @Test
    fun resolutionLabelDropsTheHostRateAndKeepsOddModesVerbatim() {
        // The trailing rate is the host's plan, not this row's decision; a mode the
        // parser does not recognize is shown as served rather than mangled.
        assertEquals("1280x800", NovaDisplayResolutionPlanner.resolutionLabel("1280x800x90"))
        assertEquals("1920x1080", NovaDisplayResolutionPlanner.resolutionLabel("1920x1080x59.94"))
        assertEquals("1920x1080", NovaDisplayResolutionPlanner.resolutionLabel("1920x1080"))
        assertEquals("", NovaDisplayResolutionPlanner.resolutionLabel(""))
    }

    @Test
    fun postSessionReportSummarizesQualityIssueSuggestedLaunchAndRecoveryProfile() {
        val report = NovaPostSessionReportUiState.from(
            health = com.papi.nova.api.PolarisSessionStatus.HealthStatus(
                grade = "watch",
                summary = "Network jitter is the most likely source of hitching.",
                primaryIssue = "network_jitter",
                recommendations = listOf("Lower bitrate or keep Adaptive Bitrate enabled."),
                safeDisplayMode = "headless",
                safeBitrateKbps = 16000,
                safeTargetFps = 60.0,
                recoveryProfile = "network_jitter",
                relaunchRecommended = true
            )
        )

        assertEquals("Grade: Watch", report.qualityLine)
        assertEquals("Main issue: network jitter", report.issueLine)
        assertEquals("Safe profile: headless · 60fps · 16 Mbps", report.nextLaunchLine)
        assertEquals("Recovery record: network jitter", report.recoveryLine)
        assertTrue(report.copyDiagnostics.contains("Network jitter"))
        assertTrue(report.visible)
    }

    @Test
    fun parserToleratesLegacyHealthWithoutReportFields() {
        val report = NovaPostSessionReportUiState.from(
            health = com.papi.nova.api.PolarisSessionStatus.HealthStatus(summary = "Session looks steady.")
        )

        assertEquals("Grade: Unknown", report.qualityLine)
        assertEquals("Main issue: none", report.issueLine)
        assertEquals("Safe profile: keep current settings", report.nextLaunchLine)
        assertEquals("Recovery record: none", report.recoveryLine)
        // A steady health block has nothing to report, so the Command Center card stays
        // hidden instead of listing four "none" lines.
        assertFalse(report.visible)
    }
}
