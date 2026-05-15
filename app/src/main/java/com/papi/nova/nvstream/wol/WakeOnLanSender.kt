package com.papi.nova.nvstream.wol

import com.papi.nova.LimeLog
import com.papi.nova.nvstream.http.ComputerDetails
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Scanner

class WakeOnLanSender {
    companion object {
        // These ports will always be tried as-is.
        private val STATIC_PORTS_TO_TRY = intArrayOf(
            9, // Standard WOL port (privileged port)
            47009, // Port opened by Moonlight Internet Hosting Tool for WoL (non-privileged port)
        )

        // These ports will be offset by the base port number (47989) to support alternate ports.
        private val DYNAMIC_PORTS_TO_TRY = intArrayOf(
            47998,
            47999,
            48000,
            48002,
            48010, // Ports opened by GFE
        )

        @Throws(IOException::class)
        private fun sendPacketsForAddress(
            address: InetAddress,
            httpPort: Int,
            sock: DatagramSocket,
            payload: ByteArray,
        ) {
            var lastException: IOException? = null
            var sentWolPacket = false

            // Try the static ports
            for (port in STATIC_PORTS_TO_TRY) {
                try {
                    val dp = DatagramPacket(payload, payload.size)
                    dp.address = address
                    dp.port = port
                    sock.send(dp)
                    sentWolPacket = true
                } catch (e: IOException) {
                    e.printStackTrace()
                    lastException = e
                }
            }

            // Try the dynamic ports
            for (port in DYNAMIC_PORTS_TO_TRY) {
                try {
                    val dp = DatagramPacket(payload, payload.size)
                    dp.address = address
                    dp.port = port - 47989 + httpPort
                    sock.send(dp)
                    sentWolPacket = true
                } catch (e: IOException) {
                    e.printStackTrace()
                    lastException = e
                }
            }

            if (!sentWolPacket) {
                throw lastException ?: IOException("Failed to send WOL packet")
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun sendWolPacket(computer: ComputerDetails) {
            val payload = createWolPayload(computer)
            var lastException: IOException? = null
            var sentWolPacket = false

            DatagramSocket(0).use { sock ->
                // Try all resolved remote and local addresses and broadcast addresses.
                // The broadcast address is required to avoid stale ARP cache entries
                // making the sleeping machine unreachable.
                val addresses = arrayOf(
                    computer.localAddress,
                    computer.remoteAddress,
                    computer.manualAddress,
                    computer.ipv6Address,
                )

                for (address in addresses) {
                    if (address == null) {
                        continue
                    }

                    try {
                        sendPacketsForAddress(
                            InetAddress.getByName("255.255.255.255"),
                            address.port,
                            sock,
                            payload,
                        )
                        sentWolPacket = true
                    } catch (e: IOException) {
                        e.printStackTrace()
                        lastException = e
                    }

                    try {
                        for (resolvedAddress in InetAddress.getAllByName(address.address)) {
                            try {
                                sendPacketsForAddress(resolvedAddress, address.port, sock, payload)
                                sentWolPacket = true
                            } catch (e: IOException) {
                                e.printStackTrace()
                                lastException = e
                            }
                        }
                    } catch (e: IOException) {
                        // We may have addresses that don't resolve on this subnet,
                        // but don't throw and exit the whole function if that happens.
                        // We'll throw it at the end if we didn't send a single packet.
                        e.printStackTrace()
                        lastException = e
                    }
                }
            }

            // Propagate the DNS resolution exception if we didn't
            // manage to get a single packet out to the host.
            if (!sentWolPacket && lastException != null) {
                throw lastException
            }
        }

        @JvmStatic
        fun normalizeMacAddress(macAddress: String?): String? {
            if (macAddress == null) {
                return null
            }

            val hex = macAddress.trim()
                .replace(":", "")
                .replace("-", "")
                .replace(".", "")
            if (hex.length != 12) {
                return null
            }

            val normalized = StringBuilder(17)
            var i = 0
            while (i < hex.length) {
                val high = Character.digit(hex[i], 16)
                val low = Character.digit(hex[i + 1], 16)
                if (high < 0 || low < 0) {
                    return null
                }
                if (normalized.isNotEmpty()) {
                    normalized.append(':')
                }
                normalized.append(hex[i].uppercaseChar())
                normalized.append(hex[i + 1].uppercaseChar())
                i += 2
            }
            return normalized.toString()
        }

        private fun macStringToBytes(macAddress: String): ByteArray {
            val macBytes = ByteArray(6)

            Scanner(macAddress).useDelimiter(":").use { scan ->
                var i = 0
                while (i < macBytes.size && scan.hasNext()) {
                    try {
                        macBytes[i] = scan.next().toInt(16).toByte()
                    } catch (e: NumberFormatException) {
                        LimeLog.warning("Malformed MAC address: $macAddress (index: $i)")
                        break
                    }
                    i++
                }
                return macBytes
            }
        }

        private fun createWolPayload(computer: ComputerDetails): ByteArray {
            val payload = ByteArray(102)
            val macAddress = macStringToBytes(computer.macAddress)
            var i: Int

            // 6 bytes of FF
            i = 0
            while (i < 6) {
                payload[i] = 0xFF.toByte()
                i++
            }

            // 16 repetitions of the MAC address
            for (j in 0 until 16) {
                System.arraycopy(macAddress, 0, payload, i, macAddress.size)
                i += macAddress.size
            }

            return payload
        }
    }
}
