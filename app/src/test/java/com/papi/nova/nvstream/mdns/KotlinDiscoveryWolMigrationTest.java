package com.papi.nova.nvstream.mdns;

import android.content.Context;

import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.nvstream.wol.WakeOnLanSender;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import javax.jmdns.ServiceListener;
import javax.jmdns.impl.NetworkTopologyDiscoveryImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class KotlinDiscoveryWolMigrationTest {
    @Test
    public void discoveryAndWolClassesAreKotlinSources() {
        String[] paths = {
                "src/main/java/com/papi/nova/nvstream/wol/WakeOnLanSender",
                "src/main/java/com/papi/nova/nvstream/mdns/MdnsDiscoveryAgent",
                "src/main/java/com/papi/nova/nvstream/mdns/NsdManagerDiscoveryAgent",
                "src/main/java/com/papi/nova/nvstream/mdns/LegacyNsdManagerDiscoveryAgent",
                "src/main/java/com/papi/nova/nvstream/mdns/JmDNSDiscoveryAgent"
        };

        for (String path : paths) {
            File javaFile = new File(path + ".java");
            File kotlinFile = new File(path + ".kt");
            assertFalse(path + " should no longer be a Java source", javaFile.exists());
            assertTrue(path + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void discoveryAndWolClassesKeepJavaCompatibleApis() throws Exception {
        WakeOnLanSender.class.getConstructor();
        assertEquals(String.class, WakeOnLanSender.class.getMethod("normalizeMacAddress", String.class).getReturnType());
        assertEquals(void.class, WakeOnLanSender.class.getMethod("sendWolPacket", ComputerDetails.class).getReturnType());
        assertEquals(IOException.class, WakeOnLanSender.class
                .getMethod("sendWolPacket", ComputerDetails.class)
                .getExceptionTypes()[0]);

        assertTrue(Modifier.isAbstract(MdnsDiscoveryAgent.class.getModifiers()));
        MdnsDiscoveryAgent.class.getConstructor(MdnsDiscoveryListener.class);
        MdnsDiscoveryAgent.class.getMethod("startDiscovery", int.class);
        MdnsDiscoveryAgent.class.getMethod("stopDiscovery");
        MdnsDiscoveryAgent.class.getMethod("getComputerSet");
        MdnsDiscoveryAgent.class.getDeclaredMethod(
                "reportNewComputer",
                String.class,
                int.class,
                Inet4Address[].class,
                Inet6Address[].class);
        MdnsDiscoveryAgent.class.getDeclaredMethod("getLocalAddress", Inet6Address[].class);
        MdnsDiscoveryAgent.class.getDeclaredMethod("getLinkLocalAddress", Inet6Address[].class);
        MdnsDiscoveryAgent.class.getDeclaredMethod("getBestIpv6Address", Inet6Address[].class);

        NsdManagerDiscoveryAgent.class.getConstructor(Context.class, MdnsDiscoveryListener.class);
        assertTrue(MdnsDiscoveryAgent.class.isAssignableFrom(NsdManagerDiscoveryAgent.class));

        LegacyNsdManagerDiscoveryAgent.class.getConstructor(Context.class, MdnsDiscoveryListener.class);
        assertTrue(MdnsDiscoveryAgent.class.isAssignableFrom(LegacyNsdManagerDiscoveryAgent.class));

        JmDNSDiscoveryAgent.class.getConstructor(Context.class, MdnsDiscoveryListener.class);
        assertTrue(MdnsDiscoveryAgent.class.isAssignableFrom(JmDNSDiscoveryAgent.class));
        assertTrue(ServiceListener.class.isAssignableFrom(JmDNSDiscoveryAgent.class));
        assertTrue(NetworkTopologyDiscoveryImpl.class.isAssignableFrom(
                JmDNSDiscoveryAgent.MyNetworkTopologyDiscovery.class));
    }

    @Test
    public void wakeOnLanNormalizeMacAddressKeepsCommonFormatsAndInvalidFallback() {
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("aa:bb:cc:dd:ee:ff"));
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("AA-BB-CC-DD-EE-FF"));
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("aabbccddeeff"));
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLanSender.normalizeMacAddress("aabb.ccdd.eeff"));
        assertNull(WakeOnLanSender.normalizeMacAddress(null));
        assertNull(WakeOnLanSender.normalizeMacAddress("aa:bb:cc:dd:ee"));
        assertNull(WakeOnLanSender.normalizeMacAddress("aa:bb:cc:dd:ee:xx"));
    }

    @Test
    public void mdnsAgentReportsAndDeduplicatesIpv4Computers() throws Exception {
        RecordingListener listener = new RecordingListener();
        TestMdnsAgent agent = new TestMdnsAgent(listener);
        Inet4Address ipv4 = (Inet4Address) InetAddress.getByName("192.168.1.20");
        Inet6Address ipv6 = (Inet6Address) InetAddress.getByName("2001:4860::20");

        agent.publish("pc", 47989, new Inet4Address[]{ipv4}, new Inet6Address[]{ipv6});
        agent.publish("pc", 47989, new Inet4Address[]{ipv4}, new Inet6Address[]{ipv6});

        assertEquals(1, listener.computers.size());
        assertEquals(1, agent.getComputerSet().size());
        assertEquals("pc", listener.computers.get(0).getName());
        assertSame(ipv4, listener.computers.get(0).getLocalAddress());
        assertSame(ipv6, listener.computers.get(0).getIpv6Address());
        assertEquals(47989, listener.computers.get(0).getPort());
    }

    @Test
    public void mdnsAgentUsesIpv6WhenNoIpv4AddressesExist() throws Exception {
        RecordingListener listener = new RecordingListener();
        TestMdnsAgent agent = new TestMdnsAgent(listener);
        Inet6Address local = (Inet6Address) InetAddress.getByName("fe80::20");
        Inet6Address global = (Inet6Address) InetAddress.getByName("2001:4860::20");

        agent.publish("ipv6-pc", 47990, new Inet4Address[0], new Inet6Address[]{local, global});

        assertEquals(1, listener.computers.size());
        assertEquals("ipv6-pc", listener.computers.get(0).getName());
        assertSame(local, listener.computers.get(0).getLocalAddress());
        assertSame(global, listener.computers.get(0).getIpv6Address());
    }

    @Test
    public void mdnsIpv6HelpersKeepLocalAndBestGlobalSelectionRules() throws Exception {
        Inet6Address local = (Inet6Address) InetAddress.getByName("fe80::1");
        Inet6Address ula = (Inet6Address) InetAddress.getByName("fd00::2");
        Inet6Address ignoredGlobal = (Inet6Address) InetAddress.getByName("2001:4860::3");
        Inet6Address matchingGlobal = (Inet6Address) InetAddress.getByName("2001:4860::1");

        assertSame(local, TestMdnsAgent.local(new Inet6Address[]{local, ula}));
        assertSame(local, TestMdnsAgent.linkLocal(new Inet6Address[]{local, ula}));
        assertSame(matchingGlobal, TestMdnsAgent.best(new Inet6Address[]{
                local,
                ignoredGlobal,
                matchingGlobal,
                ula
        }));
    }

    private static final class TestMdnsAgent extends MdnsDiscoveryAgent {
        TestMdnsAgent(MdnsDiscoveryListener listener) {
            super(listener);
        }

        @Override
        public void startDiscovery(int discoveryIntervalMs) {
        }

        @Override
        public void stopDiscovery() {
        }

        void publish(String name, int port, Inet4Address[] v4Addrs, Inet6Address[] v6Addrs) {
            reportNewComputer(name, port, v4Addrs, v6Addrs);
        }

        static Inet6Address local(Inet6Address[] addresses) {
            return MdnsDiscoveryAgent.getLocalAddress(addresses);
        }

        static Inet6Address linkLocal(Inet6Address[] addresses) {
            return MdnsDiscoveryAgent.getLinkLocalAddress(addresses);
        }

        static Inet6Address best(Inet6Address[] addresses) {
            return MdnsDiscoveryAgent.getBestIpv6Address(addresses);
        }
    }

    private static final class RecordingListener implements MdnsDiscoveryListener {
        final List<MdnsComputer> computers = new ArrayList<>();

        @Override
        public void notifyComputerAdded(MdnsComputer computer) {
            computers.add(computer);
        }

        @Override
        public void notifyDiscoveryFailure(Exception e) {
            throw new AssertionError(e);
        }
    }
}
