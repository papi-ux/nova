package com.papi.nova.ui

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** USB ownership changes the Shield's keyboard configuration while Library is resumed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaLibraryInputConfigurationTest {
    @Test fun inputConfigurationChangesKeepTheLibraryInstance() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Read Android's merged manifest, the policy ActivityTaskManager uses to decide
        // whether a device configuration update must destroy and recreate this screen.
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, NovaLibraryActivity::class.java), 0,
        )
        val inputChanges = ActivityInfo.CONFIG_KEYBOARD or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN or ActivityInfo.CONFIG_NAVIGATION
        assertEquals(
            "USB claim/release must not recreate Library and restart USB ownership",
            inputChanges, activity.configChanges and inputChanges,
        )
    }
}
