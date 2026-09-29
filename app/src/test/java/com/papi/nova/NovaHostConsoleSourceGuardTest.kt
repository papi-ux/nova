package com.papi.nova

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host's console opened in a browser, which met the host's self-signed certificate and asked
 * the player to click through the warning (N6). Nova opens it itself now, in its panel, trusting
 * only the certificate it paired with, so no screen hands the console's address to a browser.
 */
class NovaHostConsoleSourceGuardTest {
    private val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
    private val library = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()

    @Test
    fun noScreenSendsABrowserToTheHostsConsole() {
        val hosts = pcView.substringAfter("computer.guessManagementUrl()").substringBefore("\n    }\n")
        assertFalse("Hosts opens the console itself", hosts.contains("ACTION_VIEW"))
        val manage = library.substringAfter("private fun openServerManagementAt(").substringBefore("\n    }\n")
        assertFalse("the library opens the console itself", manage.contains("ACTION_VIEW"))
    }

    @Test
    fun theConsoleIsAPageBothPanelsDraw() {
        assertTrue(
            "the host menu's panel draws the console it pushes",
            pcView.contains("if (page is NovaHostConsolePage) NovaHostConsole(page)") &&
                pcView.contains("override fun hostConsolePage(): NovaPage = hostConsolePageFor(computer)"),
        )
        assertTrue(
            "the pin is the certificate Nova stored when it paired",
            pcView.contains("pinnedCertificate = runCatching { computer.details.serverCert?.encoded }.getOrNull()") &&
                library.contains("pinnedCertificate = streamServerCert,"),
        )
        assertTrue(
            "the library's panel draws it, pushed on System or opened on its own",
            library.contains("is NovaHostConsolePage -> NovaHostConsole(page)") &&
                library.contains("surfaces.panel.push(page)") &&
                library.contains("surfaces.open(page, NovaEdge.End) { shown -> LibraryPanelPage(shown) }"),
        )
    }

    @Test
    fun nothingOnTheConsolePageGoesPastACertificateItDidNotPairWith() {
        val console = File("src/main/java/com/papi/nova/ui/NovaHostConsole.kt").readText()
        val sslError = console.substringAfter("override fun onReceivedSslError(").substringBefore("override fun ")
        assertTrue(
            "a certificate error is let through only for the pin, on the console's own address",
            sslError.contains("if (!stopped && trusted(error.url, error.certificate)) {\n            handler.proceed()") &&
                sslError.split("handler.proceed()").size == 2,
        )
        assertTrue(
            "a page shows only once the certificate it came with is the pin",
            console.contains("if (trusted(url, view.certificate)) {\n            view.visibility = View.VISIBLE\n            onState(NovaHostConsoleState.Showing)"),
        )
        assertTrue(console.contains("settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW"))
        assertFalse(
            "there is no way past a refusal",
            File("src/main/res/values/strings_ui_hosts.xml").readText().contains("Proceed", ignoreCase = true) ||
                console.contains("Proceed Anyway", ignoreCase = true),
        )
    }
}
