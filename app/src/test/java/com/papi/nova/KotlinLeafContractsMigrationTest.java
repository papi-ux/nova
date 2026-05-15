package com.papi.nova;

import android.app.Application;
import android.content.Context;
import android.view.View;

import com.papi.nova.binding.PlatformBinding;
import com.papi.nova.binding.input.GameInputDevice;
import com.papi.nova.binding.input.capture.InputCaptureProvider;
import com.papi.nova.binding.input.capture.NullCaptureProvider;
import com.papi.nova.binding.input.driver.AbstractController;
import com.papi.nova.binding.input.driver.UsbDriverListener;
import com.papi.nova.binding.input.evdev.EvdevListener;
import com.papi.nova.binding.input.touch.TouchContext;
import com.papi.nova.binding.video.CrashListener;
import com.papi.nova.binding.video.PerfOverlayListener;
import com.papi.nova.computers.ComputerManagerListener;
import com.papi.nova.nvstream.NvConnectionListener;
import com.papi.nova.nvstream.av.audio.AudioRenderer;
import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.nvstream.http.LimelightCryptoProvider;
import com.papi.nova.nvstream.input.ControllerPacket;
import com.papi.nova.nvstream.input.KeyboardPacket;
import com.papi.nova.nvstream.input.MouseButtonPacket;
import com.papi.nova.nvstream.jni.MoonBridge;
import com.papi.nova.nvstream.mdns.MdnsComputer;
import com.papi.nova.nvstream.mdns.MdnsDiscoveryListener;
import com.papi.nova.ui.AdapterFragmentCallbacks;
import com.papi.nova.ui.GameGestures;

import org.junit.Test;

import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KotlinLeafContractsMigrationTest {
    @Test
    public void leafContractsAndBootstrapClassesAreKotlinSources() {
        String[] names = {
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
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void migratedBootstrapAndContractsKeepJavaCompatibleApis() throws NoSuchMethodException, NoSuchFieldException {
        assertTrue(Application.class.isAssignableFrom(NovaApplication.class));
        NovaApplication.class.getConstructor();

        LimeLog.class.getMethod("info", String.class);
        LimeLog.class.getMethod("warning", String.class);
        LimeLog.class.getMethod("severe", String.class);
        LimeLog.class.getMethod("setFileHandler", String.class);

        PlatformBinding.class.getMethod("getCryptoProvider", Context.class);

        assertTrue(InputCaptureProvider.class.isAssignableFrom(NullCaptureProvider.class));
        NullCaptureProvider.class.getConstructor();

        assertTrue(CrashListener.class.isInterface());
        CrashListener.class.getMethod("notifyCrash", Exception.class);
        assertTrue(PerfOverlayListener.class.isInterface());
        PerfOverlayListener.class.getMethod("onPerfUpdate", String.class);
        assertTrue(MdnsDiscoveryListener.class.isInterface());
        MdnsDiscoveryListener.class.getMethod("notifyComputerAdded", MdnsComputer.class);
        MdnsDiscoveryListener.class.getMethod("notifyDiscoveryFailure", Exception.class);
        assertTrue(ComputerManagerListener.class.isInterface());
        ComputerManagerListener.class.getMethod("notifyComputerUpdated", ComputerDetails.class);
        assertTrue(AdapterFragmentCallbacks.class.isInterface());
        AdapterFragmentCallbacks.class.getMethod("getAdapterFragmentLayoutId");
        AdapterFragmentCallbacks.class.getMethod("receiveAbsListView", View.class);
        assertTrue(GameGestures.class.isInterface());
        GameGestures.class.getMethod("toggleKeyboard");
        GameGestures.class.getMethod("showGameMenu", GameInputDevice.class);

        assertTrue(TouchContext.class.isInterface());
        TouchContext.class.getMethod("getActionIndex");
        TouchContext.class.getMethod("setPointerCount", int.class);
        TouchContext.class.getMethod("touchDownEvent", int.class, int.class, long.class, boolean.class);
        TouchContext.class.getMethod("touchMoveEvent", int.class, int.class, long.class);
        TouchContext.class.getMethod("touchUpEvent", int.class, int.class, long.class);
        TouchContext.class.getMethod("cancelTouch");
        TouchContext.class.getMethod("isCancelled");

        assertTrue(LimelightCryptoProvider.class.isInterface());
        LimelightCryptoProvider.class.getMethod("getClientCertificate");
        LimelightCryptoProvider.class.getMethod("getClientPrivateKey");
        LimelightCryptoProvider.class.getMethod("getPemEncodedClientCertificate");
        LimelightCryptoProvider.class.getMethod("encodeBase64String", byte[].class);
        assertEquals(X509Certificate.class, LimelightCryptoProvider.class.getMethod("getClientCertificate").getReturnType());
        assertEquals(PrivateKey.class, LimelightCryptoProvider.class.getMethod("getClientPrivateKey").getReturnType());

        assertTrue(UsbDriverListener.class.isInterface());
        UsbDriverListener.class.getMethod("reportControllerState", int.class, int.class, float.class, float.class, float.class, float.class, float.class, float.class);
        UsbDriverListener.class.getMethod("reportControllerMotion", int.class, byte.class, float.class, float.class, float.class);
        UsbDriverListener.class.getMethod("deviceRemoved", AbstractController.class);
        UsbDriverListener.class.getMethod("deviceAdded", AbstractController.class);

        assertTrue(EvdevListener.class.isInterface());
        assertEquals(1, EvdevListener.BUTTON_LEFT);
        assertEquals(5, EvdevListener.BUTTON_X2);
        EvdevListener.class.getMethod("mouseMove", int.class, int.class);
        EvdevListener.class.getMethod("mouseButtonEvent", int.class, boolean.class);
        EvdevListener.class.getMethod("mouseVScroll", byte.class);
        EvdevListener.class.getMethod("mouseHScroll", byte.class);
        EvdevListener.class.getMethod("keyboardEvent", boolean.class, short.class);

        assertTrue(AudioRenderer.class.isInterface());
        AudioRenderer.class.getMethod("setup", MoonBridge.AudioConfiguration.class, int.class, int.class);
        AudioRenderer.class.getMethod("start");
        AudioRenderer.class.getMethod("stop");
        AudioRenderer.class.getMethod("playDecodedAudio", short[].class);
        AudioRenderer.class.getMethod("cleanup");

        assertTrue(NvConnectionListener.class.isInterface());
        NvConnectionListener.class.getMethod("stageStarting", String.class);
        NvConnectionListener.class.getMethod("stageComplete", String.class);
        NvConnectionListener.class.getMethod("stageFailed", String.class, int.class, int.class);
        NvConnectionListener.class.getMethod("connectionStarted");
        NvConnectionListener.class.getMethod("connectionTerminated", int.class);
        NvConnectionListener.class.getMethod("connectionStatusUpdate", int.class);
        NvConnectionListener.class.getMethod("displayMessage", String.class);
        NvConnectionListener.class.getMethod("displayTransientMessage", String.class);
        NvConnectionListener.class.getMethod("rumble", short.class, short.class, short.class);
        NvConnectionListener.class.getMethod("rumbleTriggers", short.class, short.class, short.class);
        NvConnectionListener.class.getMethod("setHdrMode", boolean.class, byte[].class);
        NvConnectionListener.class.getMethod("setMotionEventState", short.class, byte.class, short.class);
        NvConnectionListener.class.getMethod("setControllerLED", short.class, byte.class, byte.class, byte.class);
    }

    @Test
    public void migratedPacketConstantsKeepJavaFieldValues() {
        assertEquals((byte) 0x03, KeyboardPacket.KEY_DOWN);
        assertEquals((byte) 0x04, KeyboardPacket.KEY_UP);
        assertEquals((byte) 0x01, KeyboardPacket.MODIFIER_SHIFT);
        assertEquals((byte) 0x08, KeyboardPacket.MODIFIER_META);

        assertEquals((byte) 0x07, MouseButtonPacket.PRESS_EVENT);
        assertEquals((byte) 0x08, MouseButtonPacket.RELEASE_EVENT);
        assertEquals((byte) 0x01, MouseButtonPacket.BUTTON_LEFT);
        assertEquals((byte) 0x05, MouseButtonPacket.BUTTON_X2);

        assertEquals(0x1000, ControllerPacket.A_FLAG);
        assertEquals(0x2000, ControllerPacket.B_FLAG);
        assertEquals(0x4000, ControllerPacket.X_FLAG);
        assertEquals(0x8000, ControllerPacket.Y_FLAG);
        assertEquals(0x100000, ControllerPacket.TOUCHPAD_FLAG);
        assertEquals(0x200000, ControllerPacket.MISC_FLAG);
    }
}
