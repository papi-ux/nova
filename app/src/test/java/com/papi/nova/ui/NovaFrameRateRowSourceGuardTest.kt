package com.papi.nova.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards Frame Rate row behavior that lives inside private closures in
 * NovaGameDetailActivity.onCreate and is therefore not reachable directly from a JVM
 * unit test.
 */
class NovaFrameRateRowSourceGuardTest {
    private val source =
        File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText()

    @Test
    fun autoPreviewUsesTheSameResolvedCadenceAsTheLaunchComposer() {
        val frameRateRow = source.indexOf("row = NovaPlaySetupRow.FRAME_RATE")
        assertTrue("The Frame Rate row must exist.", frameRateRow >= 0)

        val preview = source.lastIndexOf("val effectiveFps =", frameRateRow)
        assertTrue(preview >= 0)
        val previewBody = source.substring(preview, frameRateRow)
        assertTrue(
            "Auto FPS must come from NovaLaunchStreamOverride.automaticFps(), the same " +
                "authority compose() uses for an unpinned launch.",
            previewBody.contains("NovaLaunchStreamOverride.automaticFps("),
        )
        assertTrue(
            "Tuning = High FPS supplies the Settings cadence before the host-resolved " +
                "fallback, matching effectiveFpsPin() at launch.",
            previewBody.contains("chosenFps ?: fpsPin ?: NovaLaunchStreamOverride.automaticFps("),
        )
        assertTrue(
            "A resolution choice owns dimensions only; its trailing targetMode FPS must not " +
                "be shown as Auto when compose() preserves the host-resolved cadence.",
            !previewBody.contains("NovaDisplayResolutionPlanner.resolutionFps("),
        )
    }

    @Test
    fun resolutionChoicesDoNotPresentTheHostModesStaleRateAsTheirValue() {
        val resolutionRow = source.indexOf("row = NovaPlaySetupRow.RESOLUTION")
        val frameRateRow = source.indexOf("row = NovaPlaySetupRow.FRAME_RATE", resolutionRow)
        assertTrue(resolutionRow >= 0 && frameRateRow > resolutionRow)

        val body = source.substring(resolutionRow, frameRateRow)
        assertTrue(
            "Resolution choices should display only dimensions; the separate Frame Rate row owns the composed launch cadence.",
            body.contains("NovaDisplayResolutionPlanner.resolutionLabel(choice.targetMode)") &&
                !body.contains("listOf(choice.targetMode, choice.reason)"),
        )
    }

    /**
     * The Frame Rate row only exists alongside the display planner. Without this, a pin
     * saved during an earlier session with a planner would keep steering
     * launchOptimization()'s fpsOverride while having no visible, clearable control on
     * this screen -- a durable lock nobody can see or undo.
     */
    @Test
    fun aPinIsRetiredWhenThePlannerThatWouldShowItsRowIsUnavailable() {
        val plannerGate = source.indexOf("if (planner.available && planner.visibleChoices.isNotEmpty())")
        assertTrue("buildPlaySetupRows must gate the Resolution/Frame Rate rows on planner availability.", plannerGate >= 0)

        val elseBranch = source.indexOf("} else if (chosenFps != null) {", plannerGate)
        assertTrue(
            "When the planner is unavailable, the else branch must check chosenFps != null so a " +
                "pin from an earlier session with a planner is not left stuck.",
            elseBranch in plannerGate until (plannerGate + 6000),
        )

        val retireCall = source.indexOf("chooseFrameRate(null)", elseBranch)
        assertTrue(
            "The unavailable-planner branch must call chooseFrameRate(null) to retire the pin " +
                "(clearing both the in-memory state and the durable NovaFrameRateOverrides entry) " +
                "instead of leaving it invisible but still affecting the launch.",
            retireCall in elseBranch until (elseBranch + 500),
        )
    }

    /** Offering 120 FPS on a panel that cannot present it turns a choice into a launch error. */
    @Test
    fun theRowOffersOnlyFpsValuesThisPanelCanActuallyPresent() {
        val rowStart = source.indexOf("row = NovaPlaySetupRow.FRAME_RATE")
        assertTrue("The Frame Rate row must exist.", rowStart >= 0)

        val optionsStart = source.indexOf("options = buildList {", rowStart)
        assertTrue(optionsStart in rowStart until (rowStart + 2000))

        val capabilityCall = source.indexOf("NovaDisplayFpsCapability.allowedFpsValues(", optionsStart)
        assertTrue(
            "The Frame Rate row's option list must filter NOVA_FRAME_RATE_CHOICES through " +
                "NovaDisplayFpsCapability.allowedFpsValues(...) -- the same panel-capability " +
                "threshold every other FPS-offering surface in the app uses -- instead of always " +
                "offering all four fixed values regardless of what the display can present.",
            capabilityCall in optionsStart until (optionsStart + 2000),
        )

        val filterCall = source.indexOf("NOVA_FRAME_RATE_CHOICES.filter", optionsStart)
        assertTrue(filterCall in optionsStart until (optionsStart + 2000))
        assertTrue(
            "NOVA_FRAME_RATE_CHOICES must be filtered before the capability check is defined uselessly.",
            capabilityCall < filterCall,
        )
    }

    /**
     * The Tuning row's "Pins N FPS" caption must never name a different fps than what
     * actually launches. An explicit Frame Rate row choice wins over Tuning = High FPS in
     * launchOptimization(), so Tuning claiming a pin it does not control (its own
     * highFpsPin(...) value) whenever chosenFps is also set would show a number the
     * launch does not use -- the review's exact "Tuning says 120, launch is 60" case.
     */
    @Test
    fun tuningRowSuppressesItsOwnPinClaimWheneverTheFrameRateRowChoiceWins() {
        val tuningRowStart = source.indexOf("row = NovaPlaySetupRow.TUNING")
        assertTrue("The Tuning row must exist.", tuningRowStart >= 0)

        val captionCondition = source.indexOf("chosenFps == null && fpsPin != null ->", tuningRowStart)
        assertTrue(
            "The Tuning row's \"Pins N FPS\" caption must only fire when chosenFps is null -- " +
                "otherwise it can name Tuning's own highFpsPin(...) value while the Frame Rate " +
                "row's explicit choice is what actually launches.",
            captionCondition in tuningRowStart until (tuningRowStart + 1500),
        )

        val overriddenField = source.indexOf("overridden = chosenFps == null && fpsPin != null,", tuningRowStart)
        assertTrue(
            "The Tuning row's overridden flag must use the same chosenFps == null && fpsPin != " +
                "null condition as its caption -- the visible explanation and the badge must agree.",
            overriddenField in tuningRowStart until (tuningRowStart + 3500),
        )
    }

    /** A pin saved on one panel (or refresh-rate setting) must not survive as an impossible one on another. */
    @Test
    fun loadingASavedPinCoercesItToWhatThisPanelCanPresent() {
        val loadStart = source.indexOf("private fun loadFrameRateOverride(game: PolarisGame): Int?")
        assertTrue("loadFrameRateOverride must exist.", loadStart >= 0)

        val coerceCall = source.indexOf("NovaDisplayFpsCapability.coerce(", loadStart)
        assertTrue(
            "loadFrameRateOverride must coerce the saved value through " +
                "NovaDisplayFpsCapability.coerce(...) -- otherwise a pin saved on a faster panel (or " +
                "before a refresh-rate change) would keep reaching launchOptimization()'s fpsOverride " +
                "unfiltered, turning a stale choice into a fail-closed launch error.",
            coerceCall in loadStart until (loadStart + 800),
        )
    }
}
