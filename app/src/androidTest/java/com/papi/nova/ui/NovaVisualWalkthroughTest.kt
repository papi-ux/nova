package com.papi.nova.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The visual gate (spec 9.4): opens every surface in [NovaVisualSurfaces] in turn with fixture
 * data and no host, and for each one walks what is on screen at rest (see NovaVisualInspector:
 * text that overflows, is cut to lines, ellipsized or broken mid-word; anything cut by a container,
 * the window, a system bar or a cutout; a strip ending mid-item; a panel off its edge, not full
 * height, rounded on the wrong corners, or more than one panel), saves a screenshot, and writes a
 * PASS, FAIL or SKIP line for it to results.txt. Each surface is its own test, on its own activity,
 * so one failing hides nothing about the others.
 *
 * Run it on each emulator and font scale (spec 9.4 lists them):
 *
 *     adb shell am instrument -w -r -e class com.papi.nova.ui.NovaVisualWalkthroughTest \
 *         com.papi.nova.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Screenshots and results land in /sdcard/Download/nova-visual/<run>/ (and in the app's external
 * files under nova-visual/<run>), where <run> is the AVD name or model, the size in dp and the font
 * scale unless `-e novaVisualTag <name>` names it. `-e novaSurfaces a,b` walks only those surfaces
 * and adds to the run's results; `-e novaHoldSeconds 20` holds each surface on screen; `-e novaStrict
 * true` fails rows cut at a scrolling list's edge past the focused row too; `-e novaFormFactor tv`
 * draws the surfaces in the activity for a television on an emulator only sized as one.
 */
@RunWith(Parameterized::class)
class NovaVisualWalkthroughTest(private val surfaceName: String) {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun walk() {
        Assume.assumeTrue("not among -e novaSurfaces", chosen.isEmpty() || surfaceName in chosen)
        val surface = NovaVisualSurfaces.named(surfaceName)
        val output = NovaVisualOutput.current
        val stage = NovaVisualStage(rule)
        var findings = NovaVisualFindings()
        var skipped: String? = null
        try {
            stage.prepare()
            surface.open(stage)
            stage.settle()
            findings = NovaVisualInspector(stage).inspect(surface.panels)
        } catch (skip: NovaVisualSkip) {
            skipped = skip.message
        } catch (error: Throwable) {
            findings.failures += "the surface could not be opened and walked: ${error.javaClass.simpleName}: ${error.message}"
        } finally {
            if (skipped == null) {
                val file = output.file("${surface.name}.png")
                val own = stage.extraShots.remove("second-display")
                findings.screenshot = if (own != null) {
                    file.outputStream().use { own.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    true
                } else {
                    runCatching { stage.screenshot(file) }.getOrDefault(false)
                }
                runCatching { stage.hold() }
            }
            stage.close()
            output.record(surface, findings, skipped)
        }
        Assume.assumeTrue(skipped.orEmpty(), skipped == null)
        assertTrue(
            "${surface.name} (${output.shared}/${surface.name}.txt):\n" + findings.failures.joinToString("\n"),
            findings.failures.isEmpty(),
        )
    }

    companion object {
        private val chosen: List<String> by lazy {
            InstrumentationRegistry.getArguments().getString("novaSurfaces")
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun surfaces(): List<String> = NovaVisualSurfaces.names

        @JvmStatic
        @BeforeClass
        fun startRun() {
            NovaVisualOutput.start(whole = chosen.isEmpty())
        }
    }
}
