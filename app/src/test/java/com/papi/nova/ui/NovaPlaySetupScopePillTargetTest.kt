package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scope pill is drawn 24dp tall (C24). A finger gets the 48dp a target needs all the same:
 * a tap up to 12dp above or below the pill still picks the half it lands over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPlaySetupScopePillTargetTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aTapJustOffThePillStillPicksTheHalfItLandsOver() {
        val picked = mutableListOf<NovaPlaySetupScope>()
        rule.setPanelContent {
            Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                NovaPlaySetupScopePill(scope = NovaPlaySetupScope.THIS_GAME, onSelected = { picked += it })
            }
        }
        val pill = rule.onNodeWithTag(NOVA_PLAY_SETUP_SCOPE_PILL_TAG).getUnclippedBoundsInRoot()
        val density = rule.density
        with(density) {
            val right = (pill.right - 12.dp).toPx()
            val above = (pill.top - 10.dp).toPx()
            val below = (pill.bottom + 10.dp).toPx()
            rule.onRoot().performTouchInput { click(Offset(right, above)) }
            rule.onRoot().performTouchInput { click(Offset(right, below)) }
        }
        rule.waitForIdle()
        assertEquals(listOf(NovaPlaySetupScope.EVERY_GAME, NovaPlaySetupScope.EVERY_GAME), picked)
    }
}
