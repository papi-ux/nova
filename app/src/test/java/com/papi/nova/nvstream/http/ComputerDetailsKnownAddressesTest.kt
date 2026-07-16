package com.papi.nova.nvstream.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ComputerDetailsKnownAddressesTest {
    @Test
    fun addressTupleRejectsAnEmptyCanonicalAddress() {
        assertThrows(IllegalArgumentException::class.java) {
            ComputerDetails.AddressTuple(" . ", 47989)
        }
    }

    @Test
    fun addressTupleRejectsPortsOutsideTheTcpRange() {
        assertThrows(IllegalArgumentException::class.java) {
            ComputerDetails.AddressTuple("pc.example.test", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComputerDetails.AddressTuple("pc.example.test", 65_536)
        }
    }

    @Test
    fun updateRejectsDifferentComputerUuidBeforeMutatingState() {
        val existing = ComputerDetails().apply {
            uuid = "trusted-host"
            name = "Trusted Host"
            rememberAddress(ComputerDetails.AddressTuple("192.168.1.25", 47989))
        }
        val other = ComputerDetails().apply {
            uuid = "different-host"
            name = "Different Host"
            rememberAddress(ComputerDetails.AddressTuple("attacker.example.test", 47989))
        }

        assertThrows(IllegalArgumentException::class.java) {
            existing.update(other)
        }

        assertEquals("trusted-host", existing.uuid)
        assertEquals("Trusted Host", existing.name)
        assertEquals(
            listOf(ComputerDetails.AddressTuple("192.168.1.25", 47989)),
            existing.knownAddresses
        )
    }

    @Test
    fun genericUpdateDoesNotPromoteAdvertisedRoutesIntoVerifiedHistory() {
        val existing = ComputerDetails().apply {
            uuid = "host-uuid"
            rememberAddress(ComputerDetails.AddressTuple("verified.example.test", 47989))
        }
        val advertised = ComputerDetails().apply {
            uuid = "host-uuid"
            localAddress = ComputerDetails.AddressTuple("192.168.1.99", 47989)
            remoteAddress = ComputerDetails.AddressTuple("wan.example.test", 47989)
            manualAddress = ComputerDetails.AddressTuple("manual.example.test", 47989)
            ipv6Address = ComputerDetails.AddressTuple("2001:db8::99", 47989)
            activeAddress = ComputerDetails.AddressTuple("active.example.test", 47989)
        }

        existing.update(advertised)

        assertEquals(
            listOf(ComputerDetails.AddressTuple("verified.example.test", 47989)),
            existing.knownAddresses
        )
    }

    @Test
    fun updateRetainsPreviousAndIncomingRememberedRoutesForTheSameComputer() {
        val existing = ComputerDetails().apply {
            uuid = "host-uuid"
            localAddress = ComputerDetails.AddressTuple("192.168.1.25", 47989)
            rememberAddress(localAddress)
        }
        val viaTailnet = ComputerDetails().apply {
            uuid = "host-uuid"
            localAddress = ComputerDetails.AddressTuple("100.100.20.30", 47989)
            activeAddress = ComputerDetails.AddressTuple("pc-papi.tailnet.ts.net", 47989)
            rememberAddress(activeAddress)
        }

        existing.update(viaTailnet)

        assertEquals(ComputerDetails.AddressTuple("100.100.20.30", 47989), existing.localAddress)
        assertEquals(
            listOf(
                ComputerDetails.AddressTuple("192.168.1.25", 47989),
                ComputerDetails.AddressTuple("pc-papi.tailnet.ts.net", 47989)
            ),
            existing.knownAddresses
        )
    }

    @Test
    fun verifiedPollPromotesOnlyTheSuccessfulActiveRoute() {
        val existing = ComputerDetails().apply {
            uuid = "host-uuid"
            rememberAddress(ComputerDetails.AddressTuple("previous.example.test", 47989))
        }
        val polled = ComputerDetails().apply {
            uuid = "host-uuid"
            localAddress = ComputerDetails.AddressTuple("advertised-lan.example.test", 47989)
            remoteAddress = ComputerDetails.AddressTuple("advertised-wan.example.test", 47989)
            activeAddress = ComputerDetails.AddressTuple("successful.example.test", 47989)
        }

        existing.updateFromVerifiedPoll(polled)

        assertEquals(ComputerDetails.AddressTuple("advertised-lan.example.test", 47989), existing.localAddress)
        assertEquals(
            listOf(
                ComputerDetails.AddressTuple("previous.example.test", 47989),
                ComputerDetails.AddressTuple("successful.example.test", 47989)
            ),
            existing.knownAddresses
        )
    }

    @Test
    fun verifiedPollRequiresANonBlankComputerUuidBeforeMutatingState() {
        val existing = ComputerDetails().apply {
            name = "Before"
        }
        val invalidPoll = ComputerDetails().apply {
            name = "After"
            activeAddress = ComputerDetails.AddressTuple("active.example.test", 47989)
        }

        assertThrows(IllegalArgumentException::class.java) {
            existing.updateFromVerifiedPoll(invalidPoll)
        }

        assertEquals("Before", existing.name)
        assertEquals(emptyList<ComputerDetails.AddressTuple>(), existing.knownAddresses)
    }

    @Test
    fun verifiedPollRequiresAnActiveRouteBeforeMutatingState() {
        val existing = ComputerDetails().apply {
            uuid = "host-uuid"
            name = "Before"
        }
        val invalidPoll = ComputerDetails().apply {
            uuid = "host-uuid"
            name = "After"
        }

        assertThrows(IllegalArgumentException::class.java) {
            existing.updateFromVerifiedPoll(invalidPoll)
        }

        assertEquals("Before", existing.name)
        assertEquals(emptyList<ComputerDetails.AddressTuple>(), existing.knownAddresses)
    }

    @Test
    fun rememberAddressDeduplicatesEquivalentDnsNames() {
        val details = ComputerDetails()

        details.rememberAddress(ComputerDetails.AddressTuple(" PC-PAPI.TAILNET.TS.NET. ", 47989))
        details.rememberAddress(ComputerDetails.AddressTuple("pc-papi.tailnet.ts.net", 47989))

        assertEquals(1, details.knownAddresses.size)
        assertEquals("pc-papi.tailnet.ts.net", details.knownAddresses.single().address)
    }

    @Test
    fun rememberAddressKeepsDifferentPortsAsDifferentEndpoints() {
        val details = ComputerDetails()

        details.rememberAddress(ComputerDetails.AddressTuple("pc-papi.tailnet.ts.net", 47989))
        details.rememberAddress(ComputerDetails.AddressTuple("pc-papi.tailnet.ts.net", 48000))

        assertEquals(2, details.knownAddresses.size)
    }

    @Test
    fun knownAddressesReturnsADeepDefensiveSnapshot() {
        val details = ComputerDetails()
        details.rememberAddress(ComputerDetails.AddressTuple("192.168.1.25", 47989))
        val snapshot = details.knownAddresses

        snapshot.single().address = "mutated.example.test"
        snapshot.single().port = 1
        details.rememberAddress(ComputerDetails.AddressTuple("pc-papi.tailnet.ts.net", 47989))

        assertEquals("mutated.example.test", snapshot.single().address)
        assertEquals(1, snapshot.single().port)
        assertEquals(
            ComputerDetails.AddressTuple("192.168.1.25", 47989),
            details.knownAddresses.first()
        )
        assertEquals(2, details.knownAddresses.size)
    }

    @Test
    fun rememberAddressBoundsHistoryAndKeepsMostRecentEndpoints() {
        val details = ComputerDetails()
        repeat(ComputerDetails.MAX_KNOWN_ADDRESSES + 2) { index ->
            details.rememberAddress(ComputerDetails.AddressTuple("100.64.0.${index + 1}", 47989))
        }

        assertEquals(ComputerDetails.MAX_KNOWN_ADDRESSES, details.knownAddresses.size)
        assertEquals(ComputerDetails.AddressTuple("100.64.0.3", 47989), details.knownAddresses.first())
        assertEquals(
            ComputerDetails.AddressTuple("100.64.0.${ComputerDetails.MAX_KNOWN_ADDRESSES + 2}", 47989),
            details.knownAddresses.last()
        )
    }
}
