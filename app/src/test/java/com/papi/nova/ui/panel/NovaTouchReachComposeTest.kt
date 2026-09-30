package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [novaTouchReach]: a 40dp element keeps its 40dp in the layout, and its tap reaches 4dp past it
 * above and below, 48dp in all, the target a finger needs (C24).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaTouchReachComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var taps = 0

    private fun column() {
        rule.setContent {
            Column(Modifier.padding(40.dp).testTag("column")) {
                Spacer(Modifier.size(width = 100.dp, height = 40.dp))
                Box(Modifier.novaTouchReach(4.dp, Modifier.testTag("target").pointerInput(Unit) { detectTapGestures(onTap = { taps++ }) })) {
                    Box(Modifier.testTag("element").size(width = 100.dp, height = 40.dp))
                }
                Spacer(Modifier.width(100.dp).height(40.dp))
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theElementKeepsItsHeightInTheLayout() {
        column()
        val column = rule.onNodeWithTag("column").getUnclippedBoundsInRoot()
        assertEquals("three 40dp lines, no more", 120f, (column.bottom - column.top).value, 0.5f)
        val element = rule.onNodeWithTag("element").getUnclippedBoundsInRoot()
        assertEquals("drawn where it would have been", column.top.value + 40f, element.top.value, 0.5f)
        val target = rule.onNodeWithTag("target").getUnclippedBoundsInRoot()
        assertEquals("the target is 48dp", 48f, (target.bottom - target.top).value, 0.5f)
    }

    @Test
    fun aTapJustOffTheElementIsItsTap() {
        column()
        val element = rule.onNodeWithTag("element").getUnclippedBoundsInRoot()
        with(rule.density) {
            val x = (element.left + 50.dp).toPx()
            rule.onRoot().performTouchInput { click(Offset(x, (element.top - 3.dp).toPx())) }
            rule.onRoot().performTouchInput { click(Offset(x, (element.bottom + 3.dp).toPx())) }
            rule.onRoot().performTouchInput { click(Offset(x, (element.bottom + 6.dp).toPx())) }
        }
        rule.waitForIdle()
        assertEquals("3dp above and below are the element's; 6dp below is not", 2, taps)
    }
}
