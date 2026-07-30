package com.papi.nova.utils

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplay

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidDisplayCandidateAdapterTest {
    @Test
    fun candidateUsesRealMetricsInsteadOfLogicalDisplaySize() {
        val display: Display = ShadowDisplay.getDefaultDisplay()
        val shadow = Shadows.shadowOf(display)
        shadow.setWidth(640)
        shadow.setHeight(360)
        shadow.setRealWidth(1920)
        shadow.setRealHeight(1080)

        val candidate = AndroidDisplayCandidateAdapter.from(display)

        assertEquals(display.displayId, candidate.displayId)
        assertEquals(1920, candidate.width)
        assertEquals(1080, candidate.height)
    }
}
