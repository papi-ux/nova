package com.papi.nova.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.papi.nova.binding.video.PyroWaveAvailability
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.preferences.PreferenceConfiguration.FormatOption
import com.papi.nova.preferences.NovaSettingDefinitions
import com.papi.nova.preferences.NovaSettingsUiStateFactory
import com.papi.nova.preferences.NovaSettingValue
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

    @Test fun everyGameCodecUsesTheActualDeviceGateForTouchAndController() {
        val availability=PyroWaveAvailability.inspect(context.applicationContext)
        val reason=PyroWaveAvailability.reason(context,availability)
        val prefs=context.getSharedPreferences("device-codec-compose-fixture",0)
        prefs.edit().clear().putString("video_format","auto").commit()
        val definitions=NovaSettingDefinitions.load(context)
        val definition=requireNotNull(definitions.find("video_format"))
        var selected by mutableStateOf("auto")
        lateinit var inputMode: InputModeManager
        compose.setContent {
            NovaComposeTheme {
                inputMode=LocalInputModeManager.current
                val state=NovaSettingsUiStateFactory.build(definitions,
                    mapOf("video_format" to NovaSettingValue.StringValue(selected)),"category_stream_quality","")
                    .copy(deviceStreamSettings=listOf(definition))
                val row=buildNovaDevicePlaySetupRows(state,{ _,value ->
                    selected=(value as NovaSettingValue.StringValue).value
                    prefs.edit().putString("video_format",selected).commit()
                },availability,reason).single()
                Column(Modifier.width(476.dp).verticalScroll(rememberScrollState())) {
                    NovaPlaySetupBands(listOf(NovaPlaySetupBand(null,row.options)),{ it.onSelect?.invoke() },
                        rowModifier={ key,_ -> Modifier.testTag("device-codec-$key") })
                }
            }
        }
        val pyroLabel=definition.options.first { it.value=="forcepyrowave" }.label
        val pyro=compose.onNodeWithTag("device-codec-0:$pyroLabel").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        pyro.performSemanticsAction(SemanticsActions.RequestFocus)
        pyro.performKeyInput { keyDown(Key.DirectionCenter);keyUp(Key.DirectionCenter) }
        if (availability==PyroWaveAvailability.Status.AVAILABLE) {
            compose.runOnIdle { assertEquals("forcepyrowave",prefs.getString("video_format",null)) }
        } else {
            pyro.assertIsNotEnabled().performTouchInput { click() }
            compose.runOnIdle { assertEquals("auto",prefs.getString("video_format",null));assertTrue(reason.isNotBlank()) }
        }
        val hevcLabel=definition.options.first { it.value=="forceh265" }.label
        compose.onNodeWithTag("device-codec-0:$hevcLabel").performScrollTo().performTouchInput { click() }
        compose.runOnIdle { assertEquals("forceh265",prefs.getString("video_format",null)) }
        prefs.edit().clear().commit()
    }

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
                Column(Modifier.width(476.dp).verticalScroll(rememberScrollState())
                    .background(LocalNovaComposeColors.current.window).padding(12.dp)) {
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

        val availability = PyroWaveAvailability.inspect(context.applicationContext)
        val pyro = compose.onNode(hasText("PyroWave", substring = true) and hasClickAction() and !hasTestTag("codec-row"))
            .performScrollTo().assertIsDisplayed()
        // Native devices retain the real availability gate. An unsupported emulator must not
        // acquire a hidden PyroWave launch choice just to satisfy this interaction test.
        if (PyroWaveAvailability.canSelect("forcepyrowave", availability)) {
            pyro.assertIsEnabled().performTouchInput { click() }
            compose.runOnIdle { assertEquals("forcepyrowave", NovaVideoCodecOverrides.load(context, host, game, 1)) }
        } else {
            pyro.assertIsNotEnabled().performTouchInput { click() }
            compose.runOnIdle { assertEquals("auto", NovaVideoCodecOverrides.load(context, host, game, 1)) }
            compose.onNodeWithText(PyroWaveAvailability.reason(context, availability), substring = true).assertIsDisplayed()
        }
        File(context.getExternalFilesDir(null), "play-setup-codec-availability.txt")
            .writeText("$availability\n${PyroWaveAvailability.reason(context, availability)}\n")

        val hevc = context.getString(com.papi.nova.R.string.videoformat_hevcalways)
        compose.onNodeWithTag("codec-option-0:$hevc")
            .performScrollTo().assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals("forceh265", NovaVideoCodecOverrides.load(context, host, game, 1))
            val launch = PreferenceConfiguration().apply { videoFormat = FormatOption.FORCE_H264 }
            NovaVideoCodecOverrides.applyToLaunch(context, Intent().putExtra(Game.EXTRA_PC_UUID, host)
                .putExtra(Game.EXTRA_APP_UUID, game).putExtra(Game.EXTRA_APP_ID, 1), launch)
            assertEquals(FormatOption.FORCE_HEVC, launch.videoFormat)
        }
        // Keep a device rendering of the production row and its page's options alongside the test result.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "play-setup-codec.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("codec-option-0:" + context.getString(com.papi.nova.R.string.nova_play_setup_codec_app_setting))
            .performScrollTo().assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle {
            assertNull(NovaVideoCodecOverrides.load(context, host, game, 1))
            assertEquals(FormatOption.FORCE_HEVC, NovaVideoCodecOverrides.resolve(selected, FormatOption.FORCE_HEVC))
        }
    }
}
