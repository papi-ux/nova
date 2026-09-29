package com.papi.nova.preferences

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.papi.nova.R
import com.papi.nova.ui.NovaLibraryActivity
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A Shield opened with its remote showed A, B, X and L1/R1 until the first D-pad press, although a
 * remote has none of them (C04). On a television, Settings and the Library name the remote's keys
 * from the start, before any key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaTelevisionStartsOnRemoteKeysTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun television(on: Boolean) {
        shadowOf(rule.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, on)
    }

    private val definitions = NovaSettingsDefinitionSet(
        listOf(NovaSettingCategory("stream", "Stream", "")),
        listOf(
            NovaSettingDefinition(
                key = "checkbox_enable_hdr", title = "HDR", summary = "", categoryKey = "stream",
                type = NovaSettingType.Toggle, defaultValue = NovaSettingValue.BooleanValue(false),
            ),
        ),
    )

    private fun settingsHints(): String {
        rule.setPanelContent {
            NovaSettingsContent(
                state = NovaSettingsUiStateFactory.build(definitions, emptyMap(), "stream", "", resettableKeys = setOf("checkbox_enable_hdr")),
                title = "Settings",
                subtitle = "",
                onBack = {},
                onOpenLegacy = {},
                onSearch = {},
                onClearSearch = {},
                onCategory = {},
                headerActions = emptyList(),
                onResetSetting = {},
                onValue = { _, _, done -> done() },
                onSetting = {},
            )
        }
        rule.mainClock.advanceTimeBy(NOVA_FIRST_FOCUS_SETTLE_MS + 32)
        rule.waitForIdle()
        return rule.onNode(hasContentDescription("Select", substring = true))
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
    }

    @Test
    fun onATelevisionSettingsNamesTheRemotesKeysBeforeAnyKey() {
        television(true)
        val hints = settingsHints()
        val ok = rule.activity.getString(R.string.nova_controller_hint_remote_center)
        assertTrue("OK first, before a key: $hints", hints.startsWith("$ok "))
        assertFalse("no X: $hints", hints.contains(rule.activity.getString(R.string.nova_controller_hint_x) + " "))
        assertFalse("no shoulders: $hints", hints.contains(rule.activity.getString(R.string.nova_controller_hint_lb_rb)))
    }

    @Test
    fun elsewhereSettingsStartsOnAControllersKeys() {
        television(false)
        val hints = settingsHints()
        assertTrue(hints, hints.startsWith(rule.activity.getString(R.string.nova_panel_key_a) + " "))
    }

    @Test
    fun onATelevisionTheLibraryStartsOnTheRemotesKeys() {
        television(true)
        assertTrue(libraryStartsRemote())
        television(false)
        assertFalse(libraryStartsRemote())
    }

    // A hostless create leaves before loading anything, after the hint bar's input is set.
    private fun libraryStartsRemote(): Boolean {
        val controller = Robolectric.buildActivity(NovaLibraryActivity::class.java).create()
        try {
            @Suppress("UNCHECKED_CAST")
            val remote = NovaLibraryActivity::class.java.getDeclaredField("lastInputRemote\$delegate").run {
                isAccessible = true
                get(controller.get()) as MutableState<Boolean>
            }
            return remote.value
        } finally {
            controller.destroy()
        }
    }
}
