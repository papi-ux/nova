package com.papi.nova.ui

import android.content.ComponentName
import com.papi.nova.AppView
import com.papi.nova.Game
import com.papi.nova.R
import com.papi.nova.ShortcutTrampoline
import com.papi.nova.ui.panel.novaSurfaces
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaLibraryHostConsoleLinkTest {
    private val host = "5E1F5A0B-2C3D-4E5F-8A9B-0C1D2E3F4A5B"
    private val app = "992FF124-4652-5708-501D-EDDBBB80EA8E"

    private fun page(): Pair<NovaLibraryActivity, NovaHostConsolePage> {
        val activity = Robolectric.buildActivity(NovaLibraryActivity::class.java).get()
        mapOf("streamHost" to "host.test", "streamPcUuid" to host).forEach { (name, value) ->
            NovaLibraryActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
        }
        val page = NovaLibraryActivity::class.java.getDeclaredMethod("hostConsolePage", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "/#/apps") as NovaHostConsolePage
        activity.novaSurfaces.panel.open(page)
        return activity to page
    }

    @Test fun theLibraryBindsLaunchLinksToItsHostAndClosesThePanel() {
        val (activity, page) = page()
        assertEquals(host, page.hostUuid)
        val link = NovaHostConsoleLink.of("art://launch?host_uuid=$host&app_uuid=$app&app_name=Control", page.hostUuid)!!
        assertNull(page.onLink!!(link))
        assertFalse(activity.novaSurfaces.panel.isOpen)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ComponentName(activity, ShortcutTrampoline::class.java), started.component)
        assertEquals(host, started.getStringExtra(AppView.UUID_EXTRA))
        assertEquals(app, started.getStringExtra(Game.EXTRA_APP_UUID))
    }

    @Test fun theLibraryExplainsPairingAndRejectsAnotherHostsLaunchInPlace() {
        val (activity, page) = page()
        val pair = NovaHostConsoleLink.of("art://host.test:47989?pin=1234&passphrase=test", page.hostUuid)!!
        assertEquals(activity.getString(R.string.nova_host_console_link_paired), page.onLink!!(pair))
        val elsewhere = NovaHostConsoleLink.of("art://launch?host_uuid=another&app_uuid=$app", page.hostUuid)!!
        assertEquals(activity.getString(R.string.nova_host_console_link_elsewhere), page.onLink!!(elsewhere))
        assertTrue(activity.novaSurfaces.panel.isOpen)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
