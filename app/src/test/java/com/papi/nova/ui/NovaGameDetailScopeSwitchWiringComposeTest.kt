package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Play Setup's last row switches This Game and Every Game where a remote can reach it (C01). Its
 * own test gave the root page a lambda of its own; here the game page wires it, as the activity
 * composes it: the row flips the scope the page was given, both ways, and a Space game, which has
 * one subject only, has no such row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w833dp-h468dp")
class NovaGameDetailScopeSwitchWiringComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var scope by mutableStateOf(NovaPlaySetupScope.THIS_GAME)
    private val selected = mutableListOf<NovaPlaySetupScope>()

    private fun row(row: NovaPlaySetupRow) = NovaPlaySetupRowState(
        row = row,
        label = row.name,
        caption = "",
        value = "",
        options = emptyList(),
        opensPage = true,
    )

    private fun show(space: PolarisGame.SpaceContext?) {
        val uiState = NovaGameDetailUiState.from(
            game = PolarisGame(id = "game-1", name = "Control", source = "steam", launcherSource = "steam", space = space),
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(),
            profilePreference = "auto",
        )
        val panel = NovaPanelState().apply { open(PlaySetupPage.Root("Play Setup"), NovaEdge.End) }
        rule.setPanelContent {
            NovaGameDetailContentUnderTest(
                uiState = uiState,
                playSetupPanel = panel,
                playSetupRows = listOf(NovaPlaySetupRow.RESOLUTION, NovaPlaySetupRow.VIDEO_CODEC).map(::row),
                playSetupScope = scope,
                onPlaySetupScopeSelected = {
                    selected += it
                    scope = it
                },
                // Every Game shows once the host's own plan has come.
                hostPlaySetupPlan = NovaPlaySetupPlan(mode = "Private Stream", lines = listOf("1920x1080"), facts = emptyList()),
                destination = NovaGameDetailDestination.PLAY_SETUP,
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun theGamePagesSwitchRowFlipsTheScopeItWasGivenBothWays() {
        show(space = null)
        val switch = rule.onNodeWithTag(NOVA_PLAY_SETUP_SWITCH_TAG)
        switch.assertTextContains(context.getString(R.string.nova_play_setup_edit_every_game))
        switch.performClick()
        rule.waitForIdle()
        assertEquals(listOf(NovaPlaySetupScope.EVERY_GAME), selected)
        rule.onNodeWithTag(NOVA_PLAY_SETUP_SWITCH_TAG).assertTextContains(context.getString(R.string.nova_play_setup_edit_this_game))

        rule.onNodeWithTag(NOVA_PLAY_SETUP_SWITCH_TAG).performClick()
        rule.waitForIdle()
        assertEquals(listOf(NovaPlaySetupScope.EVERY_GAME, NovaPlaySetupScope.THIS_GAME), selected)
    }

    @Test
    fun aSpaceGameHasNoSwitchRow() {
        show(space = PolarisGame.SpaceContext(id = "living-room", name = "Living Room", target = "control"))
        rule.onNodeWithTag(NOVA_PLAY_SETUP_SWITCH_TAG).assertDoesNotExist()
        assertEquals(emptyList<NovaPlaySetupScope>(), selected)
    }
}
