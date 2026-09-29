package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.papi.nova.ui.panel.NovaCurrentMarkTag
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaFocusReturn
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
 * different marks, one A picks and pops, and a mode the host will not take only says why. And
 * Play Setup's panel itself: closing it gives focus back to the button that opened it.
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

    /** What the pinned card on Where It Runs was last asked to preview. */
    private var previewed: NovaPlayInPreview? = NovaPlayInPreview("unset", "")

    private fun host(picker: NovaPlaySetupModePickerState, withCard: Boolean = false): NovaTestKeys {
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
                    is PlaySetupPage.PlayIn -> if (withCard) {
                        NovaPlayInPage(page, card = { choice -> previewed = choice })
                    } else {
                        NovaPlayInPage(page)
                    }
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
    fun wherItRunsCardPreviewsTheFocusedModeAsEveryOtherPageDoes() {
        // N19: the card said "If you choose" on an option page and never here.
        val keys = host(picker(), withCard = true)
        rule.onNode(hasText("Virtual Display")).assertIsFocused()
        assertEquals("the current mode previews nothing", null, previewed)

        keys.press(NovaTestKeys.UP)
        keys.press(NovaTestKeys.UP)
        rule.onNode(hasText("Headless")).assertIsFocused()
        // Its own line, what choosing it would run, not the current mode's plan under its name.
        assertEquals(NovaPlayInPreview("Headless", "Headless, in full."), previewed)

        keys.press(NovaTestKeys.DOWN)
        rule.onNode(hasText("GPU Native")).assertIsFocused()
        assertEquals("a mode the host will not take previews nothing", null, previewed)
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

        rule.onNode(hasText("Host Default")).assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        keys.press(NovaTestKeys.CENTER)

        assertEquals(1, hostDefaultPicks)
        assertEquals(1, state.depth)
    }

    /** The mockup's Resolution page: This Device, 2x under a PyroWave bitrate, and 3x this device cannot decode. */
    private fun resolutionPage(): NovaTestKeys {
        val bands = listOf(
            NovaPlaySetupBand(
                null,
                listOf(
                    NovaPlaySetupOption(
                        label = "This Device", value = "1920\u00d71080", consequence = "Matches this screen",
                        current = true, recommended = true, onSelect = { picked += "device" },
                    ),
                    NovaPlaySetupOption(
                        label = "2x", value = "3840\u00d72160", consequence = "PyroWave needs about 800 Mbps at this size.",
                        warning = true, onSelect = { picked += "2x" },
                        preview = NovaPlaySetupPreview(NovaPlaySetupPreviewPart.SIZE, "3840\u00d72160", limit = "Limited by bitrate"),
                    ),
                    NovaPlaySetupOption(
                        label = "3x", value = "5760\u00d73240", consequence = "Too big for this device to decode", enabled = false,
                    ),
                ),
            ),
        )
        state.open(PlaySetupPage.Root("Play Setup"), NovaEdge.End)
        state.push(PlaySetupPage.Options(title = "Resolution", row = NovaPlaySetupRow.RESOLUTION, bands = { bands }, footer = "Sizes come from the host presets."))
        return rule.setPanelContent {
            NovaPageStackHost(state = state) { page ->
                when (page) {
                    is PlaySetupPage.Options -> NovaPlaySetupOptionsPage(page) { focused ->
                        val preview = focused?.takeIf { !it.current }?.preview
                        NovaPlaySetupPlanCard(
                            title = if (preview != null) "If you choose " + focused.label else "What Will Happen",
                            value = "Private Stream",
                            line = "1920\u00d71080 at 120 FPS",
                            limit = preview?.limit.orEmpty(),
                        )
                    }
                    else -> NovaRow(title = "Setup rows", onClick = {}, modifier = Modifier.novaInitialFocus())
                }
            }
        }
    }

    @Test
    fun anOptionsPageOpensOnTheCurrentOptionAndOneAPicksAndPops() {
        val keys = resolutionPage()

        rule.onNode(hasText("This Device")).assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onAllNodesWithTag(NovaCurrentMarkTag, useUnmergedTree = true).assertCountEquals(1)
        rule.onNode(hasText("Recommended \u00b7 Matches this screen")).assertExists()
        rule.onNode(hasText("Sizes come from the host presets.")).assertExists()

        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.CENTER)

        assertEquals(listOf("2x"), picked)
        assertEquals("the page popped back to the rows", 1, state.depth)
    }

    @Test
    fun thePinnedCardPreviewsTheOptionUnderTheCursorAndStaysOffTheDpad() {
        val keys = resolutionPage()
        rule.onNode(hasText("What Will Happen")).assertExists()

        keys.press(NovaTestKeys.DOWN)
        rule.onNode(hasText("2x")).assertIsFocused()
        rule.onNode(hasText("If you choose 2x")).assertExists()
        rule.onNode(hasText("Limited by bitrate")).assertExists()

        keys.press(NovaTestKeys.UP)
        keys.press(NovaTestKeys.UP)
        rule.onNode(hasText("This Device")).assertIsFocused()
        rule.onNode(hasText("What Will Happen")).assertExists()
    }

    @Test
    fun aSizeThisDeviceCannotTakeStaysListedWithItsReasonAndFocusPassesOverIt() {
        val keys = resolutionPage()
        rule.onNode(hasText("Not available \u00b7 Too big for this device to decode")).assertExists()

        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.DOWN)
        rule.onNode(hasText("2x")).assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals(listOf("2x"), picked)
    }

    @Test
    fun closingThePanelGivesFocusBackToTheButtonThatOpenedIt() {
        // As game detail draws it: the Overview is out of reach while the panel covers it, so the
        // element that held focus goes with the panel, and focus would otherwise fall to nothing.
        val button = FocusRequester()
        val keys = rule.setPanelContent {
            Box(Modifier.fillMaxSize()) {
                // Launch comes first, as it does on the Overview, so a focus that fell to whatever
                // is first could not pass for one that was given back.
                Column {
                    NovaRow(
                        title = "Launch",
                        onClick = {},
                        modifier = Modifier.testTag("launch").focusProperties { canFocus = !state.isOpen },
                    )
                    NovaRow(
                        title = "Open Play Setup",
                        onClick = { state.open(PlaySetupPage.Root("Play Setup"), NovaEdge.End, NovaFocusReturn.Compose(button)) },
                        modifier = Modifier
                            .testTag("play-setup-button")
                            .focusRequester(button)
                            .focusProperties { canFocus = !state.isOpen },
                    )
                }
                NovaPlaySetupPanel(panel = state, onClose = { state.close() }) {
                    NovaRow(title = "Setup rows", onClick = {}, modifier = Modifier.novaInitialFocus())
                }
            }
        }
        rule.runOnIdle { button.requestFocus() }
        rule.onNodeWithTag("play-setup-button").assertIsFocused()

        keys.press(NovaTestKeys.CENTER)
        rule.onNode(hasText("Setup rows")).assertIsFocused()

        keys.back()
        rule.waitForIdle()
        assertTrue("B at the root closes Play Setup", !state.isOpen)
        rule.onNodeWithTag("play-setup-button").assertIsFocused()
    }
}
