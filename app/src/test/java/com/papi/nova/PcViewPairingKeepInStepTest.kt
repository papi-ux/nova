package com.papi.nova

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.manager.PolarisProfileSync
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

/** Drives the successful PIN/OTP/TOFU certificate completion used by Hosts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h480dp-land", shadows = [
    com.papi.nova.shadows.ShadowMoonBridge::class,
    com.papi.nova.shadows.ShadowGameManager::class,
])
class PcViewPairingKeepInStepTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val saved = mutableListOf<ComputerDetails>()
    private val binder = mock(ComputerManagerService.ComputerManagerBinder::class.java) { call ->
        if (call.method.name == "persistComputer") saved += call.arguments[0] as ComputerDetails
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
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun close() { controller.pause().stop().destroy() }

    private fun complete(newPairing: Boolean, certificate: X509Certificate? = mock(X509Certificate::class.java)): ComputerDetails? {
        val computer = ComputerDetails().apply { uuid = HOST; name = "Pairing fixture" }
        val pairing = mock(PairingManager::class.java)
        `when`(pairing.getPairedCert()).thenReturn(certificate)
        return PcView::class.java.getDeclaredMethod("applyPairedCertificate",
            ComputerDetails::class.java, PairingManager::class.java, Boolean::class.javaPrimitiveType).run {
            isAccessible = true
            invoke(controller.get(), computer, pairing, newPairing) as ComputerDetails?
        }
    }

    @Test fun successfulNewPairingEnablesKeepInStepAfterTheCertificateIsSaved() {
        assertFalse(PolarisProfileSync.isAutoSyncEnabled(context, HOST))
        val paired = complete(true)
        assertNotNull(paired)
        assertEquals(PairingManager.PairState.PAIRED, paired!!.pairState)
        assertNotNull(paired.serverCert)
        assertTrue(saved.contains(paired))
        assertTrue("the actual pairing completion seeds the new host", PolarisProfileSync.isAutoSyncEnabled(context, HOST))
    }

    @Test fun successfulNewPairingPreservesAnExplicitOffChoice() {
        PolarisProfileSync.setAutoSyncEnabled(context, HOST, false)
        assertNotNull(complete(true))
        assertFalse(PolarisProfileSync.isAutoSyncEnabled(context, HOST))
    }

    @Test fun rePairingAndCertificateRepairDoNotMigrateImplicitOffHosts() {
        assertNotNull(complete(false))
        assertFalse(PolarisProfileSync.isAutoSyncEnabled(context, HOST))
        assertFalse(context.getSharedPreferences("nova_prefs", 0).contains(PolarisProfileSync.autoSyncKey(HOST)))
    }

    @Test fun existingOnChoiceSurvivesRepair() {
        PolarisProfileSync.setAutoSyncEnabled(context, HOST, true)
        assertNotNull(complete(false))
        assertTrue(PolarisProfileSync.isAutoSyncEnabled(context, HOST))
    }

    @Test fun completionWithoutPinnedCertificateDoesNotEnableSyncOrPersistPairing() {
        assertNull(complete(true, null))
        assertTrue(saved.isEmpty())
        assertFalse(PolarisProfileSync.isAutoSyncEnabled(context, HOST))
    }

    private companion object {
        const val HOST = "new-pairing-fixture"
        @JvmStatic @BeforeClass fun suppressLogs() = TestLogSuppressor.install()
    }
}
