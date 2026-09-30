package com.papi.nova.ui

import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Welcome offered Scan Polaris QR on a TV with no camera (audit C27). */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaWelcomeCameraTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun welcome(camera: Boolean): NovaWelcomeActivity {
        Shadows.shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, camera)
        return Robolectric.buildActivity(NovaWelcomeActivity::class.java).setup().get()
    }

    @Test
    fun aDeviceWithNoCameraIsNotOfferedAScan() {
        val screen = welcome(camera = false)
        assertEquals(View.GONE, screen.findViewById<View>(R.id.welcome_scan_qr_btn).visibility)
        assertEquals(
            context.getString(R.string.nova_welcome_step_pair_body_no_camera),
            screen.findViewById<TextView>(R.id.welcome_pair_body).text.toString(),
        )
    }

    @Test
    fun aDeviceWithACameraIsOfferedTheScan() {
        val screen = welcome(camera = true)
        assertEquals(View.VISIBLE, screen.findViewById<View>(R.id.welcome_scan_qr_btn).visibility)
        assertEquals(
            context.getString(R.string.nova_welcome_step_pair_body),
            screen.findViewById<TextView>(R.id.welcome_pair_body).text.toString(),
        )
    }
}
