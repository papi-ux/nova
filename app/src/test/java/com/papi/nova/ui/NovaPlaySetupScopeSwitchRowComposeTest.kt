package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
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
 * A remote has arrows, Center and Back, and no Y, and the scope pill is not a stop on the d-pad
 * (R12). Play Setup's last row switches scope instead (C01): "Edit for Every Game" under This
 * Game's rows, "Edit for This Game" under Every Game's, keeping focus across the switch so Center
 * again comes back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPlaySetupScopeSwitchRowComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var scope by mutableStateOf(NovaPlaySetupScope.THIS_GAME)

    private fun row(row: NovaPlaySetupRow) = NovaPlaySetupRowState(
        row = row,
        label = row.name,
        caption = "What ${row.name} does",
        value = "",
        options = emptyList(),
        opensPage = true,
    )

    private val gameRows = listOf(NovaPlaySetupRow.WHERE_IT_RUNS, NovaPlaySetupRow.RESOLUTION, NovaPlaySetupRow.VIDEO_CODEC).map(::row)
    private val hostRows = listOf(NovaPlaySetupRow.HOST_DEFAULT_DISPLAY, NovaPlaySetupRow.HOST_PROFILE).map(::row)

    private fun show(): NovaTestKeys {
        val state = NovaPanelState()
        state.open(PlaySetupPage.Root("Play Setup"), com.papi.nova.ui.panel.NovaEdge.End)
        return rule.setPanelContent {
            NovaPageStackHost(state = state, onCloseRequest = {}) { page ->
                if (page is PlaySetupPage.Root) {
                    NovaPlaySetupRootPage(
                        scope = scope,
                        rows = if (scope == NovaPlaySetupScope.EVERY_GAME) hostRows else gameRows,
                        onAdvance = {},
                        setHereNote = null,
                        card = { NovaPlaySetupPlanCard(title = "What Will Happen", value = "Private Stream", line = "1920×1080", onOpen = {}) },
                        onSwitchScope = {
                            scope = if (scope == NovaPlaySetupScope.THIS_GAME) NovaPlaySetupScope.EVERY_GAME else NovaPlaySetupScope.THIS_GAME
                        },
                    )
                }
            }
        }
    }

    @Test
    fun aRemoteReachesTheSwitchUnderTheRowsAndCenterSwitchesBothWays() {
        val keys = show()
        rule.onNodeWithText("WHERE_IT_RUNS").assertIsFocused()
        repeat(gameRows.size) { keys.press(NovaTestKeys.DOWN) }
        val everyGame = context.getString(R.string.nova_play_setup_edit_every_game)
        rule.onNodeWithText(everyGame).assertIsFocused()
        val last = rule.onNodeWithText("VIDEO_CODEC").getUnclippedBoundsInRoot()
        val switch = rule.onNodeWithTag(NOVA_PLAY_SETUP_SWITCH_TAG).getUnclippedBoundsInRoot()
        assertTrue("the switch sits under every setting", switch.top >= last.bottom)

        keys.press(NovaTestKeys.CENTER)
        rule.waitForIdle()
        assertEquals(NovaPlaySetupScope.EVERY_GAME, scope)
        rule.onNodeWithText("HOST_PROFILE").assertExists()
        rule.onNodeWithText(context.getString(R.string.nova_play_setup_edit_this_game)).assertIsFocused()

        keys.press(NovaTestKeys.CENTER)
        rule.waitForIdle()
        assertEquals(NovaPlaySetupScope.THIS_GAME, scope)
        rule.onNodeWithText(everyGame).assertIsFocused()
    }
}
