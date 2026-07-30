package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaServerGridLayoutManagerTest {
    @Test
    fun serverGridDisablesPredictiveChildReattachment() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertFalse(NovaServerGridLayoutManager(context).supportsPredictiveItemAnimations())
    }
}
