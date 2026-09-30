package com.papi.nova.ui.compose

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.papi.nova.preferences.NovaSettingDefinitions
import com.papi.nova.preferences.NovaSettingValue
import com.papi.nova.preferences.NovaSettingsAvailability
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w480dp-h900dp-port")
class NovaControlSizeComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val key = "nova_control_size"

    @Test fun standardIsDefaultAndThePreferenceStaysOutOfSavedGameSetups() {
        val definition = NovaSettingDefinitions.load(compose.activity).find(key)
        assertNotNull("control size is an appearance setting", definition)
        assertEquals(NovaSettingValue.StringValue("standard"), definition!!.defaultValue)
        assertEquals(listOf("Compact", "Standard", "Large"), definition.options.map { it.label })
        assertFalse("a game setup must not change this device's interface size",
            NovaSettingsAvailability.shouldPersistProfileOverride(key))
    }

    @Test fun compactAndLargeResizeControlsLiveWithoutResizingTextOrLosingFocus() = checkSizes(1f)

    @Test fun enlargedSystemTextAndPhysicalTouchTargetsSurviveCompactControls() = checkSizes(1.3f)

    private fun checkSizes(fontScale: Float) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(compose.activity)
        preferences.edit().remove(key).commit()
        val resourceConfiguration = Configuration(compose.activity.resources.configuration)
        var physicalTextHeight = 0
        var touchTargetPixels = 0f
        var viewportWidthPixels = 0f
        var baseViewportWidthPixels = 0f
        var measuredDensity = 0f
        compose.setContent {
            val original = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(original.density, fontScale)) {
                val unscaledDensity = LocalDensity.current
                val unscaledConfiguration = LocalConfiguration.current
                SideEffect { baseViewportWidthPixels = unscaledConfiguration.screenWidthDp * unscaledDensity.density }
                NovaComposeTheme {
                    val density = LocalDensity.current
                    val config = LocalConfiguration.current
                    val touch = LocalViewConfiguration.current.minimumTouchTargetSize
                    SideEffect {
                        measuredDensity = density.density
                        touchTargetPixels = with(density) { touch.height.toPx() }
                        viewportWidthPixels = config.screenWidthDp * density.density
                    }
                    Column {
                        NovaActionSurface(onClick = {}, modifier = Modifier.width(160.dp).testTag("control"), minHeight = 80.dp) { color, _ ->
                            Text("Sample", color = color, fontSize = 16.sp, modifier = Modifier.testTag("label"))
                        }
                        NovaComposeTheme {
                            NovaActionSurface(onClick = {}, modifier = Modifier.width(160.dp).testTag("nested-control"), minHeight = 80.dp) { color, _ ->
                                Text("Sample", color = color, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }
        fun buttonHeight() = compose.onNodeWithTag("control").fetchSemanticsNode().boundsInRoot.height
        fun textHeight(): Int {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("label", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single().size.height
        }
        compose.waitForIdle()
        val standard = buttonHeight()
        physicalTextHeight = textHeight()
        val originalTouchTarget = touchTargetPixels
        compose.onNodeWithTag("control").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        for ((choice, factor) in listOf("compact" to 0.88f, "large" to 1.15f, "standard" to 1f, "invalid" to 1f)) {
            compose.runOnIdle { preferences.edit().putString(key, choice).commit() }
            compose.waitForIdle()
            assertEquals(choice, standard * factor, buttonHeight(), 1f)
            assertEquals("nested themes apply the choice once", buttonHeight(),
                compose.onNodeWithTag("nested-control").fetchSemanticsNode().boundsInRoot.height, 1f)
            assertEquals("Text Size is independent", physicalTextHeight, textHeight())
            assertEquals("the physical touch floor does not shrink", originalTouchTarget, touchTargetPixels, 0.1f)
            assertEquals("layout breakpoints use the scaled viewport", baseViewportWidthPixels, viewportWidthPixels, measuredDensity)
            compose.onNodeWithTag("control").assertIsFocused()
            assertEquals("stream/device resources are unchanged", resourceConfiguration, compose.activity.resources.configuration)
        }
        preferences.edit().remove(key).commit()
    }
}
