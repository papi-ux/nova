package com.papi.nova.diagnostics

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Picking where a report goes is a step the user sees, even with one place to send it. On the RP6,
 * with Google services off, Bluetooth is the only target: Android's chooser started it by itself,
 * and Share Report landed in "Choose Bluetooth device" (#280).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaShareChooserTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Before fun attachTheManifestProvider() {
        // Robolectric gives each test a new cache root, which a path strategy from an earlier test can't reach.
        val application = RuntimeEnvironment.getApplication()
        val providerInfo = requireNotNull(application.packageManager.resolveContentProvider(
            "${application.packageName}.fileprovider", PackageManager.GET_META_DATA))
        Robolectric.buildContentProvider(FileProvider::class.java).create(providerInfo).get()
    }

    private fun assertChooserStaysOpen(what: String) {
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(what, Intent.ACTION_CHOOSER, chooser.action)
        assertFalse(
            "$what: the chooser must not start a lone target by itself",
            chooser.getBooleanExtra("android.intent.extra.AUTO_LAUNCH_SINGLE_CHOICE", true),
        )
    }

    @Test fun theMomentReportsChooserStaysOpenWithASingleTarget() {
        val file = NovaMomentShare.write(NovaMomentShare.directory(activity), "2026-10-08T11:30:00Z", "moment".toByteArray())!!
        NovaMomentShare.offer(activity, file)
        assertChooserStaysOpen("moment report")
    }

    @Test fun theLastStreamReportsChooserStaysOpenWithASingleTarget() {
        val file = NovaStreamRecapExport.write(NovaStreamRecapExport.directory(activity), "2026-10-08T14:00:00Z", "recap".toByteArray())!!
        NovaStreamRecapExport.offer(activity, file)
        assertChooserStaysOpen("Last Stream report")
    }

    @Test fun theSupportReportsChooserStaysOpenWithASingleTarget() {
        assertTrue(SupportReportSharing.share(activity, "1.5.0"))
        assertChooserStaysOpen("support report")
    }

    @Test fun nothingInNovaOpensAChooserAnyOtherWay() {
        val others = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") && it.name != "NovaShareChooser.kt" }
            .filter { it.readText().contains("Intent.createChooser(") }
            .map { it.name }
            .toList()
        assertEquals("settings exports and every report take the one way in", emptyList<String>(), others)
    }
}
