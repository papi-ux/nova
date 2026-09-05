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
