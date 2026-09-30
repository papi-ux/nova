package com.papi.nova.ui

import android.view.InputDevice
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.panel.NovaRemoteInput
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The library's chrome at rest: a search in force says so above the grid and clears in place
 * (N12), the hint bar is the panels' own (N13), More Filters lists a name once (N16), and a remote
 * is named by its own keys (C04).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaLibraryChromeRestTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aSearchInForceSaysSoAndAClearsIt() {
        var cleared = 0
        rule.setPanelContent {
            Box { NovaLibrarySearchChip(query = "con", resultCount = 2, onClear = { cleared++ }) }
        }
        rule.onNodeWithText("Search: con · 2 shown", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Search: con · 2 shown", useUnmergedTree = true).performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun theHintRowSaysEveryHintOnceOnThePanelsOwnBar() {
        val hints = listOf(NovaControllerHint("A", "Select"), NovaControllerHint("B", "Back"))
        rule.setPanelContent {
            NovaLibraryCinematicControllerHints(
                hints = hints,
                semanticsDescription = "A Select · B Back · X Options",
                compact = true,
            )
        }
        // The row's words include the hints it leaves out; the bar's own are not said again.
        assertEquals(1, rule.onAllNodesWithContentDescription("A Select", substring = true).fetchSemanticsNodes().size)
        val chrome = File("src/main/java/com/papi/nova/ui/NovaLibraryCinematicChrome.kt").readText()
        assertTrue(chrome.contains("NovaPanelHintBar(hints = hints)"))
        assertFalse("no second key chip style beside the panels' own", chrome.contains("CircleShape"))
    }

    private fun game(id: String, category: String = "", genres: List<String> = emptyList()) =
        PolarisGame(id = id, name = id, source = "steam", category = category, genres = genres)

    private fun label(category: String) = if (category == "fast_action") "Action" else category

    @Test
    fun moreFiltersListsANameOnceWhereOneCoversTheOther() {
        // Every fast_action game is also in the Action genre: Action is listed once, as the genre.
        val covered = listOf(
            game("a", "fast_action", listOf("Action")),
            game("b", "", listOf("Action", "Indie")),
        )
        val entries = NovaLibraryUiStateMapper.moreFilterEntries(covered, ::label) { it }
        assertEquals(listOf(NovaLibraryMoreFilter.Genre("Action"), NovaLibraryMoreFilter.Genre("Indie")), entries)

        // Each holds a game the other lacks: both stay, their captions telling them apart.
        val apart = listOf(game("a", "fast_action"), game("b", "", listOf("Action")))
        assertEquals(
            listOf(NovaLibraryMoreFilter.Category("fast_action"), NovaLibraryMoreFilter.Genre("Action")),
            NovaLibraryUiStateMapper.moreFilterEntries(apart, ::label) { it },
        )
    }

    @Test
    fun aRemoteIsToldFromAController() {
        assertTrue(NovaRemoteInput.isRemote(InputDevice.SOURCE_DPAD or InputDevice.SOURCE_KEYBOARD))
        assertTrue(NovaRemoteInput.isRemote(InputDevice.SOURCE_KEYBOARD))
        assertFalse(NovaRemoteInput.isRemote(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD or InputDevice.SOURCE_KEYBOARD))
        assertFalse(NovaRemoteInput.isRemote(InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD))
        assertFalse(NovaRemoteInput.isRemote(InputDevice.SOURCE_TOUCHSCREEN))
        val activity = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
        assertTrue(
            "the bar named X, Y and L1/R1 on a TV remote (C04)",
            activity.contains("val controllerHints = if (lastInputRemote) novaLibraryRemoteHints() else novaLibraryControllerHints(isLandscape)"),
        )
    }
}
