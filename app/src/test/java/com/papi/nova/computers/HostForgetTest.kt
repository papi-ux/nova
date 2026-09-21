package com.papi.nova.computers

import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.HostForgetResult
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.http.PairingManager.PairState
import java.io.File
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for the XML pull parser the host answers are read with.
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class HostForgetTest {
    private fun computer(state: PairState?, pinned: Boolean) = ComputerDetails().apply {
        pairState = state
        serverCert = if (pinned) mock(X509Certificate::class.java) else null
    }

    @Test
    fun onlyAPairedPcWithItsPinnedCertificateIsAsked() {
        assertTrue(HostForget.canAsk(computer(PairState.PAIRED, pinned = true)))
        assertFalse("never paired from here, so nothing to forget", HostForget.canAsk(computer(PairState.NOT_PAIRED, pinned = true)))
        assertFalse("no pinned certificate, so no HTTPS and no proof of who is asking", HostForget.canAsk(computer(PairState.PAIRED, pinned = false)))
        assertFalse(HostForget.canAsk(computer(null, pinned = false)))
    }

    @Test
    fun aHostThatForgotIsNotMentioned() {
        assertNull(HostForget.stillListedMessage(HostForgetResult.FORGOTTEN))
        assertNull("a PC that was never asked owes no sentence either", HostForget.stillListedMessage(null))
    }

    @Test
    fun aHostThatStillListsThisDeviceIsSaidSo() {
        assertEquals(R.string.delete_pc_host_unreachable, HostForget.stillListedMessage(HostForgetResult.UNREACHABLE))
        assertEquals(R.string.delete_pc_host_refused, HostForget.stillListedMessage(HostForgetResult.REFUSED))
        assertEquals(R.string.delete_pc_host_unsupported, HostForget.stillListedMessage(HostForgetResult.UNSUPPORTED))
    }

    @Test
    fun onlyAWellFormedAnswerThatSaysUnpairedCounts() {
        assertEquals(
            HostForgetResult.FORGOTTEN,
            NvHTTP.parseForgetResponse("<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"200\" status_message=\"Unpaired\"><paired>0</paired></root>"),
        )
        assertEquals(
            "a host that could not save the revocation answers 500 and still lists this device",
            HostForgetResult.REFUSED,
            NvHTTP.parseForgetResponse("<root status_code=\"500\" status_message=\"Paired-client revocation could not be persisted\"><paired>1</paired></root>"),
        )
        assertEquals(HostForgetResult.REFUSED, NvHTTP.parseForgetResponse("<root status_code=\"200\"><paired>1</paired></root>"))
        assertEquals(HostForgetResult.REFUSED, NvHTTP.parseForgetResponse("<root status_code=\"200\"></root>"))
        assertEquals(HostForgetResult.REFUSED, NvHTTP.parseForgetResponse("not xml at all"))
    }

    @Test
    fun theRevocationGoesOverHttpsAndBeforeThePcIsDeleted() {
        val http = File("src/main/java/com/papi/nova/nvstream/http/NvHTTP.kt").readText()
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val forget = http.substringAfter("fun forgetThisDevice(): HostForgetResult {").substringBefore("\n    }\n")

        assertTrue(
            "a host revokes a paired client only for a request that arrives over HTTPS with the client certificate; plain HTTP clears a pairing session and nothing else",
            forget.contains("getHttpsUrl(false), \"unpair\"") && !forget.contains("baseUrlHttp"),
        )
        assertTrue(
            "the pinned certificate goes away with the PC, so the host is asked first",
            pcView.indexOf("HostForget.ask(details") in 0 until pcView.indexOf("binder.removeComputer(details)"),
        )
        assertTrue(
            "removing a PC must not depend on the PC being awake",
            !forget.contains("throw ") && pcView.contains("HostForget.stillListedMessage(forgotten)"),
        )
    }
}
