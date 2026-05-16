package com.papi.nova.nvstream

import com.papi.nova.nvstream.av.ByteBufferDescriptor
import com.papi.nova.nvstream.av.video.VideoDecoderRenderer
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.HostHttpResponseException
import com.papi.nova.nvstream.mdns.MdnsComputer
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinNvstreamLeafMigrationTest {
    @Test
    fun nvstreamLeafClassesAreKotlinSources() {
        val paths = arrayOf(
            "src/main/java/com/papi/nova/nvstream/ConnectionContext",
            "src/main/java/com/papi/nova/nvstream/av/ByteBufferDescriptor",
            "src/main/java/com/papi/nova/nvstream/av/video/VideoDecoderRenderer",
            "src/main/java/com/papi/nova/nvstream/http/HostHttpResponseException",
            "src/main/java/com/papi/nova/nvstream/mdns/MdnsComputer"
        )

        for (path in paths) {
            val javaFile = File("$path.java")
            val kotlinFile = File("$path.kt")
            assertFalse("$path should no longer be a Java source", javaFile.exists())
            assertTrue("$path should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun hostHttpResponseExceptionKeepsJavaApiAndMessage() {
        val exception = HostHttpResponseException(599, "launch failed")

        assertTrue(IOException::class.java.isAssignableFrom(HostHttpResponseException::class.java))
        assertEquals(599, exception.getErrorCode())
        assertEquals("launch failed", exception.getErrorMessage())
        assertEquals("Host PC returned error: launch failed (Error code: 599)", exception.message)
    }

    @Test
    fun byteBufferDescriptorKeepsMutableFieldsAndCopyBehavior() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val descriptor = ByteBufferDescriptor(payload, 1, 3)
        val next = ByteBufferDescriptor(byteArrayOf(9), 0, 1)
        descriptor.nextDescriptor = next

        val copy = ByteBufferDescriptor(descriptor)

        assertSame(payload, copy.data)
        assertEquals(1, copy.offset)
        assertEquals(3, copy.length)
        assertEquals(next, descriptor.nextDescriptor)
        assertEquals(null, copy.nextDescriptor)

        val replacement = byteArrayOf(7, 8)
        descriptor.reinitialize(replacement, 0, 2)

        assertSame(replacement, descriptor.data)
        assertEquals(0, descriptor.offset)
        assertEquals(2, descriptor.length)
        assertEquals(null, descriptor.nextDescriptor)
    }

    @Test
    fun connectionContextKeepsPublicMutableFields() {
        val context = ConnectionContext()
        val address = ComputerDetails.AddressTuple("10.0.0.2", 47984)
        val key = SecretKeySpec(byteArrayOf(1, 2, 3, 4), "AES")

        context.serverAddress = address
        context.httpsPort = 47989
        context.isNvidiaServerSoftware = true
        context.serverCert = null
        context.streamConfig = null
        context.connListener = null
        context.riKey = key
        context.riKeyId = 7
        context.serverAppVersion = "1.2.3.4"
        context.serverGfeVersion = "3.27"
        context.serverCodecModeSupport = 15
        context.serverMaxLaunchRefreshRate = 144
        context.rtspSessionUrl = "rtsp://session"
        context.sessionToken = "token"
        context.currentGameOwnedByClient = false
        context.currentGameOwnerName = "host-user"
        context.watchOnlyRequested = true
        context.negotiatedWidth = 1920
        context.negotiatedHeight = 1080
        context.negotiatedHdr = true
        context.negotiatedLaunchRefreshRate = 119.88f
        context.negotiatedRemoteStreaming = StreamConfiguration.STREAM_CFG_REMOTE
        context.negotiatedPacketSize = 1200
        context.videoCapabilities = 255

        assertSame(address, context.serverAddress)
        assertEquals(47989, context.httpsPort)
        assertTrue(context.isNvidiaServerSoftware)
        assertEquals(null, context.streamConfig)
        assertSame(key, context.riKey)
        assertEquals(7, context.riKeyId)
        assertEquals("1.2.3.4", context.serverAppVersion)
        assertEquals("3.27", context.serverGfeVersion)
        assertEquals(15, context.serverCodecModeSupport)
        assertEquals(144, context.serverMaxLaunchRefreshRate)
        assertEquals("rtsp://session", context.rtspSessionUrl)
        assertEquals("token", context.sessionToken)
        assertEquals(false, context.currentGameOwnedByClient)
        assertEquals("host-user", context.currentGameOwnerName)
        assertTrue(context.watchOnlyRequested)
        assertEquals(1920, context.negotiatedWidth)
        assertEquals(1080, context.negotiatedHeight)
        assertTrue(context.negotiatedHdr)
        assertEquals(119.88f, context.negotiatedLaunchRefreshRate, 0.001f)
        assertEquals(StreamConfiguration.STREAM_CFG_REMOTE, context.negotiatedRemoteStreaming)
        assertEquals(1200, context.negotiatedPacketSize)
        assertEquals(255, context.videoCapabilities)
    }

    @Test
    fun mdnsComputerKeepsEqualityHashAndStringSemantics() {
        val ipv4 = InetAddress.getByName("192.168.0.10")
        val ipv6 = InetAddress.getByName("::1") as Inet6Address
        val first = MdnsComputer("pc", ipv4, ipv6, 47989)
        val same = MdnsComputer("pc", ipv4, ipv6, 47989)
        val differentName = MdnsComputer("other", ipv4, ipv6, 47989)
        val missingIpv6 = MdnsComputer("pc", ipv4, null, 47989)

        assertEquals("pc", first.getName())
        assertSame(ipv4, first.getLocalAddress())
        assertSame(ipv6, first.getIpv6Address())
        assertEquals(47989, first.getPort())
        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, differentName)
        assertNotEquals(first, missingIpv6)
        assertEquals("[pc - /192.168.0.10 - /0:0:0:0:0:0:0:1]", first.toString())
    }

    @Test
    fun videoDecoderRendererKeepsAbstractMethodSignatures() {
        val intType = Int::class.javaPrimitiveType!!
        val charType = Char::class.javaPrimitiveType!!
        val longType = Long::class.javaPrimitiveType!!
        val booleanType = Boolean::class.javaPrimitiveType!!

        assertTrue(java.lang.reflect.Modifier.isAbstract(VideoDecoderRenderer::class.java.modifiers))
        assertEquals(
            intType,
            VideoDecoderRenderer::class.java.getMethod("setup", intType, intType, intType, intType).returnType
        )
        assertEquals(Void.TYPE, VideoDecoderRenderer::class.java.getMethod("start").returnType)
        assertEquals(Void.TYPE, VideoDecoderRenderer::class.java.getMethod("stop").returnType)
        assertEquals(
            intType,
            VideoDecoderRenderer::class.java.getMethod(
                "submitDecodeUnit",
                ByteArray::class.java,
                intType,
                intType,
                intType,
                intType,
                charType,
                longType,
                longType
            ).returnType
        )
        assertEquals(Void.TYPE, VideoDecoderRenderer::class.java.getMethod("cleanup").returnType)
        assertEquals(intType, VideoDecoderRenderer::class.java.getMethod("getCapabilities").returnType)
        assertEquals(
            Void.TYPE,
            VideoDecoderRenderer::class.java.getMethod("setHdrMode", booleanType, ByteArray::class.java).returnType
        )
    }
}
