package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review finding 5, for a screen reader: a split's halves merge their Texts, so a description of
 * their own is read as well as the Texts, not instead of them. Round 3 described each half as its
 * label and caption joined, and the label, and a result in the caption, were said twice. Each half
 * now says its words once, and a result is announced by the caption's own Text, a polite live
 * region, and by nothing else in the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaSplitConfirmSemanticsComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val state = NovaSplitConfirmState()
    private val result = "The host kept Live Tuning On."

    private fun setUp(announce: Boolean): NovaTestKeys {
        val keys = rule.setPanelContent {
            Column {
                Box(Modifier.size(48.dp).testTag("other").focusable())
                NovaSplitConfirm(
                    label = "Live Tuning",
                    confirmLabel = "Turn Off",
                    onConfirm = {},
                    shape = NovaSplitShape.Row,
                    caption = result,
                    announceCaption = announce,
                    trailing = { Text("On") },
                    state = state,
                    tone = NovaSplitTone.Neutral,
                )
            }
        }
        rule.onNodeWithTag("other").requestFocus()
        rule.waitForIdle()
        return keys
    }

    /** The half that shows [label], as the platform sees it: the clickable node and all under it. */
    private fun half(label: String): SemanticsNode =
        rule.onNode(hasClickAction() and hasText(label, substring = true)).fetchSemanticsNode().let { merged ->
            rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().first { it.id == merged.id }
        }

    private fun SemanticsNode.subtree(): List<SemanticsNode> = listOf(this) + children.flatMap { it.subtree() }

    /** How many nodes in [node]'s subtree say [words], in a Text or a description. */
    private fun times(node: SemanticsNode, words: String): Int = node.subtree().count { each ->
        each.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == words } ||
            each.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains(words) }
    }

    @Test
    fun aRowSaysItsLabelAndItsResultOnce() {
        setUp(announce = true)
        val row = half("Live Tuning")
        assertNull("no description of its own", row.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals("the label once", 1, times(row, "Live Tuning"))
        assertEquals("the result once", 1, times(row, result))
        assertEquals("the state once", 1, times(row, "On"))
    }

    @Test
    fun onlyTheCaptionAnnouncesTheResult() {
        setUp(announce = true)
        rule.onNodeWithText(result, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        val row = half("Live Tuning")
        assertNull("the row itself is no live region", row.config.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals(
            "one live region in the row, the caption",
            1,
            row.subtree().count { it.config.getOrNull(SemanticsProperties.LiveRegion) != null },
        )
    }

    @Test
    fun aCaptionWithNothingToAnnounceIsNoLiveRegion() {
        setUp(announce = false)
        rule.onNodeWithText(result, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun armedHalvesSayTheirLabelsOnce() {
        val keys = setUp(announce = false)
        rule.onNodeWithText("Live Tuning").requestFocus()
        rule.waitForIdle()
        keys.press(NovaTestKeys.CENTER)
        rule.waitForIdle()
        listOf("Stay", "Turn Off").forEach { label ->
            val half = half(label)
            assertNull("$label has no description of its own", half.config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals("$label once", 1, times(half, label))
        }
    }
}
