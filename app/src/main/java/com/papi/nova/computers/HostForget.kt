package com.papi.nova.computers

import android.content.Context
import com.papi.nova.R
import com.papi.nova.binding.PlatformBinding
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.HostForgetResult
import com.papi.nova.nvstream.http.NvHTTP

/**
 * Deleting a PC here used to leave this device paired over there, and pairing the PC again then
 * added a second entry on the host. A paired PC is now asked to forget this device first.
 */
object HostForget {
    /**
     * Whether there is anything to ask: a PC this device paired with. The pinned certificate is
     * the proof, because it is saved with the PC. The pair state is not, and is only known once
     * the PC has answered in this session, so going by it a PC that was switched off when Nova
     * started was deleted without the host being asked and without a word about it.
     */
    @JvmStatic
    fun canAsk(details: ComputerDetails): Boolean = details.serverCert != null

    /** A game running on the PC can be closed by this: a host with no paired device left ends it. */
    @JvmStatic
    fun mayCloseRunningGame(details: ComputerDetails): Boolean = canAsk(details) && details.runningGameId != 0

    /**
     * Asks, before the PC is deleted: the pinned certificate goes away with it. Null when there
     * was nothing to ask. Blocks on the network, so not for the main thread.
     */
    @JvmStatic
    fun ask(details: ComputerDetails, uniqueId: String, context: Context): HostForgetResult? {
        if (!canAsk(details)) {
            return null
        }
        val address = details.activeAddress ?: return HostForgetResult.UNREACHABLE
        // Nothing here may stop the PC from being deleted. Building the client can throw more than
        // IOException (a key that will not load, an address that will not parse), and whatever it
        // is, the host was not told.
        return try {
            NvHTTP(address, details.httpsPort, uniqueId, details.serverCert, PlatformBinding.getCryptoProvider(context))
                .forgetThisDevice()
        } catch (error: Exception) {
            HostForgetResult.UNREACHABLE
        }
    }

    /** The sentence owed to the player when the host still lists this device, or null when it does not. */
    @JvmStatic
    fun stillListedMessage(result: HostForgetResult?): Int? = when (result) {
        null, HostForgetResult.FORGOTTEN -> null
        HostForgetResult.UNREACHABLE -> R.string.delete_pc_host_unreachable
        HostForgetResult.REFUSED -> R.string.delete_pc_host_refused
        HostForgetResult.UNSUPPORTED -> R.string.delete_pc_host_unsupported
    }
}
