package com.papi.nova.binding.input

import android.app.Activity
import androidx.preference.PreferenceManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** A library does not need streaming JNI to read its USB choice: intentionally no MoonBridge shadow. */
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class NativeControllerLibraryStartupTest {
    @Test fun theDefaultUsbChoiceCanBindWithoutInitializingStreamingNativeCode() {
        checkStartup(enabled = null, expectedBound = true)
    }

    @Test fun aDisabledUsbChoiceDoesNotBindOrInitializeStreamingNativeCode() {
        checkStartup(enabled = false, expectedBound = false)
    }

    private fun checkStartup(enabled: Boolean?, expectedBound: Boolean) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        prefs.edit().clear().commit()
        if (enabled != null) prefs.edit().putBoolean("checkbox_usb_driver", enabled).commit()
        val input = NativeControllerLibraryInput(activity)
        try {
            input.resume()
            assertEquals(expectedBound, shadowOf(activity.application).boundServiceConnections.contains(input.connection))
        } finally {
            input.pause()
            prefs.edit().clear().commit()
            controller.pause().stop().destroy()
        }
    }
}
