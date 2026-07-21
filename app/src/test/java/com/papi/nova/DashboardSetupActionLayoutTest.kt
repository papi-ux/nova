package com.papi.nova

import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardSetupActionLayoutTest {
    @Test
    fun setupActionHeightIsCompactWhenCollapsedAndAdaptiveWhenExpanded() {
        val compactHeight = 34

        assertEquals(compactHeight, dashboardSetupActionHeight(collapsed = true, compactHeight))
        assertEquals(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dashboardSetupActionHeight(collapsed = false, compactHeight),
        )
    }
}
