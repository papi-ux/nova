package com.papi.nova.nvstream.mdns

import android.content.Context
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.wol.WakeOnLanSender
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import javax.jmdns.ServiceListener
import javax.jmdns.impl.NetworkTopologyDiscoveryImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinDiscoveryWolMigrationTest {
    @Test
    fun discoveryAndWolClassesAreKotlinSources() {
        val paths = arrayOf(
            "src/main/java/com/papi/nova/nvstream/wol/WakeOnLanSender",
            "src/main/java/com/papi/nova/nvstream/mdns/MdnsDiscoveryAgent",
            "src/main/java/com/papi/nova/nvstream/mdns/NsdManagerDiscoveryAgent",
            "src/main/java/com/papi/nova/nvstream/mdns/LegacyNsdManagerDiscoveryAgent",
            "src/main/java/com/papi/nova/nvstream/mdns/JmDNSDiscoveryAgent"
        )

        for (path in paths) {
            val javaFile = File("$path.java")
            val kotlinFile = File("$path.kt")
            assertFalse("$path should no longer be a Java source", javaFile.exists())
            assertTrue("$path should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun discoveryAndWolClassesKeepJavaCompatibleApis() {
        val intType = Int::class.javaPrimitiveType!!

        WakeOnLanSender::class.java.getConstructor()
        assertEquals(String::class.java, WakeOnLanSender::class.java.getMethod("normalizeMacAddress", String::class.java).returnType)
        assertEquals(Void.TYPE, WakeOnLanSender::class.java.getMethod("sendWolPacket", ComputerDetails::class.java).returnType)
        assertEquals(
            IOException::class.java,
            WakeOnLanSender::class.java.getMethod("sendWolPacket", ComputerDetails::class.java).exceptionTypes[0]
        )

        assertTrue(Modifier.isAbstract(MdnsDiscoveryAgent::class.java.modifiers))
        MdnsDiscoveryAgent::class.java.getConstructor(MdnsDiscoveryListener::class.java)
        MdnsDiscoveryAgent::class.java.getMethod("startDiscovery", intType)
        MdnsDiscoveryAgent::class.java.getMethod("stopDiscovery")
        MdnsDiscoveryAgent::class.java.getMethod("getComputerSet")
        MdnsDiscoveryAgent::class.java.getDeclaredMethod(
            "reportNewComputer",
            String::class.java,
            intType,
            Array<Inet4Address>::class.java,
            Array<Inet6Address>::class.java
        )
        MdnsDiscoveryAgent::class.java.getDeclaredMethod("getLocalAddress", Array<Inet6Address>::class.java)
        MdnsDiscoveryAgent::class.java.getDeclaredMethod("getLinkLocalAddress", Array<Inet6Address>::class.java)
        MdnsDiscoveryAgent::class.java.getDeclaredMethod("getBestIpv6Address", Array<Inet6Address>::class.java)

        NsdManagerDiscoveryAgent::class.java.getConstructor(Context::class.java, MdnsDiscoveryListener::class.java)
        assertTrue(MdnsDiscoveryAgent::class.java.isAssignableFrom(NsdManagerDiscoveryAgent::class.java))

        LegacyNsdManagerDiscoveryAgent::class.java.getConstructor(Context::class.java, MdnsDiscoveryListener::class.java)
        assertTrue(MdnsDiscoveryAgent::class.java.isAssignableFrom(LegacyNsdManagerDiscoveryAgent::class.java))

        JmDNSDiscoveryAgent::class.java.getConstructor(Context::class.java, MdnsDiscoveryListener::class.java)
        assertTrue(MdnsDiscoveryAgent::class.java.isAssignableFrom(JmDNSDiscoveryAgent::class.java))
        assertTrue(ServiceListener::class.java.isAssignableFrom(JmDNSDiscoveryAgent::class.java))
        assertTrue(
            NetworkTopologyDiscoveryImpl::class.java.isAssignableFrom(
                JmDNSDiscoveryAgent.MyNetworkTopologyDiscovery::class.java
            )
        )
    }

    @Test
    fun wakeOnLanNormalizeMacAddressKeepsCommonFormatsAndInvalidFallback() {
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("AA-BB-CC-DD-EE-FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("aabbccddeeff"))
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("aabb.ccdd.eeff"))
        assertNull(WakeOnLanSender.normalizeMacAddress(null))
        assertNull(WakeOnLanSender.normalizeMacAddress("aa:bb:cc:dd:ee"))
        assertNull(WakeOnLanSender.normalizeMacAddress("aa:bb:cc:dd:ee:xx"))
    }

    @Test
    fun mdnsAgentReportsAndDeduplicatesIpv4Computers() {
        val listener = RecordingListener()
        val agent = TestMdnsAgent(listener)
        val ipv4 = InetAddress.getByName("192.168.1.20") as Inet4Address
        val ipv6 = InetAddress.getByName("2001:4860::20") as Inet6Address

        agent.publish("pc", 47989, arrayOf(ipv4), arrayOf(ipv6))
        agent.publish("pc", 47989, arrayOf(ipv4), arrayOf(ipv6))

        assertEquals(1, listener.computers.size)
        assertEquals(1, agent.getComputerSet().size)
        assertEquals("pc", listener.computers[0].getName())
        assertSame(ipv4, listener.computers[0].getLocalAddress())
        assertSame(ipv6, listener.computers[0].getIpv6Address())
        assertEquals(47989, listener.computers[0].getPort())
    }

    @Test
    fun mdnsAgentUsesIpv6WhenNoIpv4AddressesExist() {
        val listener = RecordingListener()
        val agent = TestMdnsAgent(listener)
        val local = InetAddress.getByName("fe80::20") as Inet6Address
        val global = InetAddress.getByName("2001:4860::20") as Inet6Address

        agent.publish("ipv6-pc", 47990, emptyArray(), arrayOf(local, global))

        assertEquals(1, listener.computers.size)
        assertEquals("ipv6-pc", listener.computers[0].getName())
        assertSame(local, listener.computers[0].getLocalAddress())
        assertSame(global, listener.computers[0].getIpv6Address())
    }

    @Test
    fun mdnsIpv6HelpersKeepLocalAndBestGlobalSelectionRules() {
        val local = InetAddress.getByName("fe80::1") as Inet6Address
        val ula = InetAddress.getByName("fd00::2") as Inet6Address
        val ignoredGlobal = InetAddress.getByName("2001:4860::3") as Inet6Address
        val matchingGlobal = InetAddress.getByName("2001:4860::1") as Inet6Address

        assertSame(local, TestMdnsAgent.local(arrayOf(local, ula)))
        assertSame(local, TestMdnsAgent.linkLocal(arrayOf(local, ula)))
        assertSame(
            matchingGlobal,
            TestMdnsAgent.best(
                arrayOf(
                    local,
                    ignoredGlobal,
                    matchingGlobal,
                    ula
                )
            )
        )
    }

    private class TestMdnsAgent(listener: MdnsDiscoveryListener) : MdnsDiscoveryAgent(listener) {
        override fun startDiscovery(discoveryIntervalMs: Int) = Unit

        override fun stopDiscovery() = Unit

        fun publish(name: String, port: Int, v4Addrs: Array<Inet4Address>, v6Addrs: Array<Inet6Address>) {
            reportNewComputer(name, port, v4Addrs, v6Addrs)
        }

        companion object {
            fun local(addresses: Array<Inet6Address>): Inet6Address? = getLocalAddress(addresses)

            fun linkLocal(addresses: Array<Inet6Address>): Inet6Address? = getLinkLocalAddress(addresses)

            fun best(addresses: Array<Inet6Address>): Inet6Address? = getBestIpv6Address(addresses)
        }
    }

    private class RecordingListener : MdnsDiscoveryListener {
        val computers = ArrayList<MdnsComputer>()

        override fun notifyComputerAdded(computer: MdnsComputer) {
            computers.add(computer)
        }

        override fun notifyDiscoveryFailure(e: Exception) {
            throw AssertionError(e)
        }
    }
}
