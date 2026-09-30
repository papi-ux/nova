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
        var opened = 0
        lateinit var inputMode: InputModeManager
        compose.setContent {
            NovaComposeTheme {
                inputMode = LocalInputModeManager.current
                val row = novaPlaySetupCodecRow(context, selected, FormatOption.FORCE_HEVC) {
                    selected = it
                    NovaVideoCodecOverrides.save(context, host, game, 1, it)
                }
                Column(Modifier.width(476.dp).background(LocalNovaComposeColors.current.window).padding(12.dp)) {
                    // The production row, which opens the codec page, and the page's own option rows.
                    NovaPlaySetupSettingRow(
                        state = row, onAdvance = { opened++ },
                        modifier = Modifier.testTag("codec-row"),
                    )
                    NovaPlaySetupBands(
                        bands = listOf(NovaPlaySetupBand(null, row.options)),
                        onPick = { it.onSelect?.invoke() },
                        rowModifier = { key, _ -> Modifier.testTag("codec-option-$key") },
                    )
                }
            }
        }

        val control = compose.onNodeWithTag("codec-row")
        // Match real d-pad input: touch mode deliberately excludes clickable controls from focus.
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        control.performSemanticsAction(SemanticsActions.RequestFocus)
        control.assertIsFocused()
        control.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("A opens the codec page rather than stepping in place", 1, opened) }

        val automatic = context.getString(com.papi.nova.R.string.videoformat_auto)
        val option = compose.onNodeWithTag("codec-option-0:$automatic")
        option.performSemanticsAction(SemanticsActions.RequestFocus)
        option.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("auto", selected) }

        compose.onNode(hasText("PyroWave", substring = true) and hasClickAction() and !hasTestTag("codec-row"))
            .assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals("forcepyrowave", NovaVideoCodecOverrides.load(context, host, game, 1))
            val launch = PreferenceConfiguration().apply { videoFormat = FormatOption.FORCE_HEVC }
            NovaVideoCodecOverrides.applyToLaunch(context, Intent().putExtra(Game.EXTRA_PC_UUID, host)
                .putExtra(Game.EXTRA_APP_UUID, game).putExtra(Game.EXTRA_APP_ID, 1), launch)
            assertEquals(FormatOption.FORCE_PYROWAVE, launch.videoFormat)
        }
        // Keep a device rendering of the production row and its page's options alongside the test result.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "play-setup-codec.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("codec-option-0:" + context.getString(com.papi.nova.R.string.nova_play_setup_codec_app_setting))
            .assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle {
            assertNull(NovaVideoCodecOverrides.load(context, host, game, 1))
            assertEquals(FormatOption.FORCE_HEVC, NovaVideoCodecOverrides.resolve(selected, FormatOption.FORCE_HEVC))
        }
    }
}
