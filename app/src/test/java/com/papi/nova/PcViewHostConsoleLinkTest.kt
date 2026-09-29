package com.papi.nova

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.PcViewModel.ComputerObject
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager.PairState
import com.papi.nova.ui.NovaHostConsoleLink
import com.papi.nova.ui.NovaHostConsolePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Hosts follows the links a host's console opens (N6), where Hosts itself would: Pair Now's
 * address goes where a scanned pairing code goes, with the console's own PIN and passphrase, and a
 * launch link takes the launch every art:// launch link takes. Driven through the console page
 * Hosts builds for a host, with the host service bound as on a device.
 */
@Config(
    sdk = [33],
    qualifiers = "w900dp-h480dp-land",
    shadows = [com.papi.nova.shadows.ShadowMoonBridge::class, com.papi.nova.shadows.ShadowGameManager::class],
)
@RunWith(RobolectricTestRunner::class)
class PcViewHostConsoleLinkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    // The hosts the binder was asked to find, from any thread; it finds every one.
    private val asked = java.util.concurrent.CopyOnWriteArrayList<ComputerDetails>()
    private val binder = mock(ComputerManagerService.ComputerManagerBinder::class.java) { call ->
        if (call.method.name == "addComputerBlocking") {
            asked += call.arguments[0] as ComputerDetails
            true
        } else {
            RETURNS_DEFAULTS.answer(call)
        }
    }

    private fun open(): PcView {
        context.getSharedPreferences("GlPreferences", 0).edit()
            .putString("Renderer", "TestRenderer")
            .putString("Fingerprint", Build.FINGERPRINT)
            .commit()
        val app = Shadows.shadowOf(context as Application)
        app.setSystemService(Context.GAME_SERVICE, mock(GameManager::class.java))
        Shadows.shadowOf(context).setComponentNameAndServiceForBindService(
            ComponentName(context, ComputerManagerService::class.java),
            binder,
        )
        return Robolectric.buildActivity(PcView::class.java).setup().get().also { idle() }
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private fun host(pairState: PairState) = ComputerObject(
        ComputerDetails().apply {
            uuid = HOST
            name = "pc-papi"
            state = ComputerDetails.State.ONLINE
            activeAddress = ComputerDetails.AddressTuple("10.0.0.232", 47989)
            this.pairState = pairState
        },
    )

    private fun <T> field(hosts: PcView, name: String): T? {
        @Suppress("UNCHECKED_CAST")
        return PcView::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(hosts) as T?
        }
    }

    @Test
    fun pairNowPairsAsTheHostsQrCodeDoesWithTheConsolesPinAndPassphrase() {
        val hosts = open()
        val page = hosts.hostConsolePageFor(host(PairState.NOT_PAIRED)) as NovaHostConsolePage
        assertEquals("the console names the host by its own id", HOST, page.hostUuid)
        val link = "art://10.0.0.232:47989?pin=1234&passphrase=abcd&name=pc-papi"

        val said = page.onLink!!(NovaHostConsoleLink.of(link, page.hostUuid)!!)

        assertNull("followed, nothing said in the console", said)
        // Where a scanned code goes: the host is found by the address the code names, and the
        // pairing waits for it with the code's PIN and passphrase.
        val deadline = System.nanoTime() + 2_000_000_000L
        while (asked.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(listOf(ComputerDetails.AddressTuple("10.0.0.232", 47989)), asked.map { it.manualAddress })
        assertEquals(ComputerDetails.AddressTuple("10.0.0.232", 47989), field(hosts, "pendingPairingAddress"))
        assertEquals("1234", field<String>(hosts, "pendingPairingPin"))
        assertEquals("abcd", field<String>(hosts, "pendingPairingPassphrase"))
    }

    @Test
    fun aHostNovaIsPairedWithHasNothingToPairAndSaysSoInPlace() {
        val hosts = open()
        val paired = host(PairState.PAIRED).apply {
            details.serverCert = PcViewHostConsoleLinkTestCertificates.certificate()
        }
        val page = hosts.hostConsolePageFor(paired) as NovaHostConsolePage
        val said = page.onLink!!(NovaHostConsoleLink.of("art://10.0.0.232:47989?pin=1234&passphrase=abcd", page.hostUuid)!!)
        assertEquals(context.getString(R.string.nova_host_console_link_paired), said)
        Thread.sleep(100)
        assertEquals("no host is looked for", emptyList<ComputerDetails>(), asked.toList())
        assertNull(field<String>(hosts, "pendingPairingPin"))
    }

    @Test
    fun aLaunchLinkTakesTheLaunchEveryArtLaunchLinkTakes() {
        val hosts = open()
        val page = hosts.hostConsolePageFor(host(PairState.PAIRED)) as NovaHostConsolePage
        val link = "art://launch?host_uuid=$HOST&host_name=pc-papi&app_uuid=$CONTROL&app_name=Control"

        val said = page.onLink!!(NovaHostConsoleLink.of(link, page.hostUuid)!!)

        assertNull(said)
        val started = Shadows.shadowOf(hosts).nextStartedActivity
        assertEquals(ComponentName(context, ShortcutTrampoline::class.java), started.component)
        assertEquals(HOST, started.getStringExtra(AppView.UUID_EXTRA))
        assertEquals(CONTROL, started.getStringExtra(Game.EXTRA_APP_UUID))
        assertEquals("Control", started.getStringExtra(Game.EXTRA_APP_NAME))
    }

    private companion object {
        const val HOST = "5E1F5A0B-2C3D-4E5F-8A9B-0C1D2E3F4A5B"
        const val CONTROL = "992FF124-4652-5708-501D-EDDBBB80EA8E"

        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}

/** A throwaway self-signed certificate, standing for the one Nova paired with. */
private object PcViewHostConsoleLinkTestCertificates {
    private const val PAIRED = "MIIBiTCCAS+gAwIBAgIUIaJczDVRt5Z9RKON0U4n9BbaCNwwCgYIKoZIzj0EAwIwGTEXMBUGA1UEAwwOUG9sYXJpcyBUZXN0IGEwIBcNMjYwOTI5MTM1NDI1WhgPMjEyNjA5MDUxMzU0MjVaMBkxFzAVBgNVBAMMDlBvbGFyaXMgVGVzdCBhMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEXRPHahtNgHSCLnzZBtTMcVVHi+o8B5LBm+LN5+Cl72UV1y+/IamxK1Bs54tXHOOetsEJpJPghSnox8OnV3aJgaNTMFEwHQYDVR0OBBYEFHUI2KOP97cCMD5D1P+YUHJvWqMjMB8GA1UdIwQYMBaAFHUI2KOP97cCMD5D1P+YUHJvWqMjMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgMe7TiHRUIe8yaffDMGWeX6mtD3f3D7Yg2Li8tO4lpisCIQD1XJYkg4XtP2u6SdA2cPdPy7gq0GiN+5wrhro0cQR2Qw=="

    fun certificate(): java.security.cert.X509Certificate =
        java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(PAIRED))) as java.security.cert.X509Certificate
}
