package com.papi.nova.ui

import android.app.Activity
import android.widget.Button
import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** The QR scanner had a title and a hint but no Close (audit C10); B was the only way out. */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaQrScanCloseTest {
    @Test
    fun closeLeavesTheScannerWithNoCode() {
        val scanner = Robolectric.buildActivity(NovaQrScanActivity::class.java).create().get()
        val close = scanner.findViewById<Button>(R.id.qr_close)
        assertEquals(scanner.getString(R.string.nova_panel_close), close.text.toString())
        assertTrue("a controller and a remote can reach it", close.isFocusable)

        close.performClick()

        assertTrue(scanner.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(scanner).resultCode)
    }
}
