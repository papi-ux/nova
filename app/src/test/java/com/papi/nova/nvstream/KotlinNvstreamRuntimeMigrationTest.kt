package com.papi.nova.nvstream

import android.content.Context
import com.papi.nova.nvstream.av.audio.AudioRenderer
import com.papi.nova.nvstream.av.video.VideoDecoderRenderer
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.LimelightCryptoProvider
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.shadows.ShadowMoonBridge
import java.io.File
import java.io.Reader
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
class KotlinNvstreamRuntimeMigrationTest {
    @Test
    fun nvstreamRuntimeClassesAreKotlinSources() {
        val paths = arrayOf(
            "src/main/java/com/papi/nova/nvstream/jni/MoonBridge",
            "src/main/java/com/papi/nova/nvstream/http/PairingManager",
            "src/main/java/com/papi/nova/nvstream/http/NvHTTP",
            "src/main/java/com/papi/nova/nvstream/NvConnection"
        )

        for (path in paths) {
            val javaFile = File("$path.java")
            val kotlinFile = File("$path.kt")
            assertFalse("$path should no longer be a Java source", javaFile.exists())
            assertTrue("$path should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun moonBridgeKeepsJavaCompatibleConstantsCallbacksAndNativeEntrypoints() {
        val byteType = Byte::class.javaPrimitiveType!!
        val shortType = Short::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!

        assertEquals(0x0001, MoonBridge.VIDEO_FORMAT_H264)
        assertEquals(0x0100, MoonBridge.VIDEO_FORMAT_H265)
        assertEquals(0x2200, MoonBridge.VIDEO_FORMAT_MASK_10BIT)
        assertEquals(-5501, MoonBridge.LI_ERR_UNSUPPORTED)
        assertEquals((-1).toByte(), MoonBridge.LI_TILT_UNKNOWN)
        assertEquals((-1).toShort(), MoonBridge.LI_ROT_UNKNOWN)
        assertTrue(Modifier.isStatic(MoonBridge::class.java.getField("VIDEO_FORMAT_H264").modifiers))

        MoonBridge.AudioConfiguration::class.java.getConstructor(intType, intType)
        assertEquals(intType, MoonBridge.AudioConfiguration::class.java.getField("channelCount").type)
        assertEquals(intType, MoonBridge.AudioConfiguration::class.java.getField("channelMask").type)
        assertEquals(0x00030002, MoonBridge.AudioConfiguration(2, 0x3).getSurroundAudioInfo())
        assertEquals(0x000302CA, MoonBridge.AudioConfiguration(2, 0x3).toInt())

        MoonBridge::class.java.getMethod("CAPABILITY_SLICES_PER_FRAME", byteType)
        MoonBridge::class.java.getMethod(
            "setupBridge",
            VideoDecoderRenderer::class.java,
            AudioRenderer::class.java,
            NvConnectionListener::class.java
        )
        MoonBridge::class.java.getMethod("cleanupBridge")
        MoonBridge::class.java.getMethod("bridgeDrSetup", intType, intType, intType, intType)
        MoonBridge::class.java.getMethod("bridgeArInit", intType, intType, intType)
        MoonBridge::class.java.getMethod("bridgeClStageFailed", intType, intType)
        MoonBridge::class.java.getMethod("bridgeClSetControllerLED", shortType, byteType, byteType, byteType)
        MoonBridge::class.java.getMethod(
            "startConnection",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            intType,
            intType,
            intType,
            intType,
            intType,
            intType,
            intType,
            intType,
            intType,
            intType,
            ByteArray::class.java,
            ByteArray::class.java,
            intType,
            intType,
            intType
        )
        MoonBridge::class.java.getMethod("stopConnection")
        MoonBridge::class.java.getMethod("sendKeyboardInput", shortType, byteType, byteType, byteType)
    }

    @Test
    fun moonBridgeAllowsNativeHdrMetadataToBeNull() {
        val moonBridge = readSource("src/main/java/com/papi/nova/nvstream/jni/MoonBridge.kt")
        val listener = readSource("src/main/java/com/papi/nova/nvstream/NvConnectionListener.kt")

        assertTrue(moonBridge.contains("fun bridgeClSetHdrMode(enabled: Boolean, hdrMetadata: ByteArray?)"))
        assertTrue(listener.contains("fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?)"))
    }

    @Test
    fun pairingAndHttpKeepJavaCompatibleApis() {
        val booleanType = Boolean::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!

        assertEquals(47989, NvHTTP.DEFAULT_HTTP_PORT)
        assertEquals(3000, NvHTTP.SHORT_CONNECTION_TIMEOUT)
        assertEquals(5000, NvHTTP.LONG_CONNECTION_TIMEOUT)
        assertEquals(7000, NvHTTP.READ_TIMEOUT)
        NvHTTP::class.java.getConstructor(
            ComputerDetails.AddressTuple::class.java,
            intType,
            String::class.java,
            X509Certificate::class.java,
            LimelightCryptoProvider::class.java
        )
        NvHTTP::class.java.getMethod("getHttpsUrl", booleanType)
        NvHTTP::class.java.getMethod("getComputerDetails", String::class.java)
        NvHTTP::class.java.getMethod("getComputerDetails", booleanType)
        NvHTTP::class.java.getMethod("getPairState", String::class.java)
        NvHTTP::class.java.getMethod("getAppListByReader", Reader::class.java)
        NvHTTP::class.java.getMethod(
            "launchApp",
            ConnectionContext::class.java,
            String::class.java,
            String::class.java,
            intType,
            booleanType,
            booleanType
        )
        NvHTTP::class.java.getMethod("quitApp", String::class.java)
        NvHTTP::class.java.getMethod("getClipboard")
        NvHTTP::class.java.getMethod("sendClipboard", String::class.java)
        NvHTTP::class.java.getDeclaredMethod("getXmlString", String::class.java, String::class.java, booleanType)
        NvHTTP::class.java.getDeclaredMethod("parseServerMaxLaunchRefreshRate", String::class.java)
        NvHTTP::class.java.getDeclaredMethod("parseCurrentGameOwned", String::class.java)

        PairingManager::class.java.getConstructor(NvHTTP::class.java, LimelightCryptoProvider::class.java)
        PairingManager::class.java.getMethod("generatePinString")
        PairingManager::class.java.getMethod("getPairedCert")
        PairingManager::class.java.getMethod("pair", String::class.java, String::class.java, String::class.java)
        PairingManager::class.java.getMethod("pair", String::class.java, String::class.java, String::class.java, booleanType)
        assertEquals(5, PairingManager.PairState.values().size)
        assertTrue(PairingManager.generatePinString().matches(Regex("\\d{4}")))
    }

    @Test
    fun nvConnectionKeepsJavaCompatibleApisAndLaunchRateBehavior() {
        val booleanType = Boolean::class.javaPrimitiveType!!
        val byteType = Byte::class.javaPrimitiveType!!
        val shortType = Short::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!
        val floatType = Float::class.javaPrimitiveType!!

        NvConnection::class.java.getConstructor(
            Context::class.java,
            ComputerDetails.AddressTuple::class.java,
            intType,
            String::class.java,
            StreamConfiguration::class.java,
            LimelightCryptoProvider::class.java,
            X509Certificate::class.java
        )
        NvConnection::class.java.getMethod("stop")
        NvConnection::class.java.getDeclaredMethod("negotiateLaunchRefreshRate", floatType, intType)
        NvConnection::class.java.getMethod("launchRefreshRateHz", floatType)
        NvConnection::class.java.getMethod("getSessionToken")
        NvConnection::class.java.getMethod("start", AudioRenderer::class.java, VideoDecoderRenderer::class.java, NvConnectionListener::class.java)
        NvConnection::class.java.getMethod("setWatchOnlyRequested", booleanType)
        NvConnection::class.java.getMethod("sendExecServerCmd", intType)
        NvConnection::class.java.getMethod("sendMouseMove", shortType, shortType)
        NvConnection::class.java.getMethod("sendMouseButtonDown", byteType)
        NvConnection::class.java.getMethod(
            "sendControllerInput",
            shortType,
            shortType,
            intType,
            byteType,
            byteType,
            shortType,
            shortType,
            shortType,
            shortType
        )
        NvConnection::class.java.getMethod("sendKeyboardInput", shortType, byteType, byteType, byteType)
        NvConnection::class.java.getMethod(
            "sendTouchEvent",
            byteType,
            intType,
            floatType,
            floatType,
            floatType,
            floatType,
            floatType,
            shortType
        )
        NvConnection::class.java.getMethod(
            "sendPenEvent",
            byteType,
            byteType,
            byteType,
            floatType,
            floatType,
            floatType,
            floatType,
            floatType,
            shortType,
            byteType
        )
        NvConnection::class.java.getMethod("sendUtf8Text", String::class.java)
        NvConnection::class.java.getMethod("findExternalAddressForMdns", String::class.java, intType)
        NvConnection::class.java.getMethod("shouldReplaceCurrentSession", booleanType, booleanType)
        NvConnection::class.java.getMethod("canStartFreshLaunch", booleanType, booleanType)

        assertEquals(60.0f, NvConnection.negotiateLaunchRefreshRate(120.0f, 60), 0.001f)
        assertEquals(75.0f, NvConnection.negotiateLaunchRefreshRate(75.0f, 120), 0.001f)
        assertEquals(60_000.0f, NvConnection.negotiateLaunchRefreshRate(119_880.0f, 60), 0.001f)
        assertEquals(119.88f, NvConnection.launchRefreshRateHz(119_880.0f), 0.001f)
        assertTrue(NvConnection.shouldReplaceCurrentSession(true, false))
        assertFalse(NvConnection.shouldReplaceCurrentSession(true, true))
        assertTrue(NvConnection.canStartFreshLaunch(false, false))
        assertFalse(NvConnection.canStartFreshLaunch(true, false))
        assertFalse(NvConnection.canStartFreshLaunch(false, true))
        assertFalse(NvConnection.canStartFreshLaunch(true, true))
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)
}
