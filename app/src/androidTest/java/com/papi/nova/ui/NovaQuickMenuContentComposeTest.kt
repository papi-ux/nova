package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Command Center's root page composes in a page stack, and its rows push its own pages. */
@RunWith(AndroidJUnit4::class)
class NovaQuickMenuContentComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val panel = NovaPanelState()
    private val sent = mutableListOf<String>()

    private val callbacks = NovaQuickMenuCallbacks(
        onSessionAction = { id ->
            when (id) {
                NovaQuickMenuActionId.MORE_KEYS -> panel.push(
                    CommandCenterPage.Keys(
                        "More Keys",
                        listOf(
                            CommandCenterSection(
                                null,
                                listOf(NovaMenuItem.Action(key = "esc", label = "Esc", onClick = { sent += "Esc" })),
                            ),
                        ),
                    ),
                )
                NovaQuickMenuActionId.MORE_CONTROLS -> panel.push(
                    CommandCenterPage.MoreControls(
                        "More Controls",
                        listOf(
                            CommandCenterSection(
                                "Host",
                                listOf(NovaMenuItem.Action(key = "fetch", label = "Fetch clipboard", onClick = {})),
                            ),
                        ),
                    ),
                )
                else -> Unit
            }
        },
    )

    private fun showRoot(expanded: Boolean = true) {
        val state = MutableStateFlow(NovaQuickMenuUiState.preview(rule.activity).copy(advancedExpanded = expanded))
        panel.open(CommandCenterPage.Root("Command Center"))
        rule.setContent {
            NovaComposeTheme {
                NovaPageStackHost(state = panel) { page ->
                    when (page) {
                        is CommandCenterPage.Root -> NovaQuickMenuContent(state = state, callbacks = callbacks)
                        is CommandCenterPage.Listing -> CommandCenterListingPage(page)
                        else -> Unit
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun quickMenuContentComposesInAPageStack() {
        showRoot()
        rule.onNodeWithText("End Session").assertExists()
        rule.onNodeWithText("Close").assertExists()
    }

    @Test
    fun moreKeysPushesTheKeyListOverTheRoot() {
        showRoot(expanded = false)
        rule.onNodeWithText("More Keys").performClick()
        rule.waitForIdle()
        assertEquals(2, panel.depth)
        assertTrue(panel.top is CommandCenterPage.Keys)
        rule.onNodeWithText("Esc").assertIsFocused()
    }

    @Test
    fun moreControlsPushesTheLegacyExtrasOverTheRoot() {
        showRoot(expanded = false)
        rule.onNodeWithText("More Controls").performClick()
        rule.waitForIdle()
        assertEquals(2, panel.depth)
        assertTrue(panel.top is CommandCenterPage.MoreControls)
        rule.onNodeWithText("Fetch clipboard").assertExists()
    }
}
