package com.papi.nova.nvstream.wol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Locale;

import com.papi.nova.nvstream.http.ComputerDetails;

public class WakeOnLanSender {
    // These ports will always be tried as-is.
    private static final int[] STATIC_PORTS_TO_TRY = new int[] {
        9, // Standard WOL port (privileged port)
        47009, // Port opened by Moonlight Internet Hosting Tool for WoL (non-privileged port)
    };

    // These ports will be offset by the base port number (47989) to support alternate ports.
    private static final int[] DYNAMIC_PORTS_TO_TRY = new int[] {
        47998, 47999, 48000, 48002, 48010, // Ports opened by GFE
    };

    private static void sendPacketsForAddress(InetAddress address, int httpPort, DatagramSocket sock, byte[] payload) throws IOException {
        IOException lastException = null;
        boolean sentWolPacket = false;

        // Try the static ports
        for (int port : STATIC_PORTS_TO_TRY) {
            try {
                DatagramPacket dp = new DatagramPacket(payload, payload.length);
                dp.setAddress(address);
                dp.setPort(port);
                sock.send(dp);
                sentWolPacket = true;
            } catch (IOException e) {
                e.printStackTrace();
                lastException = e;
            }
        }

        // Try the dynamic ports
        for (int port : DYNAMIC_PORTS_TO_TRY) {
            try {
                DatagramPacket dp = new DatagramPacket(payload, payload.length);
                dp.setAddress(address);
                dp.setPort((port - 47989) + httpPort);
                sock.send(dp);
                sentWolPacket = true;
            } catch (IOException e) {
                e.printStackTrace();
                lastException = e;
            }
        }

        if (!sentWolPacket) {
            throw lastException;
        }
    }
    
    public static void sendWolPacket(ComputerDetails computer) throws IOException {
        byte[] payload = createWolPayload(computer);
        IOException lastException = null;
        boolean sentWolPacket = false;

        try (final DatagramSocket sock = new DatagramSocket(0)) {
            // Try all resolved remote and local addresses and broadcast addresses.
            // The broadcast address is required to avoid stale ARP cache entries
            // making the sleeping machine unreachable.
            for (ComputerDetails.AddressTuple address : new ComputerDetails.AddressTuple[] {
                    computer.localAddress, computer.remoteAddress,
                    computer.manualAddress, computer.ipv6Address,
            }) {
                if (address == null) {
                    continue;
                }

                try {
                    sendPacketsForAddress(InetAddress.getByName("255.255.255.255"), address.port, sock, payload);
                    sentWolPacket = true;
                } catch (IOException e) {
                    e.printStackTrace();
                    lastException = e;
                }

                try {
                    for (InetAddress resolvedAddress : InetAddress.getAllByName(address.address)) {
                        try {
                            sendPacketsForAddress(resolvedAddress, address.port, sock, payload);
                            sentWolPacket = true;
                        } catch (IOException e) {
                            e.printStackTrace();
                            lastException = e;
                        }
                    }
                } catch (IOException e) {
                    // We may have addresses that don't resolve on this subnet,
                    // but don't throw and exit the whole function if that happens.
                    // We'll throw it at the end if we didn't send a single packet.
                    e.printStackTrace();
                    lastException = e;
                }
            }
        }

        // Propagate the DNS resolution exception if we didn't
        // manage to get a single packet out to the host.
        if (!sentWolPacket && lastException != null) {
            throw lastException;
        }
    }
    
    public static String normalizeMacAddress(String macAddress) {
        if (macAddress == null) {
            return null;
        }

        String compactMacAddress = macAddress.trim()
                .replace(":", "")
                .replace("-", "")
                .replace(".", "");

        if (!compactMacAddress.matches("(?i)[0-9a-f]{12}")) {
            return null;
        }

        compactMacAddress = compactMacAddress.toUpperCase(Locale.US);
        StringBuilder normalized = new StringBuilder(17);
        for (int i = 0; i < compactMacAddress.length(); i += 2) {
            if (i > 0) {
                normalized.append(':');
            }
            normalized.append(compactMacAddress, i, i + 2);
        }

        return normalized.toString();
    }

    private static byte[] macStringToBytes(String macAddress) throws IOException {
        String normalizedMacAddress = normalizeMacAddress(macAddress);
        if (normalizedMacAddress == null) {
            throw new IOException("Invalid Wake-on-LAN MAC address");
        }

        byte[] macBytes = new byte[6];

        for (int i = 0; i < macBytes.length; i++) {
            int segmentStart = i * 3;
            macBytes[i] = (byte) Integer.parseInt(normalizedMacAddress.substring(segmentStart, segmentStart + 2), 16);
        }

        return macBytes;
    }

    private static byte[] createWolPayload(ComputerDetails computer) throws IOException {
        byte[] payload = new byte[102];
        byte[] macAddress = macStringToBytes(computer.macAddress);
        int i;
        
        // 6 bytes of FF
        for (i = 0; i < 6; i++) {
            payload[i] = (byte)0xFF;
        }
        
        // 16 repetitions of the MAC address
        for (int j = 0; j < 16; j++) {
            System.arraycopy(macAddress, 0, payload, i, macAddress.length);
            i += macAddress.length;
        }
        
        return payload;
    }
}
