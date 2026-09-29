package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakeMacAddressEditorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val panel = NovaPanelState()
    private fun host(): NovaTestKeys = rule.setPanelContent {
        NovaPageStackHost(state = panel, onCloseRequest = panel::close) {}
    }
    private fun save() = rule.onNodeWithText(rule.activity.getString(R.string.save)).performClick()

    @Test fun invalidInputStaysOpenWithoutSavingAndFailedSaveKeepsTheDraft() {
        var attempts = 0
        var complete: ((Boolean) -> Unit)? = null
        val details = ComputerDetails().apply { manualWakeMacAddress = "02:11:22:33:44:55" }
        showWakeMacAddressEditor(rule.activity, details, panel) { _, callback -> attempts++; complete = callback }
        val keys = host()
        rule.onNodeWithText(details.manualWakeMacAddress!!).assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        rule.onNode(hasSetTextAction()).performTextReplacement("00:00:00:00:00:00")
        keys.press(NovaTestKeys.DOWN)
        save()
        rule.onNodeWithText(rule.activity.getString(R.string.wol_address_invalid)).assertExists()
        assertEquals(0, attempts)
        keys.press(NovaTestKeys.UP)
        keys.press(NovaTestKeys.CENTER)
        rule.onNode(hasSetTextAction()).performTextReplacement("02:22:33:44:55:66")
        keys.press(NovaTestKeys.DOWN)
        save()
        assertEquals(1, attempts)
        assertTrue(panel.top is NovaCommonPage.Busy)
        keys.press(NovaTestKeys.CENTER)
        keys.back()
        assertTrue("A and B cannot repeat or abandon the pending write", panel.top is NovaCommonPage.Busy)
        assertEquals(1, attempts)
        rule.runOnIdle { complete!!(false) }
        rule.onNodeWithText("02:22:33:44:55:66").assertIsFocused()
        rule.onNodeWithText(rule.activity.getString(R.string.wol_address_save_failed)).assertExists()
        save()
        assertEquals(2, attempts)
        rule.runOnIdle { complete!!(true) }
        assertFalse(panel.isOpen)
    }

    @Test fun clearingFieldRequestsAutomaticAddressAndOnlySuccessfulSaveCloses() {
        var savedValue: String? = null
        var complete: ((Boolean) -> Unit)? = null
        showWakeMacAddressEditor(rule.activity, ComputerDetails(), panel) { value, callback ->
            savedValue = value; complete = callback
        }
        val keys = host()
        keys.press(NovaTestKeys.CENTER)
        rule.onNode(hasSetTextAction()).performTextReplacement("")
        keys.press(NovaTestKeys.DOWN)
        save()
        assertEquals("", savedValue)
        assertTrue(panel.isOpen)
        rule.runOnIdle { complete!!(true) }
        assertFalse(panel.isOpen)
    }

    @Test fun anOldCompletionCannotCloseOrRewriteANewerEditor() {
        var complete: ((Boolean) -> Unit)? = null
        showWakeMacAddressEditor(rule.activity, ComputerDetails(), panel) { _, callback -> complete = callback }
        host()
        save()
        val old = complete!!
        rule.runOnIdle {
            panel.close()
            showWakeMacAddressEditor(rule.activity, ComputerDetails().apply {
                manualWakeMacAddress = "02:99:88:77:66:55"
            }, panel) { _, _ -> fail("The new editor must not submit itself") }
            old(true)
            old(false)
        }
        rule.onNodeWithText("02:99:88:77:66:55").assertIsFocused()
        assertTrue(panel.top is NovaCommonPage.Form)
    }
}
