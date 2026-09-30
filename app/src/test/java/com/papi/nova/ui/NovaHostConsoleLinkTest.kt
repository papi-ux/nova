package com.papi.nova.ui

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.AppView
import com.papi.nova.Game
import com.papi.nova.ShortcutTrampoline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a link the host's console opens asks of Nova (N6), read from the links Polaris's console
 * makes: its Pin page's pairing address, `art://host:port?pin=..&passphrase=..&name=..`, which its
 * QR code holds too, and its Apps page's `art://launch?host_uuid=..&host_name=..&app_uuid=..&app_name=..`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHostConsoleLinkTest {
    @Test
    fun thePinPagesPairingAddressIsAPairingWithItsPinAndPassphrase() {
        val link = "art://10.0.0.232:47989?pin=1234&passphrase=abcd&name=pc%20papi"
        assertEquals(NovaHostConsoleLink.Pair(link, "10.0.0.232", 47989, "1234", "abcd"), NovaHostConsoleLink.of(link, HOST))
        // A host with no port of its own pairs on the default, as a scanned code does.
        assertEquals(
            NovaHostConsoleLink.Pair("art://pc-papi.lan?pin=1234&passphrase=abcd", "pc-papi.lan", 47989, "1234", "abcd"),
            NovaHostConsoleLink.of("art://pc-papi.lan?pin=1234&passphrase=abcd", HOST),
        )
    }

    @Test
    fun anAddressWithoutAPinOrAPassphraseIsForAnotherApp() {
        assertEquals(NovaHostConsoleLink.Elsewhere, NovaHostConsoleLink.of("art://10.0.0.232:47989?name=pc-papi", HOST))
        assertEquals(NovaHostConsoleLink.Elsewhere, NovaHostConsoleLink.of("art://10.0.0.232:47989?pin=1234", HOST))
    }

    @Test
    fun theAppsPagesLaunchLinkForThisHostIsALaunch() {
        val link = "art://launch?host_uuid=${HOST.lowercase()}&host_name=pc-papi&app_uuid=$CONTROL&app_name=Control"
        assertEquals(
            NovaHostConsoleLink.Launch(HOST.lowercase(), "pc-papi", CONTROL, null, "Control"),
            NovaHostConsoleLink.of(link, HOST),
        )
        assertEquals(
            "an app named by its number",
            NovaHostConsoleLink.Launch(HOST, null, null, "7", null),
            NovaHostConsoleLink.of("art://launch?host_uuid=$HOST&app_id=7", HOST),
        )
    }

    @Test
    fun aLaunchLinkForAnotherHostOrWithoutAnAppIsForAnotherApp() {
        val app = "app_uuid=$CONTROL&app_name=Control"
        assertEquals(NovaHostConsoleLink.Elsewhere, NovaHostConsoleLink.of("art://launch?host_uuid=$OTHER_HOST&$app", HOST))
        assertEquals("a console Nova knows no id for", NovaHostConsoleLink.Elsewhere, NovaHostConsoleLink.of("art://launch?host_uuid=$HOST&$app", null))
        assertEquals(NovaHostConsoleLink.Elsewhere, NovaHostConsoleLink.of("art://launch?host_uuid=$HOST&app_name=Control", HOST))
        assertEquals(NovaHostConsoleLink.Elsewhere, NovaHostConsoleLink.of("art://launch?host_uuid=$HOST&app_uuid=not-an-id", HOST))
    }

    @Test
    fun anyOtherArtLinkIsForAnotherAppAndOtherAddressesAreNotLinks() {
        assertEquals(NovaHostConsoleLink.Elsewhere, NovaHostConsoleLink.of("art://settings?open=apps", HOST))
        assertEquals(NovaHostConsoleLink.Elsewhere, NovaHostConsoleLink.of("ART://", HOST))
        assertNull(NovaHostConsoleLink.of("https://10.0.0.232:47990/#/apps", HOST))
        assertNull(NovaHostConsoleLink.of(null, HOST))
    }

    // The launch every art:// launch link takes: the shortcut trampoline, with the extras
    // AddComputerManually hands it for one opened from outside Nova.
    @Test
    fun aLaunchGoesToTheShortcutTrampolineWithTheLinksHostAndApp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = NovaHostConsoleLink.Launch(HOST, "pc-papi", CONTROL, null, "Control").intent(context)
        assertEquals(ComponentName(context, ShortcutTrampoline::class.java), intent.component)
        assertEquals(HOST, intent.getStringExtra(AppView.UUID_EXTRA))
        assertEquals("pc-papi", intent.getStringExtra(AppView.NAME_EXTRA))
        assertEquals(CONTROL, intent.getStringExtra(Game.EXTRA_APP_UUID))
        assertEquals("Control", intent.getStringExtra(Game.EXTRA_APP_NAME))
        assertNull(intent.getStringExtra(Game.EXTRA_APP_ID))
    }

    private companion object {
        const val HOST = "5E1F5A0B-2C3D-4E5F-8A9B-0C1D2E3F4A5B"
        const val OTHER_HOST = "11111111-2222-4333-8444-555555555555"
        const val CONTROL = "992FF124-4652-5708-501D-EDDBBB80EA8E"
    }
}
