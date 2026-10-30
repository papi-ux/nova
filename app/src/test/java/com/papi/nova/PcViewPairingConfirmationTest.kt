package com.papi.nova

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.snackbar.Snackbar
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.ui.NovaConfirmation
import com.papi.nova.ui.NovaLibraryActivity
import com.papi.nova.ui.NovaSnackbar
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

/**
 * #386 through the actual post-pair route: a pairing that saved the host's certificate is felt,
 * and named on what it opens, once. The same host opened from its card says what it always said.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h480dp-land", shadows = [
    com.papi.nova.shadows.ShadowMoonBridge::class,
    com.papi.nova.shadows.ShadowGameManager::class,
])
class PcViewPairingConfirmationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val initialPolling = CountDownLatch(1)
    private val binder = mock(ComputerManagerService.ComputerManagerBinder::class.java) { call ->
        if (call.method.name == "startPolling") initialPolling.countDown()
        RETURNS_DEFAULTS.answer(call)
    }
    private lateinit var controller: ActivityController<PcView>

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
        // These are about what is said and felt, not the Library check, so keep it from the network.
        @Suppress("UNCHECKED_CAST")
        (field("libraryProbeInFlight") as MutableSet<String>).add(HOST)
        while (Shadows.shadowOf(controller.get()).nextStartedActivity != null) Unit
        NovaSnackbar.dismissActive()
    }

    @After fun close() {
        NovaSnackbar.dismissActive()
        controller.pause().stop().destroy()
    }

    @Test fun aPairingIsFeltAndNamedOnTheLibraryOrAppListItOpens() {
        val destinations = listOf(
            ComputerDetails.LibraryState.AVAILABLE to NovaLibraryActivity::class.java.name,
            ComputerDetails.LibraryState.UNAVAILABLE to AppView::class.java.name,
        )
        for ((library, screen) in destinations) {
            invoke("openBestPlaySurface", host(library))
            val ordinary = started()
            assertEquals(screen, ordinary.component?.className)
            assertFalse("opening a paired host from its card is not a pairing", ordinary.hasExtra(NovaConfirmation.EXTRA_PAIRED_WITH))
        }
        assertNotEquals("nothing is felt for an ordinary open", HapticFeedbackConstants.LONG_PRESS, haptic())

        for ((library, screen) in destinations) {
            invoke("openPairedHost", host(library))
            val paired = started()
            assertEquals(screen, paired.component?.className)
            assertEquals("Paired with $NAME", paired.getStringExtra(NovaConfirmation.EXTRA_PAIRED_WITH))
            assertEquals("the system's clear click, not controller rumble", HapticFeedbackConstants.LONG_PRESS, haptic())
        }

        invoke("openBestPlaySurface", host(ComputerDetails.LibraryState.AVAILABLE))
        assertFalse("the words go with that one open", started().hasExtra(NovaConfirmation.EXTRA_PAIRED_WITH))
    }

    @Test fun onHostsThePairingIsSaidInsteadOfTheCheckingLine() {
        invoke("openBestPlaySurface", host(ComputerDetails.LibraryState.UNKNOWN))
        assertEquals(context.getString(R.string.pcview_library_checking), snackbarText())

        invoke("openPairedHost", host(ComputerDetails.LibraryState.UNKNOWN))
        assertEquals("Paired with $NAME", snackbarText())
        assertNull("Hosts stays open while its Library is checked", Shadows.shadowOf(controller.get()).nextStartedActivity)
    }

    @Test fun onlyAPairingThatSavedTheCertificateConfirmsIt() {
        // doPair talks to the host on its own thread, so its two success points are held here.
        val pairing = File("src/main/java/com/papi/nova/PcView.kt").readText()
            .substringAfter("private fun doPair(").substringBefore("private fun applyPairedCertificate(")
        val alreadyPaired = pairing.substringAfter("if (existingPairState == PairState.PAIRED && hasPinnedServerCert(computer)) {")
            .substringBefore("} else {")
        val tofu = pairing.substringAfter("if (tofuState == PairState.PAIRED) {").substringBefore("return@Thread")
        val pin = pairing.substringAfter("PairState.PAIRED -> {").substringBefore("else -> message = null")

        assertFalse("an already paired host was not paired now", alreadyPaired.contains("pairedNow") || alreadyPaired.contains("openPairedHost"))
        assertTrue(
            "trusted pairing confirms only a computer its certificate was applied to",
            tofu.contains("pairedComputer = applyPairedCertificate(computer, pm, isNewPairing)") &&
                tofu.substringAfter("if (launchedComputer != null) {").substringBefore("} else {").contains("openPairedHost(launchedComputer)"),
        )
        assertTrue(
            "PIN and OTP pairing confirm only once the certificate was applied",
            pin.substringAfter("if (pairedComputer != null) {").substringBefore("} else {").contains("pairedNow = true"),
        )
        assertEquals(1, Regex("""pairedNow = true""").findAll(pairing).count())
        assertTrue(pairing.contains("if (paired) openPairedHost(launchedComputer) else openBestPlaySurface(launchedComputer)"))
        assertEquals(2, Regex("""openPairedHost\(""").findAll(pairing).count())
    }

    private fun host(library: ComputerDetails.LibraryState) = ComputerDetails().apply {
        uuid = HOST
        name = NAME
        state = ComputerDetails.State.ONLINE
        pairState = PairingManager.PairState.PAIRED
        activeAddress = ComputerDetails.AddressTuple("127.0.0.1", 47989)
        libraryState = library
    }

    private fun started(): Intent = Shadows.shadowOf(controller.get()).nextStartedActivity!!

    private fun haptic() = Shadows.shadowOf(controller.get().window.decorView).lastHapticFeedbackPerformed()

    /** The text of the one snackbar NovaSnackbar keeps on screen. */
    private fun snackbarText(): String? {
        val active = NovaSnackbar::class.java.getDeclaredField("activeSnackbar").run {
            isAccessible = true
            get(null) as Snackbar?
        } ?: return null
        return active.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).text.toString()
    }

    private fun field(name: String): Any? = PcView::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(controller.get())
    }

    private fun invoke(name: String, value: ComputerDetails) {
        PcView::class.java.getDeclaredMethod(name, ComputerDetails::class.java).run {
            isAccessible = true
            invoke(controller.get(), value)
        }
    }

    private companion object {
        const val HOST = "pairing-confirmation-fixture"
        const val NAME = "Den"
        @JvmStatic @BeforeClass fun suppressLogs() = TestLogSuppressor.install()
    }
}
