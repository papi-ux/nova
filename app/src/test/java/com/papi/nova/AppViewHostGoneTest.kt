package com.papi.nova

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * The App list's host going offline, or no longer listing this device, floated "Lost connection
 * to PC" or "PC not paired" as a Toast over Hosts once the list had already closed, and it was
 * gone before it could be read (audit X2). The list says it on a Problem page now, once, and its
 * Close, which B also does, goes back to Hosts.
 */
@Config(sdk = [33], shadows = [com.papi.nova.shadows.ShadowMoonBridge::class, com.papi.nova.shadows.ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class AppViewHostGoneTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("GlPreferences", 0).edit()
            .putString("Renderer", "TestRenderer")
            .putString("Fingerprint", Build.FINGERPRINT)
            .commit()
        Shadows.shadowOf(context as Application).setSystemService(Context.GAME_SERVICE, mock(GameManager::class.java))
        Shadows.shadowOf(context).setComponentNameAndServiceForBindService(
            ComponentName(context, ComputerManagerService::class.java),
            mock(ComputerManagerService.ComputerManagerBinder::class.java),
        )
        ProfilesManager.instance = null
        File(context.filesDir, "profiles").deleteRecursively()
        ProfilesManager.getInstance().load(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "profiles").deleteRecursively()
    }

    private fun open(): AppView {
        val intent = Intent(context, AppView::class.java)
            .putExtra(AppView.UUID_EXTRA, "test-uuid")
            .putExtra(AppView.NAME_EXTRA, "Test PC")
        return Robolectric.buildActivity(AppView::class.java, intent).setup().get().also { idle() }
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private fun problems(list: AppView) =
        NovaSurfaces.of(list).states.value.filterIsInstance<NovaStatePage.Problem>()

    @Test
    fun aHostThatGoesOfflineIsSaidOnceOnAPageWhoseCloseGoesBack() {
        val list = open()
        val before = problems(list).size

        list.showHostGone(R.string.hosts_app_list_lost_title, R.string.hosts_app_list_lost_message)
        // The poll after it says the same host is gone again, or now unpaired: nothing more is said.
        list.showHostGone(R.string.hosts_app_list_unpaired_title, R.string.hosts_app_list_unpaired_message)
        idle()

        val added = problems(list).drop(before)
        assertEquals("said once, however many polls follow", 1, added.size)
        val page = added.single()
        assertEquals(context.getString(R.string.hosts_app_list_lost_title), page.title)
        assertEquals(context.getString(R.string.hosts_app_list_lost_message), page.message)
        assertNull("nothing floats over the list or over Hosts", ShadowToast.getLatestToast())
        assertFalse("the list stays under the page until it is closed", list.isFinishing)

        page.primary.run()
        idle()
        assertTrue("Close goes back to Hosts", list.isFinishing)
    }

    @Test
    fun aHostThatNoLongerListsThisDeviceSaysSoInItsOwnWords() {
        val list = open()
        val before = problems(list).size

        list.showHostGone(R.string.hosts_app_list_unpaired_title, R.string.hosts_app_list_unpaired_message)
        idle()

        val page = problems(list).drop(before).single()
        assertEquals(context.getString(R.string.hosts_app_list_unpaired_title), page.title)
        assertEquals(context.getString(R.string.hosts_app_list_unpaired_message), page.message)
        assertNull(ShadowToast.getLatestToast())
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}
