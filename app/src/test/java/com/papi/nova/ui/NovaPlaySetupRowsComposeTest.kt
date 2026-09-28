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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
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
 * on the first press (R12). Whatever changes a row, or focuses it, points the legend at it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPlaySetupRowsComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val picked = mutableListOf<String>()
    private val explained = mutableListOf<NovaPlaySetupRow>()
    private val advanced = mutableListOf<NovaPlaySetupRow>()

    private fun option(label: String, current: Boolean = false, enabled: Boolean = true) = NovaPlaySetupOption(
        label = label,
        consequence = "$label, in full.",
        current = current,
        enabled = enabled,
        onSelect = if (enabled) ({ picked += label }) else null,
    )

    private fun row(
        row: NovaPlaySetupRow,
        options: List<NovaPlaySetupOption>,
        value: String = options.firstOrNull { it.current }?.label.orEmpty(),
        ordered: Boolean = false,
        opensPage: Boolean = false,
    ) = NovaPlaySetupRowState(
        row = row,
        label = row.name,
        caption = "What ${row.name} does",
        value = value,
        stripTitle = "If you changed ${row.name}",
        options = options,
        ordered = ordered,
        opensPage = opensPage,
    )

    private fun show(vararg rows: NovaPlaySetupRowState): NovaTestKeys {
        val keys = rule.setPanelContent {
            Column {
                rows.forEach { state ->
                    NovaPlaySetupSettingRow(
                        state = state,
                        onExplain = { explained += it },
                        onAdvance = { advanced += it },
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
        assertEquals("focus and every change point the legend at the row", NovaPlaySetupRow.TUNING, explained.last())
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
    fun aTapChangesTheRowAtOnce() {
        show(row(NovaPlaySetupRow.STEAM_LAUNCH, listOf(option("Direct", current = true), option("Big Picture"))))

        rule.onNodeWithTag(NovaPlaySetupRow.STEAM_LAUNCH.name).performClick()

        assertEquals("the first tap acts, as A does: one step forward", listOf("Big Picture"), picked)
        assertEquals(NovaPlaySetupRow.STEAM_LAUNCH, explained.last())
    }

    @Test
    fun theRowSaysItsValueAndMarksNothingButTheValue() {
        show(row(NovaPlaySetupRow.FACE_BUTTONS, listOf(option("App setting", current = true), option("Labels"), option("Positions"))))

        rule.onNodeWithTag(NovaPlaySetupRow.FACE_BUTTONS.name)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "App setting"))
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
    }

    @Test
    fun aRowOfVerbsActsOnceOnA() {
        // The host profile's four cards are four different verbs: A performs the one its caption
        // names, and the legend under the rows offers the rest to touch.
        val verbs = listOf(option("Match Nova"), option("Send Nova"), option("Use Polaris"), option("Clear"))
        val keys = show(row(NovaPlaySetupRow.HOST_PROFILE, verbs, value = "Matched"))

        keys.press(NovaTestKeys.CENTER)

        assertEquals(listOf(NovaPlaySetupRow.HOST_PROFILE), advanced)
        assertEquals(emptyList<String>(), picked)
    }
}
