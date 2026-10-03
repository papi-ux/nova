package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.preferences.NovaSettingDefinitions
import com.papi.nova.preferences.NovaSettingsContent
import com.papi.nova.preferences.NovaSettingsUiStateFactory
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h600dp-land")
class NovaCameraSettingsComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun header(search: Boolean) {
        val cameras = mutableStateOf<List<Rect>>(emptyList())
        var density = 1f
        val definitions = NovaSettingDefinitions.load(rule.activity)
        rule.setPanelContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalNovaCameraWindow provides NovaCameraWindow(
                Rect(0f, 0f, 900 * density, 600 * density), cameras.value)) {
                NovaSettingsContent(
                    state = NovaSettingsUiStateFactory.build(definitions, emptyMap(), definitions.categories.first().key, ""),
                    title = "Camera settings", subtitle = "Camera subtitle", onBack = {}, onOpenLegacy = {},
                    onSearch = {}, onClearSearch = {}, onCategory = {}, headerActions = emptyList(),
                    onResetSetting = {}, onValue = { _, _, done -> done() }, onSetting = {},
                )
            }
        }
        val node = if (search) rule.onNodeWithContentDescription("Search Settings")
            else rule.onNodeWithText("Camera settings", useUnmergedTree = true)
        val before = node.fetchSemanticsNode().boundsInRoot
        val bottom = before.bottom + 12 * density
        rule.runOnIdle { cameras.value = listOf(Rect(0f, 0f, 900 * density, bottom)) }
        rule.waitForIdle()
        val after = node.fetchSemanticsNode().boundsInRoot
        assertTrue("actual ${if (search) "search target" else "identity glyph"} clears top cutout: $before -> $after / $bottom", after.top >= bottom - 1)
        assertTrue("the actual field/title does not shrink", after.height >= before.height - 1)
    }

    @Test fun actualLandscapeIdentityClearsTopCutout() = header(false)
    @Test fun actualLandscapeSearchTargetClearsTopCutout() = header(true)
}
