package com.papi.nova.nvstream;

import com.papi.nova.nvstream.av.ByteBufferDescriptor;
import com.papi.nova.nvstream.av.video.VideoDecoderRenderer;
import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.nvstream.http.HostHttpResponseException;
import com.papi.nova.nvstream.mdns.MdnsComputer;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class KotlinNvstreamLeafMigrationTest {
    @Test
    public void nvstreamLeafClassesAreKotlinSources() {
        String[] paths = {
                "src/main/java/com/papi/nova/nvstream/ConnectionContext",
                "src/main/java/com/papi/nova/nvstream/av/ByteBufferDescriptor",
                "src/main/java/com/papi/nova/nvstream/av/video/VideoDecoderRenderer",
                "src/main/java/com/papi/nova/nvstream/http/HostHttpResponseException",
                "src/main/java/com/papi/nova/nvstream/mdns/MdnsComputer"
        };

        for (String path : paths) {
            File javaFile = new File(path + ".java");
            File kotlinFile = new File(path + ".kt");
            assertFalse(path + " should no longer be a Java source", javaFile.exists());
            assertTrue(path + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void hostHttpResponseExceptionKeepsJavaApiAndMessage() {
        HostHttpResponseException exception = new HostHttpResponseException(599, "launch failed");

        assertTrue(exception instanceof IOException);
        assertEquals(599, exception.getErrorCode());
        assertEquals("launch failed", exception.getErrorMessage());
        assertEquals("Host PC returned error: launch failed (Error code: 599)", exception.getMessage());
    }

    @Test
    public void byteBufferDescriptorKeepsMutableFieldsAndCopyBehavior() {
        byte[] payload = new byte[] {1, 2, 3, 4, 5};
        ByteBufferDescriptor descriptor = new ByteBufferDescriptor(payload, 1, 3);
        ByteBufferDescriptor next = new ByteBufferDescriptor(new byte[] {9}, 0, 1);
        descriptor.nextDescriptor = next;

        ByteBufferDescriptor copy = new ByteBufferDescriptor(descriptor);

        assertSame(payload, copy.data);
        assertEquals(1, copy.offset);
        assertEquals(3, copy.length);
        assertEquals(next, descriptor.nextDescriptor);
        assertEquals(null, copy.nextDescriptor);

        byte[] replacement = new byte[] {7, 8};
        descriptor.reinitialize(replacement, 0, 2);

        assertSame(replacement, descriptor.data);
        assertEquals(0, descriptor.offset);
        assertEquals(2, descriptor.length);
        assertEquals(null, descriptor.nextDescriptor);
    }

    @Test
    public void connectionContextKeepsPublicMutableFields() {
        ConnectionContext context = new ConnectionContext();
        ComputerDetails.AddressTuple address = new ComputerDetails.AddressTuple("10.0.0.2", 47984);
        SecretKeySpec key = new SecretKeySpec(new byte[] {1, 2, 3, 4}, "AES");

        context.serverAddress = address;
        context.httpsPort = 47989;
        context.isNvidiaServerSoftware = true;
        context.serverCert = null;
        context.streamConfig = null;
        context.connListener = null;
        context.riKey = key;
        context.riKeyId = 7;
        context.serverAppVersion = "1.2.3.4";
        context.serverGfeVersion = "3.27";
        context.serverCodecModeSupport = 15;
        context.serverMaxLaunchRefreshRate = 144;
        context.rtspSessionUrl = "rtsp://session";
        context.sessionToken = "token";
        context.currentGameOwnedByClient = Boolean.FALSE;
        context.currentGameOwnerName = "host-user";
        context.watchOnlyRequested = true;
        context.negotiatedWidth = 1920;
        context.negotiatedHeight = 1080;
        context.negotiatedHdr = true;
        context.negotiatedLaunchRefreshRate = 119.88f;
        context.negotiatedRemoteStreaming = StreamConfiguration.STREAM_CFG_REMOTE;
        context.negotiatedPacketSize = 1200;
        context.videoCapabilities = 255;

        assertSame(address, context.serverAddress);
        assertEquals(47989, context.httpsPort);
        assertTrue(context.isNvidiaServerSoftware);
        assertEquals(null, context.streamConfig);
        assertSame(key, context.riKey);
        assertEquals(7, context.riKeyId);
        assertEquals("1.2.3.4", context.serverAppVersion);
        assertEquals("3.27", context.serverGfeVersion);
        assertEquals(15, context.serverCodecModeSupport);
        assertEquals(144, context.serverMaxLaunchRefreshRate);
        assertEquals("rtsp://session", context.rtspSessionUrl);
        assertEquals("token", context.sessionToken);
        assertEquals(Boolean.FALSE, context.currentGameOwnedByClient);
        assertEquals("host-user", context.currentGameOwnerName);
        assertTrue(context.watchOnlyRequested);
        assertEquals(1920, context.negotiatedWidth);
        assertEquals(1080, context.negotiatedHeight);
        assertTrue(context.negotiatedHdr);
        assertEquals(119.88f, context.negotiatedLaunchRefreshRate, 0.001f);
        assertEquals(StreamConfiguration.STREAM_CFG_REMOTE, context.negotiatedRemoteStreaming);
        assertEquals(1200, context.negotiatedPacketSize);
        assertEquals(255, context.videoCapabilities);
    }

    @Test
    public void mdnsComputerKeepsEqualityHashAndStringSemantics() throws Exception {
        InetAddress ipv4 = InetAddress.getByName("192.168.0.10");
        Inet6Address ipv6 = (Inet6Address) InetAddress.getByName("::1");
        MdnsComputer first = new MdnsComputer("pc", ipv4, ipv6, 47989);
        MdnsComputer same = new MdnsComputer("pc", ipv4, ipv6, 47989);
        MdnsComputer differentName = new MdnsComputer("other", ipv4, ipv6, 47989);
        MdnsComputer missingIpv6 = new MdnsComputer("pc", ipv4, null, 47989);

        assertEquals("pc", first.getName());
        assertSame(ipv4, first.getLocalAddress());
        assertSame(ipv6, first.getIpv6Address());
        assertEquals(47989, first.getPort());
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, differentName);
        assertNotEquals(first, missingIpv6);
        assertEquals("[pc - /192.168.0.10 - /0:0:0:0:0:0:0:1]", first.toString());
    }

    @Test
    public void videoDecoderRendererKeepsAbstractMethodSignatures() throws Exception {
        assertTrue(java.lang.reflect.Modifier.isAbstract(VideoDecoderRenderer.class.getModifiers()));
        assertEquals(int.class, VideoDecoderRenderer.class.getMethod("setup", int.class, int.class, int.class, int.class).getReturnType());
        assertEquals(void.class, VideoDecoderRenderer.class.getMethod("start").getReturnType());
        assertEquals(void.class, VideoDecoderRenderer.class.getMethod("stop").getReturnType());
        assertEquals(int.class, VideoDecoderRenderer.class.getMethod(
                "submitDecodeUnit",
                byte[].class,
                int.class,
                int.class,
                int.class,
                int.class,
                char.class,
                long.class,
                long.class
        ).getReturnType());
        assertEquals(void.class, VideoDecoderRenderer.class.getMethod("cleanup").getReturnType());
        assertEquals(int.class, VideoDecoderRenderer.class.getMethod("getCapabilities").getReturnType());
        assertEquals(void.class, VideoDecoderRenderer.class.getMethod("setHdrMode", boolean.class, byte[].class).getReturnType());
    }
}
