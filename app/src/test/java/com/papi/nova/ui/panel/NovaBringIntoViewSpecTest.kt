package com.papi.nova.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class NovaBringIntoViewSpecTest {
    @Test
    fun aConfirmationAndItsFocusedHalfRequestTheSameScroll() {
        val tracker = NovaRowTracker()
        tracker.viewports["viewport"] = Rect(0f, 0f, 320f, 235f)
        tracker.rows["previous"] = NovaRowTracker.Entry(Rect(0f, -6f, 320f, 81f), false, false)
        tracker.rows["half"] = NovaRowTracker.Entry(Rect(0f, 89f, 150f, 138f), true, false)
        tracker.rows["group"] = NovaRowTracker.Entry(Rect(0f, 89f, 320f, 181f), false, false, focusGroup = true)
        tracker.rows["next"] = NovaRowTracker.Entry(Rect(0f, 185f, 320f, 278f), false, false)
        val spec = NovaContextBringIntoViewSpec(49f, labelPx = 24f, tracker = tracker, fadePx = 12f)
        var offset = 89f
        repeat(4) {
            val half = spec.calculateScrollDistance(offset, 49f, 235f)
            val group = spec.calculateScrollDistance(offset, 92f, 235f)
            assertEquals("both callers must share one destination", group, half, 0.001f)
            tracker.rows.values.forEach { it.bounds = it.bounds.translate(0f, -group) }
            offset -= group
        }
        assertEquals("the focused button settles", 0f, spec.calculateScrollDistance(offset, 49f, 235f), 0.001f)
        assertEquals("its whole warning also settles", 0f, spec.calculateScrollDistance(offset, 92f, 235f), 0.001f)
    }

    @Test
    fun aControlTallerThanTheViewportSettlesWhenItCoversTheViewport() {
        val spec = NovaContextBringIntoViewSpec(49f)
        assertEquals(0f, spec.calculateScrollDistance(-25f, 300f, 235f), 0f)
        val scroll = spec.calculateScrollDistance(10f, 300f, 235f)
        assertTrue(scroll > 0f)
        assertEquals(0f, spec.calculateScrollDistance(10f - scroll, 300f, 235f), 0f)
    }
}
