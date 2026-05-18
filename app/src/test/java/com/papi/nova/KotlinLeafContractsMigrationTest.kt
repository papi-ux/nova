package com.papi.nova

import android.app.Application
import android.content.Context
import android.view.View
import com.papi.nova.binding.PlatformBinding
import com.papi.nova.binding.input.GameInputDevice
import com.papi.nova.binding.input.capture.InputCaptureProvider
import com.papi.nova.binding.input.capture.NullCaptureProvider
import com.papi.nova.binding.input.driver.AbstractController
import com.papi.nova.binding.input.driver.UsbDriverListener
import com.papi.nova.binding.input.evdev.EvdevListener
import com.papi.nova.binding.input.touch.TouchContext
import com.papi.nova.binding.video.CrashListener
import com.papi.nova.binding.video.PerfOverlayListener
import com.papi.nova.computers.ComputerManagerListener
import com.papi.nova.nvstream.NvConnectionListener
import com.papi.nova.nvstream.av.audio.AudioRenderer
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.LimelightCryptoProvider
import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.nvstream.input.KeyboardPacket
import com.papi.nova.nvstream.input.MouseButtonPacket
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.nvstream.mdns.MdnsComputer
import com.papi.nova.nvstream.mdns.MdnsDiscoveryListener
import com.papi.nova.ui.AdapterFragmentCallbacks
import com.papi.nova.ui.GameGestures
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinLeafContractsMigrationTest {
    @Test
    fun leafContractsAndBootstrapClassesAreKotlinSources() {
        val names = arrayOf(
            "NovaApplication",
            "LimeLog",
            "binding/PlatformBinding",
            "binding/input/capture/NullCaptureProvider",
            "binding/video/CrashListener",
            "binding/video/PerfOverlayListener",
            "nvstream/mdns/MdnsDiscoveryListener",
            "computers/ComputerManagerListener",
            "ui/AdapterFragmentCallbacks",
            "ui/GameGestures",
            "nvstream/input/KeyboardPacket",
            "nvstream/input/MouseButtonPacket",
            "nvstream/input/ControllerPacket",
            "binding/input/touch/TouchContext",
            "nvstream/http/LimelightCryptoProvider",
            "binding/input/driver/UsbDriverListener",
            "binding/input/evdev/EvdevListener",
            "nvstream/av/audio/AudioRenderer",
            "nvstream/NvConnectionListener"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun migratedBootstrapAndContractsKeepJavaCompatibleApis() {
        val booleanType = Boolean::class.javaPrimitiveType!!
        val byteType = Byte::class.javaPrimitiveType!!
        val shortType = Short::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!
        val longType = Long::class.javaPrimitiveType!!
        val floatType = Float::class.javaPrimitiveType!!

        assertTrue(Application::class.java.isAssignableFrom(NovaApplication::class.java))
        NovaApplication::class.java.getConstructor()

        LimeLog::class.java.getMethod("info", String::class.java)
        LimeLog::class.java.getMethod("warning", String::class.java)
        LimeLog::class.java.getMethod("severe", String::class.java)
        LimeLog::class.java.getMethod("setFileHandler", String::class.java)

        PlatformBinding::class.java.getMethod("getCryptoProvider", Context::class.java)

        assertTrue(InputCaptureProvider::class.java.isAssignableFrom(NullCaptureProvider::class.java))
        NullCaptureProvider::class.java.getConstructor()

        assertTrue(CrashListener::class.java.isInterface)
        CrashListener::class.java.getMethod("notifyCrash", Exception::class.java)
        assertTrue(PerfOverlayListener::class.java.isInterface)
        PerfOverlayListener::class.java.getMethod("onPerfUpdate", String::class.java)
        assertTrue(MdnsDiscoveryListener::class.java.isInterface)
        MdnsDiscoveryListener::class.java.getMethod("notifyComputerAdded", MdnsComputer::class.java)
        MdnsDiscoveryListener::class.java.getMethod("notifyDiscoveryFailure", Exception::class.java)
        assertTrue(ComputerManagerListener::class.java.isInterface)
        ComputerManagerListener::class.java.getMethod("notifyComputerUpdated", ComputerDetails::class.java)
        assertTrue(AdapterFragmentCallbacks::class.java.isInterface)
        AdapterFragmentCallbacks::class.java.getMethod("getAdapterFragmentLayoutId")
        AdapterFragmentCallbacks::class.java.getMethod("receiveAbsListView", View::class.java)
        assertTrue(GameGestures::class.java.isInterface)
        GameGestures::class.java.getMethod("toggleKeyboard")
        GameGestures::class.java.getMethod("showGameMenu", GameInputDevice::class.java)
        GameGestures::class.java.getMethod("cycleNovaHudFromController")

        assertTrue(TouchContext::class.java.isInterface)
        TouchContext::class.java.getMethod("getActionIndex")
        TouchContext::class.java.getMethod("setPointerCount", intType)
        TouchContext::class.java.getMethod("touchDownEvent", intType, intType, longType, booleanType)
        TouchContext::class.java.getMethod("touchMoveEvent", intType, intType, longType)
        TouchContext::class.java.getMethod("touchUpEvent", intType, intType, longType)
        TouchContext::class.java.getMethod("cancelTouch")
        TouchContext::class.java.getMethod("isCancelled")

        assertTrue(LimelightCryptoProvider::class.java.isInterface)
        LimelightCryptoProvider::class.java.getMethod("getClientCertificate")
        LimelightCryptoProvider::class.java.getMethod("getClientPrivateKey")
        LimelightCryptoProvider::class.java.getMethod("getPemEncodedClientCertificate")
        LimelightCryptoProvider::class.java.getMethod("encodeBase64String", ByteArray::class.java)
        assertEquals(
            X509Certificate::class.java,
            LimelightCryptoProvider::class.java.getMethod("getClientCertificate").returnType
        )
        assertEquals(
            PrivateKey::class.java,
            LimelightCryptoProvider::class.java.getMethod("getClientPrivateKey").returnType
        )

        assertTrue(UsbDriverListener::class.java.isInterface)
        UsbDriverListener::class.java.getMethod(
            "reportControllerState",
            intType,
            intType,
            floatType,
            floatType,
            floatType,
            floatType,
            floatType,
            floatType
        )
        UsbDriverListener::class.java.getMethod("reportControllerMotion", intType, byteType, floatType, floatType, floatType)
        UsbDriverListener::class.java.getMethod("deviceRemoved", AbstractController::class.java)
        UsbDriverListener::class.java.getMethod("deviceAdded", AbstractController::class.java)

        assertTrue(EvdevListener::class.java.isInterface)
        assertEquals(1, EvdevListener.BUTTON_LEFT)
        assertEquals(5, EvdevListener.BUTTON_X2)
        EvdevListener::class.java.getMethod("mouseMove", intType, intType)
        EvdevListener::class.java.getMethod("mouseButtonEvent", intType, booleanType)
        EvdevListener::class.java.getMethod("mouseVScroll", byteType)
        EvdevListener::class.java.getMethod("mouseHScroll", byteType)
        EvdevListener::class.java.getMethod("keyboardEvent", booleanType, shortType)

        assertTrue(AudioRenderer::class.java.isInterface)
        AudioRenderer::class.java.getMethod("setup", MoonBridge.AudioConfiguration::class.java, intType, intType)
        AudioRenderer::class.java.getMethod("start")
        AudioRenderer::class.java.getMethod("stop")
        AudioRenderer::class.java.getMethod("playDecodedAudio", ShortArray::class.java)
        AudioRenderer::class.java.getMethod("cleanup")

        assertTrue(NvConnectionListener::class.java.isInterface)
        NvConnectionListener::class.java.getMethod("stageStarting", String::class.java)
        NvConnectionListener::class.java.getMethod("stageComplete", String::class.java)
        NvConnectionListener::class.java.getMethod("stageFailed", String::class.java, intType, intType)
        NvConnectionListener::class.java.getMethod("connectionStarted")
        NvConnectionListener::class.java.getMethod("connectionTerminated", intType)
        NvConnectionListener::class.java.getMethod("connectionStatusUpdate", intType)
        NvConnectionListener::class.java.getMethod("displayMessage", String::class.java)
        NvConnectionListener::class.java.getMethod("displayTransientMessage", String::class.java)
        NvConnectionListener::class.java.getMethod("rumble", shortType, shortType, shortType)
        NvConnectionListener::class.java.getMethod("rumbleTriggers", shortType, shortType, shortType)
        NvConnectionListener::class.java.getMethod("setHdrMode", booleanType, ByteArray::class.java)
        NvConnectionListener::class.java.getMethod("setMotionEventState", shortType, byteType, shortType)
        NvConnectionListener::class.java.getMethod("setControllerLED", shortType, byteType, byteType, byteType)
    }

    @Test
    fun migratedPacketConstantsKeepJavaFieldValues() {
        assertEquals(0x03.toByte(), KeyboardPacket.KEY_DOWN)
        assertEquals(0x04.toByte(), KeyboardPacket.KEY_UP)
        assertEquals(0x01.toByte(), KeyboardPacket.MODIFIER_SHIFT)
        assertEquals(0x08.toByte(), KeyboardPacket.MODIFIER_META)

        assertEquals(0x07.toByte(), MouseButtonPacket.PRESS_EVENT)
        assertEquals(0x08.toByte(), MouseButtonPacket.RELEASE_EVENT)
        assertEquals(0x01.toByte(), MouseButtonPacket.BUTTON_LEFT)
        assertEquals(0x05.toByte(), MouseButtonPacket.BUTTON_X2)

        assertEquals(0x1000, ControllerPacket.A_FLAG)
        assertEquals(0x2000, ControllerPacket.B_FLAG)
        assertEquals(0x4000, ControllerPacket.X_FLAG)
        assertEquals(0x8000, ControllerPacket.Y_FLAG)
        assertEquals(0x100000, ControllerPacket.TOUCHPAD_FLAG)
        assertEquals(0x200000, ControllerPacket.MISC_FLAG)
    }
}
