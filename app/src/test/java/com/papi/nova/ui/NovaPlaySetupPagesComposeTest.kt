package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.papi.nova.ui.panel.NovaCurrentMarkTag
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where It Runs as a Play Setup page: it opens on the current mode, focus and current are two
 * different marks, one A picks and pops, and a mode the host will not take only says why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPlaySetupPagesComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val state = NovaPanelState()
    private val picked = mutableListOf<String>()
    private var hostDefaultPicks = 0
    private var configureRequests = 0

    private fun choice(
        id: String,
        label: String,
        group: String,
        current: Boolean = false,
        enabled: Boolean = true,
        hostDefaultOnly: Boolean = false,
    ) = NovaPlaySetupModeChoice(
        id = id,
        label = label,
        detail = "$label, in full.",
        group = group,
        current = current,
        active = false,
        enabled = enabled,
        hostDefaultOnly = hostDefaultOnly,
    )

    private fun picker(hostDefaultCurrent: Boolean = false, currentId: String? = "virtual") = NovaPlaySetupModePickerState(
        title = "Where It Runs",
        hostDefaultLabel = "Follows the host: Headless",
        hostDefaultCurrent = hostDefaultCurrent,
        choices = listOf(
            choice("headless", "Headless", "private", current = currentId == "headless"),
            choice("gpu", "GPU Native", "private", enabled = false),
            choice("virtual", "Virtual Display", "host", current = currentId == "virtual"),
            choice("dongle", "Headless Dongle", "host", enabled = false, hostDefaultOnly = true),
        ),
    )

    private fun host(picker: NovaPlaySetupModePickerState): NovaTestKeys {
        state.open(PlaySetupPage.Root("Play Setup"), NovaEdge.End)
        state.push(
            PlaySetupPage.PlayIn(
                title = "Where It Runs",
                picker = { picker },
                onPick = { picked += it },
                onPickHostDefault = { hostDefaultPicks++ },
                onConfigureHost = { configureRequests++ },
            ),
        )
        return rule.setPanelContent {
            NovaPageStackHost(state = state) { page ->
                when (page) {
                    is PlaySetupPage.PlayIn -> NovaPlayInPage(page)
                    else -> NovaRow(title = "Setup rows", onClick = {}, modifier = Modifier.novaInitialFocus())
                }
            }
        }
    }

    @Test
    fun opensOnTheCurrentModeWithTheOneCurrentMark() {
        host(picker())

        rule.onNode(hasText("Virtual Display")).assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onAllNodesWithTag(NovaCurrentMarkTag, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun focusMovesButTheCurrentMarkStaysWhereTheValueIs() {
        val keys = host(picker())

        keys.press(NovaTestKeys.UP)
        keys.press(NovaTestKeys.UP)

        rule.onNode(hasText("Headless")).assertIsFocused()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        rule.onNode(hasText("Virtual Display"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onAllNodesWithTag(NovaCurrentMarkTag, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun oneAPicksAndPops() {
        val keys = host(picker())
        keys.press(NovaTestKeys.UP)
        keys.press(NovaTestKeys.UP)

        keys.press(NovaTestKeys.CENTER)

        assertEquals(listOf("headless"), picked)
        assertEquals("the page popped back to the rows", 1, state.depth)
    }

    @Test
    fun aModeTheHostWillNotTakeSaysWhyAndSwallowsA() {
        val keys = host(picker())
        keys.press(NovaTestKeys.UP)
        rule.onNode(hasText("GPU Native")).assertIsFocused()

        keys.press(NovaTestKeys.CENTER)
        keys.press(NovaTestKeys.A)

        assertTrue(picked.isEmpty())
        assertEquals(2, state.depth)
        rule.onNode(hasText("GPU Native")).assertIsFocused()
    }

    @Test
    fun aHostOnlyModeOpensHostSettingsInsteadOfPicking() {
        val keys = host(picker())

        keys.press(NovaTestKeys.DOWN)
        rule.onNode(hasText("Headless Dongle")).assertIsFocused()
        keys.press(NovaTestKeys.CENTER)

        assertTrue(picked.isEmpty())
        assertEquals(1, configureRequests)
    }

    @Test
    fun followingTheHostOpensOnTheHostDefaultEntry() {
        val keys = host(picker(hostDefaultCurrent = true, currentId = null))

        rule.onNode(hasText("Host default")).assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        keys.press(NovaTestKeys.CENTER)

        assertEquals(1, hostDefaultPicks)
        assertEquals(1, state.depth)
    }
}
