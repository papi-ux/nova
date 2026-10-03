package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.novaScanPairCameraPage
import com.papi.nova.ui.panel.NovaProblemBack
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scan Pair on the RP6 with the camera refused opened zxing's scanner on a black void. It is
 * offered only where there is a camera, and a refusal gets a state page with Try Again and the
 * way that needs no camera.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaScanPairCameraPageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aRefusalOffersTryAgainAndAddServerAndBOnlyLeaves() {
        val ran = mutableListOf<String>()
        val page = novaScanPairCameraPage(
            context, "camera", denied = true,
            retry = { ran += "retry" }, addServer = { ran += "add" }, takeDown = { ran += "down" },
        )
        assertEquals(context.getString(R.string.nova_scan_pair_try_again), page.primary.label)
        assertEquals(
            listOf(context.getString(R.string.pcview_quick_add_server), context.getString(R.string.nova_panel_back)),
            page.secondary.map { it.label },
        )
        val back = page.back as NovaProblemBack.Continue
        back.action.run()
        assertEquals("B takes the page down and retries nothing", listOf("down"), ran)
        page.primary.run()
        assertEquals(listOf("down", "down", "retry"), ran)
    }

    @Test
    fun withNoCameraTheWayOnIsAddServer() {
        val page = novaScanPairCameraPage(context, "camera", denied = false, retry = {}, addServer = {}, takeDown = {})
        assertEquals(context.getString(R.string.pcview_quick_add_server), page.primary.label)
    }

    @Test
    fun theScannerOpensOnlyWithACameraAndItsPermission() {
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val launch = source.substringAfter("private fun launchQrScanner() {").substringBefore("private fun showScanPairCameraPage")
        assertTrue(launch.contains("if (!hasCamera())") && launch.contains("cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)"))
        assertTrue(source.contains("if (!hasCamera()) emptyScanPair?.visibility = View.GONE"))
        assertTrue(
            "the spinner turns only while a search is running",
            source.contains("if ((computers == null || computers.isEmpty()) && runningPolling) View.VISIBLE else View.GONE"),
        )
    }
}
