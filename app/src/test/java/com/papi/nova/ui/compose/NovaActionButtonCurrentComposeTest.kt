package com.papi.nova.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaCurrentMarkTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A selected NovaActionButton marks the current value the one way R9 allows, the check, rather than
 * a fill that reads as focus. The Space settings options are the screen that passes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaActionButtonCurrentComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aSelectedButtonCarriesTheCheckAndSaysCurrent() {
        rule.setContent {
            NovaComposeTheme {
                Row {
                    NovaActionButton(text = "Balanced", onClick = {}, selected = true, modifier = Modifier.testTag("current"))
                    NovaActionButton(text = "Quality", onClick = {}, modifier = Modifier.testTag("other"))
                }
            }
        }

        rule.onNodeWithTag("current")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, rule.activity.getString(R.string.nova_panel_current)))
        rule.onAllNodesWithTag(NovaCurrentMarkTag, useUnmergedTree = true).assertCountEquals(1)
        rule.onNode(hasTestTag(NovaCurrentMarkTag) and hasAnyAncestor(hasTestTag("current")), useUnmergedTree = true)
            .assertExists()
    }
}
