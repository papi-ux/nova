package com.papi.nova.profiles

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.AppView
import com.papi.nova.PcView
import com.papi.nova.ProfilesActivity
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.computers.ComputerManagerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@Config(
    sdk = [33],
    shadows = [
        com.papi.nova.shadows.ShadowMoonBridge::class,
        com.papi.nova.shadows.ShadowGameManager::class
    ]
)
@RunWith(RobolectricTestRunner::class)
class ProfilesNavigationTest {

    private fun prepareEnvironment() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val glPrefs = context.getSharedPreferences("GlPreferences", 0)
        glPrefs.edit()
            .putString("Renderer", "TestRenderer")
            .putString("Fingerprint", Build.FINGERPRINT)
            .commit()

        val shadowApp = Shadows.shadowOf(context as Application)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            shadowApp.setSystemService(Context.GAME_SERVICE, mock(GameManager::class.java))
        } else {
            shadowApp.setSystemService(Context.GAME_SERVICE, null)
        }

        val componentName = ComponentName(context, ComputerManagerService::class.java)
        val binder = mock(ComputerManagerService.ComputerManagerBinder::class.java)
        Shadows.shadowOf(context).setComponentNameAndServiceForBindService(componentName, binder)
    }

    @Test
    fun clickingProfileButtonLaunchesProfilesActivity() {
        prepareEnvironment()
        val controller = Robolectric.buildActivity(PcView::class.java).setup()
        val pcView = controller.get()

        val button = pcView.findViewById<View>(R.id.profilesButton)
        assertNotNull("profilesButton not found", button)

        button.performClick()

        val next = Shadows.shadowOf(pcView).nextStartedActivity
        assertNotNull("ProfilesActivity should be launched", next)
        assertEquals(ProfilesActivity::class.java.name, next.component!!.className)
    }

    @Test
    fun clickingProfileButtonLaunchesProfilesActivityFromAppView() {
        prepareEnvironment()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, AppView::class.java)
        intent.putExtra("UUID", "test-uuid")
        intent.putExtra("Name", "Test PC")

        val controller = Robolectric.buildActivity(AppView::class.java, intent).setup()
        val appView = controller.get()

        val button = appView.findViewById<View>(R.id.profilesButton)
        assertNotNull("profilesButton not found in AppView", button)

        button.performClick()

        val next = Shadows.shadowOf(appView).nextStartedActivity
        assertNotNull("ProfilesActivity should be launched from AppView", next)
        assertEquals(ProfilesActivity::class.java.name, next.component!!.className)
    }

    @Test
    fun profilesActivityStartsWithoutCrash() {
        prepareEnvironment()
        val activity = Robolectric.buildActivity(ProfilesActivity::class.java).setup().get()
        assertNotNull(activity)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressInvalidIdLogs() {
            TestLogSuppressor.install()
        }
    }
}
