package com.papi.nova.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.Game
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.preferences.PreferenceConfiguration.FormatOption
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.compose.LocalNovaComposeColors
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovaPlaySetupCodecComposeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val host = "codec-compose-test-host"
    private val game = "codec-compose-test-game"

    @After fun clearTestChoice() { NovaVideoCodecOverrides.save(context, host, game, 1, null) }

    @Test fun controllerAndTouchChooseTheLaunchCodecAndCanReturnToAppSettings() {
        var selected by mutableStateOf<String?>(null)
        lateinit var inputMode: InputModeManager
        compose.setContent {
            NovaComposeTheme {
                inputMode = LocalInputModeManager.current
                val row = novaPlaySetupCodecRow(context, selected, FormatOption.FORCE_HEVC) {
                    selected = it
                    NovaVideoCodecOverrides.save(context, host, game, 1, it)
                }
                Column(Modifier.width(440.dp).background(LocalNovaComposeColors.current.window).padding(16.dp)) {
                    // The production row: A steps the codec forward in place, as Right does.
                    NovaPlaySetupSettingRow(
                        state = row, onExplain = {}, onAdvance = {},
                        modifier = Modifier.testTag("codec-row"),
                    )
                    NovaPlaySetupComparison(row.stripTitle, row.options, perRow = row.optionsPerRow)
                }
            }
        }

        val control = compose.onNodeWithTag("codec-row")
        // Match real d-pad input: touch mode deliberately excludes clickable controls from focus.
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        control.performSemanticsAction(SemanticsActions.RequestFocus)
        control.assertIsFocused()
        control.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("auto", selected) }

        compose.onNodeWithContentDescription("PyroWave", substring = true)
            .assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals("forcepyrowave", NovaVideoCodecOverrides.load(context, host, game, 1))
            val launch = PreferenceConfiguration().apply { videoFormat = FormatOption.FORCE_HEVC }
            NovaVideoCodecOverrides.applyToLaunch(context, Intent().putExtra(Game.EXTRA_PC_UUID, host)
                .putExtra(Game.EXTRA_APP_UUID, game).putExtra(Game.EXTRA_APP_ID, 1), launch)
            assertEquals(FormatOption.FORCE_PYROWAVE, launch.videoFormat)
        }
        // Keep a device rendering of the production row and legend alongside the test result.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "play-setup-codec.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription("App setting.", substring = true)
            .assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle {
            assertNull(NovaVideoCodecOverrides.load(context, host, game, 1))
            assertEquals(FormatOption.FORCE_HEVC, NovaVideoCodecOverrides.resolve(selected, FormatOption.FORCE_HEVC))
        }
    }
}
