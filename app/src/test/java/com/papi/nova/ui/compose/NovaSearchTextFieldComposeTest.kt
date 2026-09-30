package com.papi.nova.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Settings search field, laid out as Settings lays it out: the field in the header, composed
 * before the pane's page host, whose root answers B by leaving Settings. A opens the field on its
 * release, and B while it is open closes the field and nothing else (R4, R8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaSearchTextFieldComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val pane = NovaPanelState()
    private var leftSettings = 0

    private data class RowsPage(override val key: String = "rows", override val title: String = "Stream") : NovaPage

    private val editable = SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)
    private val readOnly = SemanticsMatcher.keyNotDefined(SemanticsActions.SetText)

    private fun settings(): NovaTestKeys {
        pane.open(RowsPage())
        val keys = rule.setPanelContent {
            Column {
                var query by remember { mutableStateOf("") }
                NovaSearchTextField(
                    value = query,
                    onValueChange = { query = it },
                    contentDescription = "Search settings",
                    modifier = Modifier.testTag("search"),
                ) { field -> field() }
                NovaPageStackHost(state = pane, containFocus = false, onCloseRequest = { leftSettings++ }) {
                    NovaRow(title = "Frame rate", onClick = {})
                }
            }
        }
        rule.onNodeWithTag("search").requestFocus()
        rule.waitForIdle()
        return keys
    }

    @Test
    fun aOpensTheFieldOnItsReleaseAndNotOnItsPress() {
        val keys = settings()
        rule.onNodeWithTag("search").assert(readOnly)

        keys.down(NovaTestKeys.CENTER)
        rule.onNodeWithTag("search").assert(readOnly)
        keys.up(NovaTestKeys.CENTER)
        rule.onNodeWithTag("search").assert(editable)
    }

    @Test
    fun bWhileTypingClosesTheFieldAndSettingsStaysOpen() {
        val keys = settings()
        keys.press(NovaTestKeys.CENTER)
        rule.onNodeWithTag("search").assert(editable)

        keys.back()
        rule.onNodeWithTag("search").assert(readOnly)
        assertEquals("the first B only puts the keyboard away", 0, leftSettings)

        keys.back()
        assertEquals("with the field closed, B is the screen's again", 1, leftSettings)
    }
}
