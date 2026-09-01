package com.papi.nova.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.papi.nova.ui.compose.NovaComposeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovaPlaySetupModePickerComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun disabledCardConsumesControllerActivationWithoutPicking() {
        val picked = mutableListOf<String>()
        val escapedActivationKeys = mutableListOf<Key>()
        val activationKeys = listOf(
            Key.ButtonA,
            Key.DirectionCenter,
            Key.Enter,
            Key.NumPadEnter,
            Key.Spacebar,
        )
        val detail = "Set by the host. This mode cannot be chosen for one session."

        composeRule.setContent {
            NovaComposeTheme {
                Box(
                    modifier = Modifier.onKeyEvent { event ->
                        if (event.key in activationKeys) {
                            escapedActivationKeys += event.key
                            true
                        } else {
                            false
                        }
                    },
                ) {
                    NovaPlaySetupModePicker(
                        state = NovaPlaySetupModePickerState(
                            title = "Where It Runs",
                            hostDefaultLabel = null,
                            hostDefaultCurrent = false,
                            choices = listOf(
                                NovaPlaySetupModeChoice(
                                    id = "headless_stream",
                                    label = "Headless Dongle",
                                    detail = detail,
                                    group = "host",
                                    current = false,
                                    active = false,
                                    enabled = false,
                                ),
                            ),
                        ),
                        onPick = { picked += it },
                        onPickHostDefault = null,
                    )
                }
            }
        }

        val disabledCard = composeRule.onNodeWithContentDescription("Headless Dongle. $detail")
        disabledCard.performSemanticsAction(SemanticsActions.RequestFocus)
        disabledCard.assertIsFocused()

        activationKeys.forEach { key ->
            disabledCard.performKeyInput {
                keyDown(key)
                keyUp(key)
            }
        }

        composeRule.runOnIdle {
            assertTrue("a disabled card must never call onPick", picked.isEmpty())
            assertTrue(
                "controller activation must not escape a disabled card",
                escapedActivationKeys.isEmpty(),
            )
        }
        disabledCard.assertIsFocused()
    }

    @Test
    fun hostDefaultOnlyCardOpensHostSettingsWithoutSelectingTheMode() {
        val picked = mutableListOf<String>()
        var configureRequests = 0
        val detail = "Headless Dongle rearranges physical displays, so a game cannot turn it on for one launch."

        composeRule.setContent {
            NovaComposeTheme {
                NovaPlaySetupModePicker(
                    state = NovaPlaySetupModePickerState(
                        title = "Where It Runs",
                        hostDefaultLabel = null,
                        hostDefaultCurrent = false,
                        choices = listOf(
                            NovaPlaySetupModeChoice(
                                id = "headless_dongle",
                                label = "Headless Dongle",
                                detail = detail,
                                group = "host",
                                current = false,
                                active = false,
                                enabled = false,
                                hostDefaultOnly = true,
                            ),
                        ),
                    ),
                    onPick = { picked += it },
                    onPickHostDefault = null,
                    onConfigureHost = { configureRequests += 1 },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(
                "Headless Dongle. Host default only. $detail Open Polaris Settings.",
            )
            .performClick()

        composeRule.runOnIdle {
            assertTrue("host-only card must not select a per-game mode", picked.isEmpty())
            assertTrue("host-only card should open host settings", configureRequests == 1)
        }
    }
}
