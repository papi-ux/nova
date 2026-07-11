package com.papi.nova.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.papi.nova.ui.compose.NovaComposeTheme
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
}
