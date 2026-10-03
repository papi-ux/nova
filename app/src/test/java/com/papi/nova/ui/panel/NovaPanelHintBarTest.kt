package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.ui.compose.NovaControllerHint
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One hint bar style everywhere. The panels' bar is the only one: the older bar in the shared
 * focus components, 30dp tall with 10sp keys and hints that scrolled sideways, is retired, and the
 * screens that showed it draw the panels' bar. As in the approved mockup, its first key sits 12dp
 * inside the bar, on the text line of the rows above it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w833dp-h468dp")
class NovaPanelHintBarTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theFirstKeySitsTwelveDpInsideTheBar() {
        rule.setPanelContent {
            NovaPanelDensityHost {
                NovaPanelHintBar(
                    hints = listOf(NovaControllerHint("A", "Select"), NovaControllerHint("B", "Back")),
                    modifier = Modifier.testTag("bar"),
                )
            }
        }
        val bar = rule.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        val key = rule.onNodeWithText("A", useUnmergedTree = true).getUnclippedBoundsInRoot()
        // The chip starts 12dp in; its letter sits inside the chip's own 8dp.
        assertEquals((NovaPanelMetrics.SpaceMd + NovaPanelMetrics.SpaceSm).value, (key.left - bar.left).value, 0.5f)
    }

    @Test
    fun theOlderBarIsRetiredAndEveryScreenDrawsThePanelsBar() {
        val main = File("src/main/java/com/papi/nova")
        val sources = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertEquals(
            "no second hint bar: NovaControllerHintBar is gone",
            emptyList<String>(),
            sources.filter { it.readText().contains("NovaControllerHintBar") }.map { it.relativeTo(main).path },
        )
        listOf("ui/NovaGameDetailDestinations.kt", "ui/NovaSpaceChooser.kt").forEach { path ->
            assertTrue("$path draws the one hint bar", File(main, path).readText().contains("NovaPanelHintBar("))
        }
    }
}
