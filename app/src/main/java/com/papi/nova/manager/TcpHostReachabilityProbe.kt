package com.papi.nova.manager

import com.papi.nova.nvstream.http.ComputerDetails
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Tells a sleeping host from an awake one with Polaris down, without needing Polaris.
 *
 * A TCP connect to the host's streaming port is answered by the kernel, not by Polaris:
 * an awake machine with nothing listening refuses it within milliseconds, while a
 * sleeping or powered-off one lets it time out or reports no route. Every known
 * address is tried so a host reached over a VPN or a manual entry is not misread as
 * asleep because its LAN address is unreachable from here.
 */
class TcpHostReachabilityProbe(
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS
) : PolarisStartupCoordinator.HostReachabilityProbe {

    override fun isAwake(computer: ComputerDetails): Boolean {
        val candidates = listOfNotNull(
            computer.activeAddress,
            computer.localAddress,
            computer.manualAddress,
            computer.remoteAddress,
            computer.ipv6Address
        )
            .filter { !it.address.isNullOrBlank() }
            .distinctBy { it.address to it.port }
        for (tuple in candidates) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(tuple.address, tuple.port), timeoutMs)
                }
                return true
            } catch (_: ConnectException) {
                // Refused: the machine answered, so it is up. Polaris is what is missing.
                return true
            } catch (_: SocketTimeoutException) {
                // No answer on this address; a sleeping host looks like this.
            } catch (_: IOException) {
                // No route, or the address is not reachable from this network.
            }
        }
        return false
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1500
    }
}
