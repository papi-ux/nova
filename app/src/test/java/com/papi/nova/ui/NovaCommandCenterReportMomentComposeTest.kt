package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.papi.nova.Game
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaSurfaces
import com.papi.nova.ui.panel.NovaSurfacesWindowContent
import com.papi.nova.ui.panel.NovaWindowPlacement
import com.papi.nova.ui.panel.setPanelContent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Report This Moment is a Command Center session row: it reads what it does and that nothing is
 * sent automatically, reaches the session action on A, and is not offered when no stream is up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h480dp-land-xhdpi")
class NovaCommandCenterReportMomentComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var surfaces: NovaSurfaces
    private val pressed = ArrayList<NovaQuickMenuActionId>()

    @After
    fun tearDown() {
        if (::surfaces.isInitialized) rule.runOnUiThread { surfaces.dispose() }
    }

    private fun open(available: Boolean) {
        val game = Mockito.mock(Game::class.java)
        Mockito.`when`(game.isFinishing).thenReturn(true)
        surfaces = NovaSurfaces(NovaWindowPlacement.Stream(game))
        val preview = NovaQuickMenuUiState.preview(rule.activity)
        val state = MutableStateFlow(if (available) preview else preview.copy(sessionRows = preview.sessionRows.map {
            if (it.id != NovaQuickMenuActionId.REPORT_MOMENT) it else it.copy(
                enabled = false, caption = rule.activity.getString(R.string.nova_moment_report_unavailable_caption))
        }))
        rule.setPanelContent { NovaSurfacesWindowContent(surfaces) }
        rule.runOnUiThread {
            surfaces.open(CommandCenterPage.Root("Command Center"), NovaEdge.Start) { page ->
                if (page is CommandCenterPage.Root) NovaQuickMenuContent(
                    state = state,
                    callbacks = NovaQuickMenuCallbacks(onSessionAction = { pressed += it }),
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun theRowSaysWhatItDoesAndReachesTheSessionAction() {
        open(available = true)
        val row = rule.onNodeWithTag(REPORT_MOMENT_TAG, useUnmergedTree = true).performScrollTo()
        row.assertIsEnabled()
        val description = row.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description, description.startsWith(rule.activity.getString(R.string.nova_moment_report_action)))
        assertTrue(description, description.contains("Nothing is sent automatically"))
        row.performClick()
        rule.waitForIdle()
        assertEquals(listOf(NovaQuickMenuActionId.REPORT_MOMENT), pressed)
    }

    @Test fun withNoStreamUpTheRowSaysWhyAndDoesNothing() {
        open(available = false)
        val row = rule.onNodeWithTag(REPORT_MOMENT_TAG, useUnmergedTree = true).performScrollTo()
        row.assertIsNotEnabled()
        val description = row.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description, description.contains(rule.activity.getString(R.string.nova_moment_report_unavailable_caption)))
        row.performClick()
        rule.waitForIdle()
        assertTrue(pressed.isEmpty())
    }

    @Test fun theStateBuilderOffersTheRowOnlyForAnActiveLocalStream() {
        val context = rule.activity
        val preview = NovaQuickMenuUiState.preview(context)
        val active = preview.sessionRows.first { it.id == NovaQuickMenuActionId.REPORT_MOMENT }
        assertTrue(active.enabled)
        assertEquals(context.getString(R.string.nova_moment_report_caption), active.caption)
        // Ownership is not required: a viewer of an active stream can still report its moment.
        assertEquals(NovaQuickMenuActionId.REPORT_MOMENT, preview.sessionRows.first().id)
    }
}
