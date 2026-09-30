package com.papi.nova.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.panel.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

/** Fixture-only visual proof: no host, stream, pairing, device preferences or network writes. */
@RunWith(AndroidJUnit4::class)
class NovaOverlayAppearanceInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "overlay-appearance")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun allHudModesAndLargeTextDrawWithoutLabelBoxes() {
        var mode by mutableStateOf(NovaHudMode.DEBUG)
        var opacity by mutableStateOf(0.64f)
        var fontScale by mutableStateOf(1f)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NovaComposeTheme {
                    Box(Modifier.fillMaxSize()) {
                        backdrop()
                        NovaStreamHudContent(NovaHudUiState.preview(mode),
                            Modifier.testTag("hud"), opacityScale = opacity)
                    }
                }
            }
        }
        for (choice in NovaHudMode.entries) {
            rule.runOnIdle { mode = choice }
            rule.waitForIdle()
            shot("hud-${choice.name.lowercase()}-64")
        }
        rule.runOnIdle { mode = NovaHudMode.DEBUG; fontScale = 1.3f }
        rule.waitForIdle()
        val host = rule.onNodeWithText("HOST").getUnclippedBoundsInRoot()
        val client = rule.onNodeWithText("CLIENT").getUnclippedBoundsInRoot()
        assertEquals("833dp emulator has room for three accessible columns", host.top.value, client.top.value, 1f)
        shot("hud-debug-large-text-64")
        rule.runOnIdle { opacity = 0f }
        rule.waitForIdle()
        shot("hud-debug-large-text-clear")
    }

    @Test fun actualStreamPanelDrawsEveryOpacityOverABusyBackground() {
        var percent by mutableStateOf(100)
        rule.setContent {
            NovaComposeTheme(menuOpacityPercent = percent) {
                Box(Modifier.fillMaxSize()) {
                    backdrop()
                    NovaPanelFrame(NovaEdge.Start, NovaPanelWidth.Standard, true, {}, {},
                        scrim = NovaScrim.Stream, overStream = true) {
                        val state = remember { MutableStateFlow(NovaQuickMenuUiState.preview(rule.activity).copy(advancedExpanded = true)) }
                        val panel = remember { NovaPanelState().apply { open(CommandCenterPage.Root("Command Center")) } }
                        NovaPageStackHost(state = panel) { NovaQuickMenuContent(state = state, callbacks = NovaQuickMenuCallbacks()) }
                    }
                }
            }
        }
        for (choice in listOf(100, 64, 25, 0)) {
            rule.runOnIdle { percent = choice }
            rule.waitForIdle()
            shot("menu-opacity-$choice")
        }
    }
}

@androidx.compose.runtime.Composable
private fun backdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0xFFB5CADB))
        val stripe = size.width / 12
        repeat(12) { index ->
            drawRect(if (index % 2 == 0) Color(0xFFDA853B) else Color(0xFF183346),
                topLeft = Offset(index * stripe, size.height / 3),
                size = androidx.compose.ui.geometry.Size(stripe, size.height * 2 / 3))
        }
    }
}
