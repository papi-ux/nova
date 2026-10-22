package com.papi.nova.ui

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Owning the native Steam Controller must not recreate Play Setup as USB keyboard state changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaGameDetailInputConfigurationTest {
    @Test fun inputOwnershipChangesKeepGameDetailsAndPlaySetupAlive() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, NovaGameDetailActivity::class.java), 0,
        )
        val inputChanges = ActivityInfo.CONFIG_KEYBOARD or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN or ActivityInfo.CONFIG_NAVIGATION
        assertEquals(
            "Claiming or releasing the native controller must not recreate Game Detail",
            inputChanges, activity.configChanges and inputChanges,
        )
        val ordinaryRecreation = ActivityInfo.CONFIG_UI_MODE or ActivityInfo.CONFIG_FONT_SCALE or
            ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_ORIENTATION
        assertEquals("Theme, font, size and orientation keep normal recreation", 0,
            activity.configChanges and ordinaryRecreation)
    }
}
