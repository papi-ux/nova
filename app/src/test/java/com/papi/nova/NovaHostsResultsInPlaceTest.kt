package com.papi.nova

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hosts results floated as snackbars and were gone in seconds (audit X2): Wake Host and Wake on
 * LAN results, the QR pairing errors, a host that cannot open its app list or library, and the
 * update check's failure. Each is a Notice in the right edge panel now, or the screen's own state:
 * the update pill says Retry, and pairing on its way is the Pairing page. Why pairing came to a PIN
 * is said on the PIN page. Sleep Host's countdown, its Keep Awake and every answer are Notices too
 * (M12). "Waking" and "Checking Library" are progress with no in-place home yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHostsResultsInPlaceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()

    private fun section(start: String, end: String): String {
        assertTrue("$start is still there", pcView.contains(start))
        return pcView.substringAfter(start).substringBefore(end)
    }

    @Test
    fun hostsResultsAreNoticesNotSnackbars() {
        val results = mapOf(
            "Wake Host's result" to section("private fun handlePolarisStartupResult(", "private fun updatePolarisStartupComputer("),
            // Up to the progress it then shows, which has no in-place home yet.
            "Wake Host's refusals" to section("private fun startPolarisFromNova(", "val uuid = computer.uuid"),
            "Wake on LAN" to section("private fun doWakeOnLan(", "private fun startPolarisFromNova("),
            "the QR pairing code" to section("private fun handleQrScanResult(", "private fun doPair("),
            "pairing" to section("private fun doPair(", "private fun applyPairedCertificate("),
            "the rail's Library" to section("private fun launchQuickLibrary(", "private fun launchPolarisStartupForPreferredHost("),
            "the rail's Wake Host" to section("private fun launchPolarisStartupForPreferredHost(", "private fun selectPreferredPolarisStartupComputer("),
            "the app list" to section("private fun doAppList(", "private fun doNovaLibrary("),
            "the library" to section("private fun doNovaLibrary(", "val intent = Intent(this, NovaLibraryActivity::class.java)"),
            "the update check" to section("private fun showNovaUpdateDashboardError(", "private fun maybeRunAutomaticNovaUpdateCheck("),
        )
        val floating = results.filterValues { it.contains("NovaSnackbar") }.keys
        assertEquals("say these on a Notice or in the screen's own state", emptySet<String>(), floating)
        assertTrue(results.getValue("Wake Host's result").contains("showHostsNotice(getString(R.string.pcview_quick_start_polaris), getString(failure))"))
        assertTrue("a ready host's library opening is the answer", results.getValue("Wake Host's result").contains("doNovaLibrary(computer)"))
    }

    @Test
    fun sleepHostResultsAreNoticesNotSnackbars() {
        val results = mapOf(
            "the rail's tap, hold and refusals" to section("private fun bindHostPowerAction(", "private fun widenHostPowerTouchTarget("),
            "the countdown and its Keep Awake" to section("private fun beginHostSleep(", "private fun requestHostSleep("),
            "the host's answer" to section("private fun requestHostSleep(", "private fun awaitHostAsleep("),
            "coming back after leaving mid countdown" to section("override fun onResume() {", "override fun onPause() {"),
            "leaving mid countdown" to section("override fun onPause() {", "override fun onStop() {"),
        )
        val floating = results.filterValues { it.contains("NovaSnackbar") }.keys
        assertEquals("say these on a Notice in the right edge panel (M12)", emptySet<String>(), floating)
        assertFalse(
            "the host menu's Sleep Host is confirmed by its split, so it does not count down again",
            pcView.contains("override fun sleep() = beginHostSleep()"),
        )
        assertTrue(pcView.contains("override fun sleep() = sleepHostNow()"))
        val now = section("private fun sleepHostNow(", "private fun takeDownSleepCountdown(")
        assertTrue(now.contains("hostSleepSequence.startRequest()") && !now.contains("startCountdown"))
        val countdown = section("private fun beginHostSleep(", "private fun sleepHostNow(")
        assertTrue(
            "Keep Awake is the countdown's close, so every way off its page calls the sleep off",
            countdown.contains("closeLabel = getString(R.string.pcview_sleep_keep_awake)") &&
                countdown.contains("onClose = { cancelPendingHostSleep() }"),
        )
        assertTrue(
            "the grace running out takes the page down quietly, so Keep Awake does not answer it",
            countdown.contains("takeDownSleepCountdown()\n            requestHostSleep(details)") &&
                pcView.contains("novaSurfaces.panel.removeWhere { it.key == SLEEP_COUNTDOWN_PAGE }"),
        )
        assertFalse(
            "no snackbar with a timer is left to float",
            File("src/main/java/com/papi/nova/ui/NovaSnackbar.kt").readText().contains("fun showPendingWithCancel("),
        )
    }

    @Test
    fun whyPairingCameToAPinIsSaidOnThePinPage() {
        val note = context.getString(R.string.nova_pairing_auto_fallback_pin)
        val page = novaPairingCodePage(context, "pairing", "4821", note) {}
        assertEquals("4821", page.code)
        assertTrue("the note comes first: ${page.message}", page.message.startsWith(note))
        assertTrue(page.message.endsWith(context.getString(R.string.hosts_pairing_code_message)))

        val plain = novaPairingCodePage(context, "pairing", "4821") {}
        assertEquals(context.getString(R.string.hosts_pairing_code_message), plain.message)

        val pairing = section("private fun doPair(", "private fun applyPairedCertificate(")
        assertTrue(pairing.contains("pinNote = getString(R.string.nova_pairing_auto_fallback_pin)"))
        assertTrue(pairing.contains("pinNote = getString(R.string.nova_pairing_auto_unsupported)"))
        assertTrue(pairing.contains("showPairingCode(pinStr, pinNote)"))
    }

    @Test
    fun pairingOnItsWayIsThePairingPageAndItsCloseHidesIt() {
        var hidden = 0
        val message = context.getString(R.string.hosts_qr_connecting, "192.0.2.10")
        val page = novaPairingProgressPage(context, "pairing", message) { hidden++ }
        assertEquals(context.getString(R.string.pair_pairing_title), page.title)
        assertEquals(message, page.message.value)
        assertEquals(context.getString(R.string.nova_panel_close), page.cancel!!.label)
        page.cancel!!.run()
        assertEquals(1, hidden)

        val scan = section("private fun handleQrScanResult(", "private fun doPair(")
        assertTrue(scan.contains("showPairingProgress(getString(R.string.hosts_qr_connecting, host))"))
        assertTrue("a host Nova cannot reach takes the wait down and says so", scan.contains("hidePairingPage()") && scan.contains("R.string.hosts_qr_unreachable"))
        assertFalse("no hardcoded English", scan.contains("\"Connecting to"))
        val already = section("private fun maybeRunPendingQrPairing(", "private val qrScanLauncher")
        assertTrue("a scanned host that is already paired takes the wait down", already.contains("hidePairingPage()"))
    }
}
