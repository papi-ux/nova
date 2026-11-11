package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.R
import com.papi.nova.network.HostNetworkRules
import com.papi.nova.profiles.SettingsProfile
import com.papi.nova.ui.compose.NovaComposeTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Network Rules hands a screen reader when no settings profile is saved yet (#470). The
 * switch is the screen's only focus stop then, so with a D-pad TalkBack said "Off. Use network
 * rules for this host. Switch" and never the sentence under it that says why the switch can do
 * nothing yet. The switch now carries that sentence; the screen looks the same.
 *
 * This reads the switch's semantics. What TalkBack says from them on a TV, and in what order, is
 * the Shield walk's to show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaNetworkRulesSpokenComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = rule.activity.getString(id)

    private fun show(profiles: List<SettingsProfile>) {
        rule.setContent {
            NovaComposeTheme {
                NetworkRulesScreen(hostName = "PC", rules = HostNetworkRules.NONE, profiles = profiles, onChange = {})
            }
        }
        rule.waitForIdle()
    }

    private fun spoken(): List<String> =
        rule.onNodeWithTag(NovaNetworkRulesActivity.TAG_ENABLED).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]

    @Test fun withNoSavedProfileTheSwitchSaysWhyItCanDoNothingYet() {
        show(emptyList())
        val noProfiles = text(R.string.nova_network_rules_no_profiles)
        assertEquals(listOf(text(R.string.nova_network_rules_enabled) + ". " + noProfiles), spoken())
        // The sentence is still on screen where it was.
        rule.onNodeWithText(noProfiles).assertIsDisplayed()
    }

    @Test fun withASavedProfileTheSwitchIsNamedAsBefore() {
        show(listOf(SettingsProfile(UUID.randomUUID(), "Travel", 1L, 1L, emptyMap())))
        assertEquals(listOf(text(R.string.nova_network_rules_enabled)), spoken())
    }
}
