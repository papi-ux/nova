package com.papi.nova.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.papi.nova.ui.compose.NovaComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NovaHudPerformanceLayoutComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun performancePrimaryAndDetailRowsDoNotOverlap() {
        composeRule.setContent {
            NovaComposeTheme {
                NovaStreamHudContent(
                    state = NovaHudUiState.preview(NovaHudMode.PERFORMANCE),
                    opacityScale = 0f
                )
            }
        }

        val primaryBounds = composeRule
            .onNodeWithTag(NOVA_HUD_PERFORMANCE_PRIMARY_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
        val detailBounds = composeRule
            .onNodeWithTag(NOVA_HUD_PERFORMANCE_DETAILS_TAG)
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue("FPS primary row should have measurable bounds", primaryBounds.width > 0f)
        assertTrue("Performance detail row should have measurable bounds", detailBounds.width > 0f)
        assertTrue(
            "FPS/target/sparkline row must end before latency/bitrate/resolution/codec details begin",
            primaryBounds.bottom <= detailBounds.top
        )
    }

    @Test
    fun debugHeadlineFpsDoesNotEllipsizeWithLongStreamMode() {
        composeRule.setContent {
            NovaComposeTheme {
                NovaStreamHudContent(
                    state = NovaHudUiState.preview(NovaHudMode.DEBUG).copy(
                        fpsLabel = "120",
                        targetFpsLabel = "TGT 120",
                        streamModeLabel = "Private Stream · GPU-native DMA-BUF 8b EXP",
                        autopilotHudLabel = "Auto Safe"
                    ),
                    opacityScale = 0.9f
                )
            }
        }

        val layoutResults = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText("120", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue("FPS text layout result should be available", action(layoutResults))
            }

        val fpsLayout = layoutResults.single()
        assertEquals(
            "Every headline FPS character must remain visible",
            fpsLayout.layoutInput.text.length,
            fpsLayout.getLineEnd(0, visibleEnd = true)
        )
        assertFalse("Headline FPS must not be ellipsized", fpsLayout.isLineEllipsized(0))
    }

}
