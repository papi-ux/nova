package com.papi.nova.nvstream.mdns

import com.papi.nova.LimeLog
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.ArrayList
import java.util.HashSet

abstract class MdnsDiscoveryAgent(
    @JvmField
    protected var listener: MdnsDiscoveryListener,
) {
    @JvmField
    protected var computers: HashSet<MdnsComputer> = HashSet()

    abstract fun startDiscovery(discoveryIntervalMs: Int)

    abstract fun stopDiscovery()

    protected open fun reportNewComputer(
        name: String,
        port: Int,
        v4Addrs: Array<Inet4Address>,
        v6Addrs: Array<Inet6Address>,
    ) {
        LimeLog.info("mDNS: " + name + " has " + v4Addrs.size + " IPv4 addresses")
        LimeLog.info("mDNS: " + name + " has " + v6Addrs.size + " IPv6 addresses")

        val v6GlobalAddr = getBestIpv6Address(v6Addrs)

        // Add a computer object for each IPv4 address reported by the PC
        for (v4Addr in v4Addrs) {
            synchronized(computers) {
                val computer = MdnsComputer(name, v4Addr, v6GlobalAddr, port)
                if (computers.add(computer)) {
                    // This was a new entry
                    listener.notifyComputerAdded(computer)
                }
            }
        }

        // If there were no IPv4 addresses, use IPv6 for registration
        if (v4Addrs.isEmpty()) {
            val v6LocalAddr = getLocalAddress(v6Addrs)

            if (v6LocalAddr != null || v6GlobalAddr != null) {
                synchronized(computers) {
                    val computer = MdnsComputer(name, v6LocalAddr, v6GlobalAddr, port)
                    if (computers.add(computer)) {
                        // This was a new entry
                        listener.notifyComputerAdded(computer)
                    }
                }
            }
        }
    }

    open fun getComputerSet(): List<MdnsComputer> {
        synchronized(computers) {
            return ArrayList(computers)
        }
    }

    companion object {
        @JvmStatic
        fun getLocalAddress(addresses: Array<Inet6Address>): Inet6Address? {
            for (addr in addresses) {
                if (addr.isLinkLocalAddress || addr.isSiteLocalAddress) {
                    return addr
                } else if ((addr.address[0].toInt() and 0xfe) == 0xfc) {
                    // fc00::/7 - ULAs
                    return addr
                }
            }

            return null
        }

        @JvmStatic
        fun getLinkLocalAddress(addresses: Array<Inet6Address>): Inet6Address? {
            for (addr in addresses) {
                if (addr.isLinkLocalAddress) {
                    LimeLog.info("Found link-local address: " + addr.hostAddress)
                    return addr
                }
            }

            return null
        }

        @JvmStatic
        fun getBestIpv6Address(addresses: Array<Inet6Address>): Inet6Address? {
            // First try to find a link local address, so we can match the interface identifier
            // with a global address (this will work for SLAAC but not DHCPv6).
            val linkLocalAddr = getLinkLocalAddress(addresses)

            // We will try once to match a SLAAC interface suffix, then
            // pick the first matching address
            for (tries in 0 until 2) {
                // We assume the addresses are already sorted in descending order
                // of preference from Bonjour.
                for (addr in addresses) {
                    if (addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isLoopbackAddress) {
                        // Link-local, site-local, and loopback aren't global
                        LimeLog.info("Ignoring non-global address: " + addr.hostAddress)
                        continue
                    }

                    val addrBytes = addr.address

                    if (addrBytes[0].toInt() == 0x20 && addrBytes[1].toInt() == 0x02) {
                        // 2002::/16
                        // 6to4 has horrible performance
                        LimeLog.info("Ignoring 6to4 address: " + addr.hostAddress)
                        continue
                    } else if (
                        addrBytes[0].toInt() == 0x20 &&
                        addrBytes[1].toInt() == 0x01 &&
                        addrBytes[2].toInt() == 0x00 &&
                        addrBytes[3].toInt() == 0x00
                    ) {
                        // 2001::/32
                        // Teredo also has horrible performance
                        LimeLog.info("Ignoring Teredo address: " + addr.hostAddress)
                        continue
                    } else if ((addrBytes[0].toInt() and 0xfe) == 0xfc) {
                        // fc00::/7
                        // ULAs aren't global
                        LimeLog.info("Ignoring ULA: " + addr.hostAddress)
                        continue
                    }

                    // Compare the final 64-bit interface identifier and skip the address
                    // if it doesn't match our link-local address.
                    if (linkLocalAddr != null && tries == 0) {
                        var matched = true

                        for (i in 8 until 16) {
                            if (linkLocalAddr.address[i] != addr.address[i]) {
                                matched = false
                                break
                            }
                        }

                        if (!matched) {
                            LimeLog.info("Ignoring non-matching global address: " + addr.hostAddress)
                            continue
                        }
                    }

                    return addr
                }
            }

            return null
        }
    }
}
