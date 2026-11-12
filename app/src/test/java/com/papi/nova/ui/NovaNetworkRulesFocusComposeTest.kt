package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import com.papi.nova.network.HostNetworkRules
import com.papi.nova.network.NetworkProfileRule
import com.papi.nova.profiles.SettingsProfile
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaSurfaceLook
import com.papi.nova.ui.compose.NovaSurfaceLookKey
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Network Rules under focus (#479). Its controls are Material's, whose own focus was a faint grey
 * halo round the switch's thumb and a slightly lighter fill on Add rule, the rule's chips and Delete
 * rule; nothing on the screen drew a ring, so from a sofa it was hard to tell which control A would
 * press. Each control now takes Nova's one focus look, the accent ring over the selected fill, and
 * drops it when focus moves on. The look is read from the node that draws it.
 *
 * How the ring reads on the RP6 and the Shield is the device walk's to show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h1600dp")
class NovaNetworkRulesFocusComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var surfaces: NovaLibrarySurfaces

    private val profile = SettingsProfile(UUID.randomUUID(), "Travel", 1L, 1L, emptyMap())

    private fun show(): NovaTestKeys {
        val rules = HostNetworkRules(
            enabled = true,
            rules = listOf(
                NetworkProfileRule(profileUuid = profile.getUuid().toString()),
                NetworkProfileRule(profileUuid = profile.getUuid().toString()),
            ),
        )
        val keys = rule.setPanelContent {
            surfaces = LocalNovaLibrarySurfaces.current
            NetworkRulesScreen(hostName = "PC", rules = rules, profiles = listOf(profile), onChange = {})
        }
        rule.waitForIdle()
        return keys
    }

    private fun look(tag: String): NovaSurfaceLook =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config[NovaSurfaceLookKey]

    private val controls = listOf(
        NovaNetworkRulesActivity.TAG_ENABLED,
        NovaNetworkRulesActivity.ruleTag(0, "profile"),
        NovaNetworkRulesActivity.ruleTag(0, "transport"),
        NovaNetworkRulesActivity.ruleTag(0, "metered"),
        NovaNetworkRulesActivity.ruleTag(0, "vpn"),
        NovaNetworkRulesActivity.ruleTag(0, "hostConnection"),
        NovaNetworkRulesActivity.ruleTag(0, "down"),
        NovaNetworkRulesActivity.ruleTag(0, "delete"),
        NovaNetworkRulesActivity.ruleTag(1, "up"),
        NovaNetworkRulesActivity.ruleTag(1, "delete"),
        NovaNetworkRulesActivity.TAG_ADD,
    )

    @Test
    fun everyControlRingsUnderFocusAndOnlyThatOne() {
        show()
        controls.forEach { tag ->
            rule.onNodeWithTag(tag).requestFocus()
            rule.waitForIdle()
            rule.onNodeWithTag(tag).assertIsFocused()
            assertEquals(
                "$tag under focus wears the ring every other Nova control has",
                NovaSurfaceLook(fill = surfaces.selectedControl, ring = surfaces.focusRing, ringStandsOff = false),
                look(tag),
            )
            (controls - tag).forEach { other ->
                assertEquals("$other has no ring while $tag has focus", Color.Unspecified, look(other).ring)
            }
        }
    }

    @Test
    fun theRingFollowsTheDpadFromTheSwitch() {
        val keys = show()
        rule.onNodeWithTag(NovaNetworkRulesActivity.TAG_ENABLED).requestFocus()
        rule.waitForIdle()
        keys.press(NovaTestKeys.DOWN)
        rule.waitForIdle()
        val focused = rule.onNode(isFocused()).fetchSemanticsNode()
        assertNotEquals("Down left the switch", rule.onNodeWithTag(NovaNetworkRulesActivity.TAG_ENABLED).fetchSemanticsNode().id, focused.id)
        assertEquals("the control Down reached wears the ring", surfaces.focusRing, focused.config[NovaSurfaceLookKey].ring)
        assertEquals("the switch let its ring go", Color.Unspecified, look(NovaNetworkRulesActivity.TAG_ENABLED).ring)
    }
}
