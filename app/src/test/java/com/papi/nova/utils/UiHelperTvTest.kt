package com.papi.nova.utils

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class UiHelperTvTest {
    @Test
    fun isTvDeviceHonorsTelevisionConfiguration() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration)
        config.uiMode = config.uiMode and Configuration.UI_MODE_TYPE_MASK.inv() or
            Configuration.UI_MODE_TYPE_TELEVISION

        val tvContext = base.createConfigurationContext(config)

        assertTrue(UiHelper.isTvDevice(tvContext))
    }

    @Test
    fun isTvDeviceReturnsFalseForNormalConfiguration() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration)
        config.uiMode = config.uiMode and Configuration.UI_MODE_TYPE_MASK.inv() or
            Configuration.UI_MODE_TYPE_NORMAL

        val normalContext = base.createConfigurationContext(config)

        assertFalse(UiHelper.isTvDevice(normalContext))
    }

    @Test
    fun applyTvFocusStyleMakesViewDpadFocusable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = View(context)

        UiHelper.applyTvFocusStyle(view)

        assertTrue(view.isFocusable)
        assertFalse(view.isFocusableInTouchMode)
    }
}
