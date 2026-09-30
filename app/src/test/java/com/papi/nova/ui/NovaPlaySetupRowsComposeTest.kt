package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Play Setup's rows as a pad and a finger meet them: a row that holds one of a set of values
 * changes in place, Left and Right stepping through it without moving focus (R1), and a tap acts
 * on the first press (R12). A row whose value carries › opens its page. The focused row says what
 * its value means inside its own tile, and the hint bar says what A does there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPlaySetupRowsComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val picked = mutableListOf<String>()
    private val advanced = mutableListOf<NovaPlaySetupRow>()

    private fun option(label: String, current: Boolean = false, enabled: Boolean = true, short: String = "") = NovaPlaySetupOption(
        label = label,
        consequence = "$label, in full.",
        current = current,
        enabled = enabled,
        onSelect = if (enabled) ({ picked += label }) else null,
        short = short,
    )

    private fun row(
        row: NovaPlaySetupRow,
        options: List<NovaPlaySetupOption>,
        value: String = options.firstOrNull { it.current }?.label.orEmpty(),
        ordered: Boolean = false,
        opensPage: Boolean = false,
        overridden: Boolean = false,
        unit: String = "",
    ) = NovaPlaySetupRowState(
        row = row,
        label = row.name,
        caption = "What ${row.name} does",
        value = value,
        options = options,
        ordered = ordered,
        opensPage = opensPage,
        overridden = overridden,
        unit = unit,
    )

    private fun show(vararg rows: NovaPlaySetupRowState, setHereNote: String? = null): NovaTestKeys {
        val keys = rule.setPanelContent {
            Column {
                rows.forEach { state ->
                    NovaPlaySetupSettingRow(
                        state = state,
                        onAdvance = { advanced += it },
                        setHereNote = setHereNote,
                        modifier = Modifier.testTag(state.row.name),
                    )
                }
            }
        }
        rule.onNodeWithTag(rows.first().row.name).requestFocus()
        rule.waitForIdle()
        return keys
    }

    @Test
    fun leftAndRightChangeTheValueInPlaceAndFocusStaysOnTheRow() {
        val keys = show(row(NovaPlaySetupRow.TUNING, listOf(option("Auto", current = true), option("Quality"), option("Stability"))))

        keys.press(NovaTestKeys.RIGHT)
        rule.onNodeWithTag(NovaPlaySetupRow.TUNING.name).assertIsFocused()
        keys.press(NovaTestKeys.LEFT)
        rule.onNodeWithTag(NovaPlaySetupRow.TUNING.name).assertIsFocused()

        // The row is a snapshot of Auto, so each step goes from Auto: Right to Quality, Left round to Stability.
        assertEquals(listOf("Quality", "Stability"), picked)
    }

    @Test
    fun aScaleStopsAtItsEndsAndAOptionTheHostWillNotTakeIsSteppedOver() {
        val keys = show(
            row(
                NovaPlaySetupRow.FRAME_RATE,
                listOf(option("Auto"), option("30 FPS", enabled = false), option("60 FPS"), option("120 FPS", current = true)),
                value = "120 FPS",
                ordered = true,
            ),
        )
        keys.press(NovaTestKeys.RIGHT)
        assertEquals("an ordered row stops at its end", emptyList<String>(), picked)

        keys.press(NovaTestKeys.LEFT)
        assertEquals(listOf("60 FPS"), picked)
    }

    @Test
    fun aFocusedScaleShowsEveryStopAndATapOnOneChoosesIt() {
        // The mockup's Frame Rate strip: Auto 30 60 90 120 FPS, where the legend drew four empty boxes.
        show(
            row(
                NovaPlaySetupRow.FRAME_RATE,
                listOf(option("Auto", current = true, short = "Auto"), option("60 FPS", short = "60"), option("120 FPS", short = "120")),
                value = "60 FPS",
                ordered = true,
                unit = "FPS",
            ),
        )
        listOf("Auto", "60", "120", "FPS").forEach { stop ->
            rule.onNodeWithText(stop, useUnmergedTree = true).assertExists()
        }
        rule.onNodeWithText("120", useUnmergedTree = true).performClick()
        assertEquals(listOf("120 FPS"), picked)
    }

    @Test
    fun aTapChangesTheRowAtOnce() {
        show(row(NovaPlaySetupRow.STEAM_LAUNCH, listOf(option("Direct", current = true), option("Big Picture"))))

        rule.onNodeWithTag(NovaPlaySetupRow.STEAM_LAUNCH.name).performClick()

        assertEquals("the first tap acts, as A does: one step forward", listOf("Big Picture"), picked)
    }

    @Test
    fun theRowSaysItsValueAndMarksNothingButTheValue() {
        show(row(NovaPlaySetupRow.FACE_BUTTONS, listOf(option("App setting", current = true), option("Labels"), option("Positions"))))

        rule.onNodeWithTag(NovaPlaySetupRow.FACE_BUTTONS.name)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "App setting"))
    }

    @Test
    fun theFocusedRowSaysWhatItsValueMeansAndWhetherItWasSetForThisGame() {
        val keys = show(
            row(NovaPlaySetupRow.FACE_BUTTONS, listOf(option("App setting"), option("Labels", current = true)), overridden = true),
            row(NovaPlaySetupRow.STEAM_LAUNCH, listOf(option("Direct", current = true), option("Big Picture"))),
            setHereNote = "Set for this game",
        )
        rule.onNodeWithText("Set for this game · What FACE_BUTTONS does", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("What STEAM_LAUNCH does", useUnmergedTree = true).assertDoesNotExist()

        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithTag(NovaPlaySetupRow.STEAM_LAUNCH.name).assertIsFocused()
        rule.onNodeWithText("What STEAM_LAUNCH does", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Set for this game · What FACE_BUTTONS does", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aRowWhoseChoicesOutgrowItOpensTheirPage() {
        val keys = show(
            row(
                NovaPlaySetupRow.WHERE_IT_RUNS,
                listOf(option("Headless", current = true), option("Virtual Display"), option("GPU Native")),
                opensPage = true,
            ),
        )
        keys.press(NovaTestKeys.CENTER)

        assertEquals(listOf(NovaPlaySetupRow.WHERE_IT_RUNS), advanced)
        assertEquals("the page makes the choice, not the row", emptyList<String>(), picked)
        rule.onNodeWithTag(com.papi.nova.ui.panel.NovaChevronOpensTag, useUnmergedTree = true).assertExists()
    }

    @Test
    fun aRowOfVerbsActsOnceOnAAndNeverStepsThroughThem() {
        // The host profile's options are four different verbs. Nothing is current among them, so the
        // row never steps through them: A asks the owner, which opens their page.
        val verbs = listOf(option("Match Nova"), option("Send Nova"), option("Use Polaris"), option("Clear"))
        val keys = show(row(NovaPlaySetupRow.HOST_PROFILE, verbs, value = "Matched"))

        keys.press(NovaTestKeys.CENTER)
        keys.press(NovaTestKeys.RIGHT)

        assertEquals(listOf(NovaPlaySetupRow.HOST_PROFILE), advanced)
        assertEquals(emptyList<String>(), picked)
    }

    @Test
    fun theHintBarSaysChangeAndNextWhileARowChangesInPlace() {
        val state = NovaPanelState().apply { open(PlaySetupPage.Root("Play Setup"), NovaEdge.End) }
        val keys = rule.setPanelContent {
            NovaPlaySetupPanel(panel = state, onClose = {}) {
                Column {
                    NovaPlaySetupSettingRow(
                        state = row(NovaPlaySetupRow.RESOLUTION, listOf(option("This Device", current = true)), opensPage = true),
                        onAdvance = { advanced += it },
                        modifier = Modifier.testTag("page-row").novaInitialFocus(),
                    )
                    NovaPlaySetupSettingRow(
                        state = row(NovaPlaySetupRow.STEAM_LAUNCH, listOf(option("Direct", current = true), option("Big Picture"))),
                        onAdvance = { advanced += it },
                        modifier = Modifier.testTag("in-place-row"),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("page-row").assertIsFocused()
        rule.onNodeWithText("Select", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Change", useUnmergedTree = true).assertDoesNotExist()

        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithTag("in-place-row").assertIsFocused()
        rule.onNodeWithText("Change", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Next", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Select", useUnmergedTree = true).assertDoesNotExist()

        keys.press(NovaTestKeys.UP)
        rule.onNodeWithText("Select", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Change", useUnmergedTree = true).assertDoesNotExist()
    }
}
