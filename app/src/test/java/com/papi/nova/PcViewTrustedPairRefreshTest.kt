package com.papi.nova

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.computers.ComputerManagerListener
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the actual post-pair route while the Library check keeps Hosts in the foreground. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h480dp-land", shadows = [
    com.papi.nova.shadows.ShadowMoonBridge::class,
    com.papi.nova.shadows.ShadowGameManager::class,
])
class PcViewTrustedPairRefreshTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val initialPolling = CountDownLatch(1)
    private var freshHost: ComputerDetails? = null
    private val binder = mock(ComputerManagerService.ComputerManagerBinder::class.java) { call ->
        if (call.method.name == "startPolling") {
            freshHost?.let { (call.arguments[0] as ComputerManagerListener).notifyComputerUpdated(it) }
            initialPolling.countDown()
        }
        RETURNS_DEFAULTS.answer(call)
    }
    private lateinit var controller: ActivityController<PcView>
    private lateinit var model: PcViewModel
    private lateinit var pairedHost: ComputerDetails
    private var resumed = true

    @Before fun open() {
        context.getSharedPreferences("nova_prefs", 0).edit().clear().commit()
        context.getSharedPreferences("GlPreferences", 0).edit()
            .putString("Renderer", "TestRenderer").putString("Fingerprint", Build.FINGERPRINT).commit()
        Shadows.shadowOf(context as Application).setSystemService(Context.GAME_SERVICE, mock(GameManager::class.java))
        Shadows.shadowOf(context).setComponentNameAndServiceForBindService(
            ComponentName(context, ComputerManagerService::class.java), binder)
        controller = Robolectric.buildActivity(PcView::class.java).setup()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (initialPolling.count != 0L && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the service binding completed before the pairing scenario", 0L, initialPolling.count)
        model = field("viewModel") as PcViewModel
        pairedHost = host(ComputerDetails.LibraryState.UNKNOWN)
        model.updateComputer(pairedHost)
        // A capability request can still be pending when trusted pairing finishes. Keep it
        // pending without making a real network request: this test is about discovery delivery.
        @Suppress("UNCHECKED_CAST")
        (field("libraryProbeInFlight") as MutableSet<String>).add(HOST)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        invoke("stopComputerUpdates", true)
        clearInvocations(binder)
        freshHost = host(ComputerDetails.LibraryState.AVAILABLE)
    }

    @After fun close() {
        if (resumed) controller.pause()
        controller.stop().destroy()
    }

    @Test fun trustedPairWithPendingLibraryCheckRefreshesWithoutLeavingHosts() {
        invoke("openBestPlaySurface", pairedHost)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, pollingStarts())
        assertEquals(true, field("runningPolling"))
        assertEquals(false, field("freezeUpdates"))
        assertEquals(ComputerDetails.LibraryState.AVAILABLE,
            model.computersLiveData.value!!.single().details.libraryState)
        assertFalse("no Activity leave/resume was needed", controller.get().isFinishing)
    }

    @Test fun repeatedPlayPressDoesNotSubscribeToDiscoveryTwice() {
        invoke("openBestPlaySurface", pairedHost)
        invoke("openBestPlaySurface", pairedHost)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, pollingStarts())
    }

    @Test fun latePairResultDoesNotResumePollingBehindAnotherScreen() {
        controller.pause()
        resumed = false
        clearInvocations(binder)
        invoke("openBestPlaySurface", pairedHost)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, pollingStarts())
        assertEquals(false, field("runningPolling"))
    }

    private fun host(library: ComputerDetails.LibraryState) = ComputerDetails().apply {
        uuid = HOST
        name = "Trusted pairing fixture"
        state = ComputerDetails.State.ONLINE
        pairState = PairingManager.PairState.PAIRED
        serverCert = mock(X509Certificate::class.java)
        activeAddress = ComputerDetails.AddressTuple("127.0.0.1", 47989)
        libraryState = library
    }

    private fun field(name: String): Any? = PcView::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(controller.get())
    }

    private fun pollingStarts() = mockingDetails(binder).invocations.count { it.method.name == "startPolling" }

    private fun invoke(name: String, value: Any) {
        val type = if (value is Boolean) Boolean::class.javaPrimitiveType!! else value.javaClass
        PcView::class.java.getDeclaredMethod(name, type).run {
            isAccessible = true
            invoke(controller.get(), value)
        }
    }

    private companion object {
        const val HOST = "trusted-pair-refresh-fixture"
        @JvmStatic @BeforeClass fun suppressLogs() = TestLogSuppressor.install()
    }
}
