package com.papi.nova.preferences

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.utils.AndroidDisplayRolePlan
import com.papi.nova.utils.AndroidStreamDisplayTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NovaDisplayRoleComposerComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun actionsWrapWithoutOverlapAtLargeFontScaleAndCompactWidth() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val roleState = roleState()

        composeRule.setContent {
            NovaComposeTheme {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                    Box(
                        modifier = Modifier
                            .width(220.dp)
                            .testTag("actions-root")
                    ) {
                        NovaDisplayRoleComposerActions(
                            roleState = roleState,
                            onSwap = {},
                            onApply = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }

        val labels = listOf(
            context.getString(R.string.display_role_swap),
            context.getString(R.string.display_role_cancel),
            context.getString(R.string.display_role_apply),
        )
        val rootBounds = composeRule.onNodeWithTag("actions-root")
            .fetchSemanticsNode().boundsInRoot
        val actionBounds = labels.map { label ->
            composeRule.onNodeWithText(label)
                .assertIsDisplayed()
                .assertHasClickAction()
                .fetchSemanticsNode().boundsInRoot
        }

        actionBounds.forEach { bounds ->
            assertTrue(bounds.left >= rootBounds.left)
            assertTrue(bounds.top >= rootBounds.top)
            assertTrue(bounds.right <= rootBounds.right)
            assertTrue(bounds.bottom <= rootBounds.bottom)
        }
        for (left in actionBounds.indices) {
            for (right in left + 1 until actionBounds.size) {
                assertFalse(actionBounds[left].overlaps(actionBounds[right]))
            }
        }
    }

    @Test
    fun followAndDisplayCardsExposeRadioSelectionAndActivationSemantics() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val display = AndroidDisplayRolePlan.DisplaySpec(
            displayId = 7,
            label = "External Test",
            width = 1920,
            height = 1080,
            refreshRateHz = 60f,
            isDefault = false,
        )

        composeRule.setContent {
            NovaComposeTheme {
                Column {
                    NovaDisplayRoleActionButton(
                        label = context.getString(R.string.display_role_follow),
                        supporting = context.getString(R.string.display_role_follow_supporting),
                        accessibilityDescription = context.getString(
                            R.string.display_role_follow_action_description
                        ),
                        selected = true,
                        enabled = true,
                        onClick = {},
                    )
                    NovaDisplayRoleCard(
                        currentRole = AndroidDisplayRolePlan.Role.AVAILABLE,
                        pendingAssignment = AndroidDisplayRolePlan.Assignment(
                            display = display,
                            role = AndroidDisplayRolePlan.Role.COMPANION,
                        ),
                        enabled = true,
                        onClick = {},
                    )
                }
            }
        }

        val selectedState = context.getString(R.string.display_role_selection_state_selected)
        val notSelectedState = context.getString(R.string.display_role_selection_state_not_selected)
        val followDescription = context.getString(R.string.display_role_follow_action_description)
        val cardDescription = context.getString(
            R.string.display_role_card_action_description,
            display.label,
            context.getString(R.string.display_role_companion),
        )

        val followNode = composeRule.onNodeWithContentDescription(followDescription)
            .assertIsSelected()
            .assertHasClickAction()
            .fetchSemanticsNode()
        val cardNode = composeRule.onNodeWithContentDescription(cardDescription)
            .assertIsNotSelected()
            .assertHasClickAction()
            .fetchSemanticsNode()

        assertEquals(selectedState, followNode.config[SemanticsProperties.StateDescription])
        assertEquals(notSelectedState, cardNode.config[SemanticsProperties.StateDescription])
    }

    private fun roleState(): AndroidDisplayRolePlan.State {
        return AndroidDisplayRolePlan.build(
            displays = listOf(
                AndroidDisplayRolePlan.DisplaySpec(
                    displayId = 0,
                    label = "Built-in",
                    width = 1920,
                    height = 1080,
                    refreshRateHz = 60f,
                    isDefault = true,
                ),
                AndroidDisplayRolePlan.DisplaySpec(
                    displayId = 7,
                    label = "External",
                    width = 1280,
                    height = 720,
                    refreshRateHz = 60f,
                    isDefault = false,
                ),
            ),
            defaultDisplayId = 0,
            currentTarget = AndroidStreamDisplayTarget.AUTO,
            pendingTarget = AndroidStreamDisplayTarget.PRIMARY,
        )
    }
}
