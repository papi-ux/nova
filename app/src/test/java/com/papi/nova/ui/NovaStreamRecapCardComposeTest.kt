package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaStreamRecap
import com.papi.nova.diagnostics.NovaStreamRecap.End
import com.papi.nova.diagnostics.NovaStreamRecapStore
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.launch
import com.papi.nova.diagnostics.NovaStreamRecapTest.Companion.sample
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Library's Last Stream card: it shows what ended, never takes focus on its own, and View and
 * Dismiss are its only actions. Dismiss through the store removes that recap only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h480dp-land-xhdpi")
class NovaStreamRecapCardComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val store = NovaStreamRecapStore()
    private var views = 0
    private var dismissals = 0

    private fun published(): NovaStreamRecap.Recap {
        val acc = NovaStreamRecap.Accumulator(store.begin())
        acc.launch(launch(host = "host-entry-1"))
        acc.connected(1_000L)
        acc.accept(sample(2_000L, expected = 60), 2_000L)
        acc.accept(sample(3_000L, expected = 120), 3_000L)
        val recap = acc.finish(End.END_REQUESTED, null, 4_000L, "2026-10-08T14:00:00Z")!!
        assertTrue(store.publish(recap))
        return recap
    }

    private fun show(recap: NovaStreamRecap.Recap) {
        rule.setPanelContent {
            Column {
                NovaStreamRecapCard(
                    recap = recap,
                    onView = { views++ },
                    onDismiss = {
                        dismissals++
                        store.dismiss(recap.generation)
                    },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun theCardSaysWhatEndedAndNeverTakesFocusOnItsOwn() {
        show(published())
        val card = rule.onNodeWithTag(NOVA_STREAM_RECAP_CARD_TAG, useUnmergedTree = true)
        card.assertExists()
        rule.onNodeWithTag(NOVA_STREAM_RECAP_VIEW_TAG).assertIsNotFocused()
        rule.onNodeWithTag(NOVA_STREAM_RECAP_DISMISS_TAG).assertIsNotFocused()
        val description = rule.onNodeWithTag(NOVA_STREAM_RECAP_VIEW_TAG).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString()
        val activity = rule.activity
        assertTrue(description, description.startsWith(activity.getString(R.string.nova_recap_card_title)))
        assertTrue(description, description.contains("2 client samples"))
        assertTrue(description, description.contains(activity.getString(R.string.nova_recap_short_end_requested)))
    }

    @Test fun viewOpensTheRecapAndChangesNothing() {
        val recap = published()
        show(recap)
        rule.onNodeWithTag(NOVA_STREAM_RECAP_VIEW_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, views)
        assertEquals(0, dismissals)
        assertEquals(recap.generation, store.forHost("host-entry-1")!!.generation)
    }

    @Test fun dismissRemovesThatRecapFromTheStore() {
        show(published())
        rule.onNodeWithTag(NOVA_STREAM_RECAP_DISMISS_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, dismissals)
        assertEquals(0, views)
        assertNull(store.forHost("host-entry-1"))
    }
}
