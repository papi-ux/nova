package com.papi.nova.computers

import android.content.Context
import com.papi.nova.R
import com.papi.nova.binding.PlatformBinding
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.HostForgetResult
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.http.PairingManager.PairState
import java.io.IOException

/**
 * Deleting a PC here used to leave this device paired over there, and pairing the PC again then
 * added a second entry on the host. A paired PC is now asked to forget this device first.
 */
object HostForget {
    /**
     * Whether there is anything to ask. A PC that was never paired from this device has nothing
     * to forget, and one whose pinned certificate is gone cannot be reached over HTTPS, which is
     * the only road a host takes a revocation on.
     */
    @JvmStatic
    fun canAsk(details: ComputerDetails): Boolean =
        details.pairState == PairState.PAIRED && details.serverCert != null

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
        return try {
            NvHTTP(address, details.httpsPort, uniqueId, details.serverCert, PlatformBinding.getCryptoProvider(context))
                .forgetThisDevice()
        } catch (error: IOException) {
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
