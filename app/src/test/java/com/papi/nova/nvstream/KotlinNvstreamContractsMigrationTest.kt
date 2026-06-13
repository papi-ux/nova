package com.papi.nova.nvstream

import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.shadows.ShadowMoonBridge
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
class KotlinNvstreamContractsMigrationTest {
    @Test
    fun nvstreamContractClassesAreKotlinSources() {
        val paths = arrayOf(
            "src/main/java/com/papi/nova/nvstream/http/NvApp",
            "src/main/java/com/papi/nova/nvstream/http/ComputerDetails",
            "src/main/java/com/papi/nova/nvstream/StreamConfiguration"
        )

        for (path in paths) {
            val javaFile = File("$path.java")
            val kotlinFile = File("$path.kt")
            assertFalse("$path should no longer be a Java source", javaFile.exists())
            assertTrue("$path should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun contractClassesKeepJavaCompatibleApis() {
        val booleanType = Boolean::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!
        val floatType = Float::class.javaPrimitiveType!!

        NvApp::class.java.getConstructor()
        NvApp::class.java.getConstructor(String::class.java)
        NvApp::class.java.getConstructor(String::class.java, String::class.java, intType, booleanType)
        assertEquals(String::class.java, NvApp::class.java.getField("REMOTE_INPUT_UUID").type)
        assertTrue(Modifier.isStatic(NvApp::class.java.getField("REMOTE_INPUT_UUID").modifiers))
        NvApp::class.java.getMethod("setAppName", String::class.java)
        NvApp::class.java.getMethod("setAppUUID", String::class.java)
        NvApp::class.java.getMethod("setAppId", String::class.java)
        NvApp::class.java.getMethod("setAppId", intType)
        NvApp::class.java.getMethod("setAppIndex", String::class.java)
        NvApp::class.java.getMethod("setAppIndex", intType)
        NvApp::class.java.getMethod("setHdrSupported", booleanType)
        assertEquals(booleanType, NvApp::class.java.getMethod("applyPolarisMetadata", PolarisGame::class.java).returnType)
        assertEquals(String::class.java, NvApp::class.java.getMethod("getMetadataKey").returnType)

        ComputerDetails::class.java.getConstructor()
        ComputerDetails::class.java.getConstructor(ComputerDetails::class.java)
        ComputerDetails::class.java.getMethod("guessExternalPort")
        ComputerDetails::class.java.getMethod("update", ComputerDetails::class.java)
        ComputerDetails.AddressTuple::class.java.getConstructor(String::class.java, intType)
        assertEquals(String::class.java, ComputerDetails.AddressTuple::class.java.getField("address").type)
        assertEquals(intType, ComputerDetails.AddressTuple::class.java.getField("port").type)
        assertPublicField("uuid", String::class.java)
        assertPublicField("localAddress", ComputerDetails.AddressTuple::class.java)
        assertPublicField("serverCert", X509Certificate::class.java)
        assertPublicField("state", ComputerDetails.State::class.java)
        assertPublicField("permission", intType)
        assertPublicField("serverCommands", List::class.java)

        assertEquals(0, StreamConfiguration.INVALID_APP_ID)
        assertEquals(0, StreamConfiguration.STREAM_CFG_LOCAL)
        assertEquals(1, StreamConfiguration.STREAM_CFG_REMOTE)
        assertEquals(2, StreamConfiguration.STREAM_CFG_AUTO)
        StreamConfiguration.Builder::class.java.getConstructor()
        assertBuilderMethod("setApp", NvApp::class.java)
        assertBuilderMethod("setRemoteConfiguration", intType)
        assertBuilderMethod("setResolution", intType, intType)
        assertBuilderMethod("setRefreshRate", floatType)
        assertBuilderMethod("setLaunchRefreshRate", floatType)
        assertBuilderMethod("setVirtualDisplay", booleanType)
        assertBuilderMethod("setDisplayModeExplicit", booleanType)
        assertBuilderMethod("setResolutionScaleFactor", intType)
        assertBuilderMethod("setBitrate", intType)
        assertBuilderMethod("setEnableSops", booleanType)
        assertBuilderMethod("enableAdaptiveResolution", booleanType)
        assertBuilderMethod("enableLocalAudioPlayback", booleanType)
        assertBuilderMethod("setMaxPacketSize", intType)
        assertBuilderMethod("setAttachedGamepadMask", intType)
        assertBuilderMethod("setAttachedGamepadMaskByCount", intType)
        assertBuilderMethod("setPersistGamepadsAfterDisconnect", booleanType)
        assertBuilderMethod("setClientRefreshRateX100", intType)
        assertBuilderMethod("setAudioConfiguration", MoonBridge.AudioConfiguration::class.java)
        assertBuilderMethod("setSupportedVideoFormats", intType)
        assertBuilderMethod("setColorRange", intType)
        assertBuilderMethod("setColorSpace", intType)
        assertBuilderMethod("setEnableUltraLowLatency", booleanType)
        assertBuilderMethod("setForceFreshLaunch", booleanType)
        assertEquals(StreamConfiguration::class.java, StreamConfiguration.Builder::class.java.getMethod("build").returnType)
    }

    @Test
    fun addressTupleKeepsValidationNormalizationAndEquality() {
        val ipv4 = ComputerDetails.AddressTuple("10.0.0.2", 47984)
        val same = ComputerDetails.AddressTuple("10.0.0.2", 47984)
        val different = ComputerDetails.AddressTuple("10.0.0.3", 47984)
        val ipv6 = ComputerDetails.AddressTuple("[2001:db8::1]", 47989)

        assertEquals("10.0.0.2", ipv4.address)
        assertEquals(47984, ipv4.port)
        assertEquals("10.0.0.2:47984", ipv4.toString())
        assertEquals("2001:db8::1", ipv6.address)
        assertEquals("[2001:db8::1]:47989", ipv6.toString())
        assertEquals(ipv4, same)
        assertEquals(ipv4.hashCode(), same.hashCode())
        assertNotEquals(ipv4, different)

        assertAddressTupleThrows(null, 47989, "Address cannot be null")
        assertAddressTupleThrows("10.0.0.2", 0, "Invalid port")
    }

    @Test
    fun computerDetailsKeepsPortPrecedenceAndUpdateRules() {
        val details = ComputerDetails()
        assertEquals(ComputerDetails.State.UNKNOWN, details.state)
        assertEquals(NvHTTP.DEFAULT_HTTP_PORT, details.guessExternalPort())

        details.localAddress = ComputerDetails.AddressTuple("local", 1)
        assertEquals(1, details.guessExternalPort())
        details.ipv6Address = ComputerDetails.AddressTuple("::1", 2)
        assertEquals(2, details.guessExternalPort())
        details.activeAddress = ComputerDetails.AddressTuple("active", 3)
        assertEquals(3, details.guessExternalPort())
        details.remoteAddress = ComputerDetails.AddressTuple("remote", 4)
        assertEquals(4, details.guessExternalPort())
        details.externalPort = 5
        assertEquals(5, details.guessExternalPort())

        val base = ComputerDetails()
        base.remoteAddress = ComputerDetails.AddressTuple("remote", 1111)
        base.macAddress = "AA:BB:CC:DD:EE:FF"
        base.libraryState = ComputerDetails.LibraryState.AVAILABLE

        val update = ComputerDetails()
        update.state = ComputerDetails.State.ONLINE
        update.pairState = PairingManager.PairState.PAIRED
        update.localAddress = ComputerDetails.AddressTuple("127.0.0.1", 2222)
        update.externalPort = 3333
        update.macAddress = "00:00:00:00:00:00"
        update.libraryState = ComputerDetails.LibraryState.UNKNOWN

        base.update(update)

        assertNull(base.localAddress)
        assertEquals(3333, base.remoteAddress!!.port)
        assertEquals("AA:BB:CC:DD:EE:FF", base.macAddress)
        assertEquals(ComputerDetails.LibraryState.AVAILABLE, base.libraryState)

        val copy = ComputerDetails(base)
        assertEquals(base.state, copy.state)
        assertSame(base.remoteAddress, copy.remoteAddress)
        assertEquals(base.libraryState, copy.libraryState)
    }

    @Test
    fun nvAppKeepsParsingMetadataLabelsAndStringOutput() {
        val app = NvApp("Game")
        assertEquals("Game", app.appName)
        assertEquals("", app.appUUID)
        assertFalse(app.isInitialized())

        app.setAppId("42")
        app.setAppIndex("7")
        app.setAppId("bad-id")
        app.setAppIndex("bad-index")
        app.isHdrSupported = true

        assertEquals(42, app.appId)
        assertEquals(7, app.appIndex)
        assertTrue(app.isInitialized())
        assertTrue(app.isHdrSupported)
        assertFalse(app.applyPolarisMetadata(null))

        val game = PolarisGame(
            "game-id",
            99,
            "Game",
            "Other",
            "Steam",
            "Big Picture",
            "Linux",
            "Proton",
            "",
            "",
            " 12345 ",
            "Desktop",
            true,
            "",
            emptyList(),
            0L,
            false,
            false,
            null
        )

        assertTrue(app.applyPolarisMetadata(game))
        assertFalse(app.applyPolarisMetadata(game))
        assertEquals("other", app.source)
        assertEquals("steam", app.launcherSource)
        assertEquals("linux", app.platform)
        assertEquals("proton", app.runtime)
        assertEquals("12345", app.steamAppid)
        assertEquals("Steam", app.sourceLabel)
        assertEquals("Linux", app.platformLabel)
        assertEquals("Proton", app.runtimeLabel)
        assertEquals("Steam · Linux · Proton", app.metadataLabel)
        assertEquals("other|steam|big picture|linux|proton|12345|desktop", app.metadataKey)

        val appText = app.toString()
        assertTrue(appText.contains("Name: Game"))
        assertTrue(appText.contains("ID: 42"))
        assertTrue(appText.contains("HDR Supported: Yes"))
        assertTrue(appText.contains("Source: Steam · Linux · Proton"))

        val initialized = NvApp("Other", "uuid", 100, false)
        assertTrue(initialized.isInitialized())
        assertEquals("uuid", initialized.appUUID)
        assertEquals(100, initialized.appId)
    }

    @Test
    fun streamConfigurationKeepsDefaultsAndBuilderSemantics() {
        val defaults = StreamConfiguration.Builder().build()

        assertEquals("Steam", defaults.getApp()!!.appName)
        assertEquals(1280, defaults.getWidth())
        assertEquals(720, defaults.getHeight())
        assertEquals(60, defaults.getRefreshRate())
        assertEquals(60, defaults.getLaunchRefreshRate())
        assertFalse(defaults.getVirtualDisplay())
        assertFalse(defaults.getDisplayModeExplicit())
        assertEquals(100, defaults.getResolutionScaleFactor())
        assertEquals(10000, defaults.getBitrate())
        assertEquals(1024, defaults.getMaxPacketSize())
        assertEquals(StreamConfiguration.STREAM_CFG_AUTO, defaults.getRemote())
        assertTrue(defaults.getSops())
        assertFalse(defaults.getAdaptiveResolutionEnabled())
        assertFalse(defaults.getPlayLocalAudio())
        assertSame(MoonBridge.AUDIO_CONFIGURATION_STEREO, defaults.getAudioConfiguration())
        assertEquals(MoonBridge.VIDEO_FORMAT_H264, defaults.getSupportedVideoFormats())
        assertEquals(0, defaults.getAttachedGamepadMask())
        assertFalse(defaults.getPersistGamepadsAfterDisconnect())
        assertEquals(0, defaults.getClientRefreshRateX100())
        assertEquals(0, defaults.getColorRange())
        assertEquals(0, defaults.getColorSpace())
        assertFalse(defaults.getEnableUltraLowLatency())
        assertFalse(defaults.getForceFreshLaunch())

        val customApp = NvApp("Custom")
        val builder = StreamConfiguration.Builder()
        assertSame(builder, builder.setApp(customApp))

        val config = builder
            .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_REMOTE)
            .setResolution(1920, 1080)
            .setRefreshRate(59.94f)
            .setLaunchRefreshRate(119.88f)
            .setVirtualDisplay(true)
            .setDisplayModeExplicit(true)
            .setResolutionScaleFactor(75)
            .setBitrate(20000)
            .setEnableSops(false)
            .enableAdaptiveResolution(true)
            .enableLocalAudioPlayback(true)
            .setMaxPacketSize(1200)
            .setAttachedGamepadMask(15)
            .setAttachedGamepadMaskByCount(3)
            .setPersistGamepadsAfterDisconnect(true)
            .setClientRefreshRateX100(5994)
            .setAudioConfiguration(MoonBridge.AUDIO_CONFIGURATION_51_SURROUND)
            .setSupportedVideoFormats(MoonBridge.VIDEO_FORMAT_H265)
            .setColorRange(MoonBridge.COLOR_RANGE_FULL)
            .setColorSpace(MoonBridge.COLORSPACE_REC_2020)
            .setEnableUltraLowLatency(true)
            .setForceFreshLaunch(true)
            .build()

        assertSame(customApp, config.getApp())
        assertEquals(StreamConfiguration.STREAM_CFG_REMOTE, config.getRemote())
        assertEquals(1920, config.getWidth())
        assertEquals(1080, config.getHeight())
        assertEquals(59940, config.getRefreshRate())
        assertEquals(119880, config.getLaunchRefreshRate())
        assertTrue(config.getVirtualDisplay())
        assertTrue(config.getDisplayModeExplicit())
        assertEquals(75, config.getResolutionScaleFactor())
        assertEquals(20000, config.getBitrate())
        assertFalse(config.getSops())
        assertTrue(config.getAdaptiveResolutionEnabled())
        assertTrue(config.getPlayLocalAudio())
        assertEquals(1200, config.getMaxPacketSize())
        assertEquals(0b111, config.getAttachedGamepadMask())
        assertTrue(config.getPersistGamepadsAfterDisconnect())
        assertEquals(5994, config.getClientRefreshRateX100())
        assertSame(MoonBridge.AUDIO_CONFIGURATION_51_SURROUND, config.getAudioConfiguration())
        assertEquals(MoonBridge.VIDEO_FORMAT_H265, config.getSupportedVideoFormats())
        assertEquals(MoonBridge.COLOR_RANGE_FULL, config.getColorRange())
        assertEquals(MoonBridge.COLORSPACE_REC_2020, config.getColorSpace())
        assertTrue(config.getEnableUltraLowLatency())
        assertTrue(config.getForceFreshLaunch())
    }

    private fun assertPublicField(name: String, type: Class<*>) {
        val field: Field = ComputerDetails::class.java.getField(name)
        assertEquals(type, field.type)
        assertTrue(Modifier.isPublic(field.modifiers))
    }

    private fun assertBuilderMethod(name: String, vararg params: Class<*>) {
        assertEquals(
            StreamConfiguration.Builder::class.java,
            StreamConfiguration.Builder::class.java.getMethod(name, *params).returnType
        )
    }

    private fun assertAddressTupleThrows(address: String?, port: Int, message: String) {
        try {
            ComputerDetails.AddressTuple(address, port)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals(message, e.message)
        }
    }
}
