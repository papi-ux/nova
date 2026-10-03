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
        val link = "art://192.0.2.10:47989?pin=1234&passphrase=abcd&name=test%20pc"
        assertEquals(NovaHostConsoleLink.Pair(link, "192.0.2.10", 47989, "1234", "abcd"), NovaHostConsoleLink.of(link, HOST))
        // A host with no port of its own pairs on the default, as a scanned code does.
        assertEquals(
            NovaHostConsoleLink.Pair("art://test-pc.lan?pin=1234&passphrase=abcd", "test-pc.lan", 47989, "1234", "abcd"),
            NovaHostConsoleLink.of("art://test-pc.lan?pin=1234&passphrase=abcd", HOST),
        )
    }

    @Test
    fun anAddressWithoutAPinOrAPassphraseIsForAnotherApp() {
        assertEquals(NovaHostConsoleLink.Elsewhere, NovaHostConsoleLink.of("art://192.0.2.10:47989?name=test-pc", HOST))
        assertEquals(NovaHostConsoleLink.Elsewhere, NovaHostConsoleLink.of("art://192.0.2.10:47989?pin=1234", HOST))
    }

    @Test
    fun theAppsPagesLaunchLinkForThisHostIsALaunch() {
        val link = "art://launch?host_uuid=${HOST.lowercase()}&host_name=test-pc&app_uuid=$CONTROL&app_name=Control"
        assertEquals(
            NovaHostConsoleLink.Launch(HOST.lowercase(), "test-pc", CONTROL, null, "Control"),
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
        assertNull(NovaHostConsoleLink.of("https://192.0.2.10:47990/#/apps", HOST))
        assertNull(NovaHostConsoleLink.of(null, HOST))
    }

    // The launch every art:// launch link takes: the shortcut trampoline, with the extras
    // AddComputerManually hands it for one opened from outside Nova.
    @Test
    fun aLaunchGoesToTheShortcutTrampolineWithTheLinksHostAndApp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = NovaHostConsoleLink.Launch(HOST, "test-pc", CONTROL, null, "Control").intent(context)
        assertEquals(ComponentName(context, ShortcutTrampoline::class.java), intent.component)
        assertEquals(HOST, intent.getStringExtra(AppView.UUID_EXTRA))
        assertEquals("test-pc", intent.getStringExtra(AppView.NAME_EXTRA))
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
