package com.papi.nova.preferences

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.computers.ComputerManagerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * OK on an empty address floated "You must enter an IP address" as a Toast over the keyboard, and
 * it was gone before it could be read (audit X2). It is said under the field it is about, and
 * typing takes it away.
 */
@Config(sdk = [33], shadows = [com.papi.nova.shadows.ShadowMoonBridge::class, com.papi.nova.shadows.ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class AddComputerManuallyAddressTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Shadows.shadowOf(context as Application).setSystemService(Context.GAME_SERVICE, mock(GameManager::class.java))
        Shadows.shadowOf(context).setComponentNameAndServiceForBindService(
            ComponentName(context, ComputerManagerService::class.java),
            mock(ComputerManagerService.ComputerManagerBinder::class.java),
        )
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun anEmptyAddressIsSaidUnderTheFieldUntilTypingAnswersIt() {
        val controller = Robolectric.buildActivity(AddComputerManually::class.java).setup()
        try {
            val screen = controller.get()
            idle()
            val error = screen.findViewById<TextView>(R.id.addPcError)
            assertEquals("nothing is said before OK", View.GONE, error.visibility)

            screen.findViewById<View>(R.id.addPcButton).performClick()
            idle()
            assertEquals(View.VISIBLE, error.visibility)
            assertEquals(context.getString(R.string.hosts_add_enter_address), error.text.toString())
            assertNull("nothing floats over the keyboard", ShadowToast.getLatestToast())

            screen.findViewById<EditText>(R.id.hostTextView).setText("10.0.0.2")
            assertEquals("typing answers it, so it goes", View.GONE, error.visibility)
        } finally {
            controller.pause().stop().destroy()
            idle()
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}
