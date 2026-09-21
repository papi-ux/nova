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
    private fun computer(state: PairState?, pinned: Boolean, runningGame: Int = 0) = ComputerDetails().apply {
        pairState = state
        serverCert = if (pinned) mock(X509Certificate::class.java) else null
        runningGameId = runningGame
    }

    @Test
    fun aPcIsAskedOnTheStrengthOfItsPinnedCertificate() {
        assertTrue(HostForget.canAsk(computer(PairState.PAIRED, pinned = true)))
        assertTrue(
            "the pair state is only known once the PC has answered this session; a PC that was off when Nova started must still be asked",
            HostForget.canAsk(computer(null, pinned = true)),
        )
        assertFalse("no pinned certificate, so no HTTPS and no proof of who is asking", HostForget.canAsk(computer(PairState.PAIRED, pinned = false)))
        assertFalse(HostForget.canAsk(computer(null, pinned = false)))
    }

    @Test
    fun theConfirmationWarnsWhenAGameIsRunningOnThePc() {
        assertTrue(HostForget.mayCloseRunningGame(computer(PairState.PAIRED, pinned = true, runningGame = 42)))
        assertFalse(HostForget.mayCloseRunningGame(computer(PairState.PAIRED, pinned = true)))
        assertFalse("a PC that is not asked cannot have its game closed by asking", HostForget.mayCloseRunningGame(computer(null, pinned = false, runningGame = 42)))
    }

    @Test
    fun aPcThatCannotBeReachedIsStillDeletable() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        assertNull("nothing to ask, so nothing to report", HostForget.ask(computer(null, pinned = false), "0123456789abcdef", context))
        assertEquals(
            "no address to try is the same to the player as a PC that is off",
            HostForgetResult.UNREACHABLE,
            HostForget.ask(computer(null, pinned = true), "0123456789abcdef", context),
        )
        val nowhere = computer(null, pinned = true).apply {
            // TEST-NET-1: nothing answers there, and the call has to come back with a result, not throw.
            activeAddress = ComputerDetails.AddressTuple("192.0.2.1", 47989)
            httpsPort = 47984
        }
        assertEquals(HostForgetResult.UNREACHABLE, HostForget.ask(nowhere, "0123456789abcdef", context))
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
        val removal = pcView.substringAfter("private fun removeComputer(details: ComputerDetails)").substringBefore("override fun onServiceDisconnected")
        assertTrue(
            "the host is asked inside the removal itself, ahead of the local delete",
            removal.indexOf("HostForget.ask(details") in 0 until removal.indexOf("binder.removeComputer(details)"),
        )
        assertTrue(
            "what the host said reaches the player in something that wraps; a toast is cut at two lines",
            removal.contains("HostForget.stillListedMessage(forgotten)") && removal.contains("NovaSnackbar.show("),
        )
    }
}
