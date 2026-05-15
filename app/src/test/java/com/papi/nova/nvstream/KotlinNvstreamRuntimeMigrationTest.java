package com.papi.nova.nvstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.papi.nova.nvstream.av.audio.AudioRenderer;
import com.papi.nova.nvstream.av.video.VideoDecoderRenderer;
import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.nvstream.http.LimelightCryptoProvider;
import com.papi.nova.nvstream.http.NvHTTP;
import com.papi.nova.nvstream.http.PairingManager;
import com.papi.nova.nvstream.jni.MoonBridge;
import com.papi.nova.shadows.ShadowMoonBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.Reader;
import java.lang.reflect.Modifier;
import java.security.cert.X509Certificate;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {ShadowMoonBridge.class})
public class KotlinNvstreamRuntimeMigrationTest {
    @Test
    public void nvstreamRuntimeClassesAreKotlinSources() {
        String[] paths = {
                "src/main/java/com/papi/nova/nvstream/jni/MoonBridge",
                "src/main/java/com/papi/nova/nvstream/http/PairingManager",
                "src/main/java/com/papi/nova/nvstream/http/NvHTTP",
                "src/main/java/com/papi/nova/nvstream/NvConnection"
        };

        for (String path : paths) {
            File javaFile = new File(path + ".java");
            File kotlinFile = new File(path + ".kt");
            assertFalse(path + " should no longer be a Java source", javaFile.exists());
            assertTrue(path + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void moonBridgeKeepsJavaCompatibleConstantsCallbacksAndNativeEntrypoints() throws Exception {
        assertEquals(0x0001, MoonBridge.VIDEO_FORMAT_H264);
        assertEquals(0x0100, MoonBridge.VIDEO_FORMAT_H265);
        assertEquals(0x2200, MoonBridge.VIDEO_FORMAT_MASK_10BIT);
        assertEquals(-5501, MoonBridge.LI_ERR_UNSUPPORTED);
        assertEquals((byte) 0xFF, MoonBridge.LI_TILT_UNKNOWN);
        assertEquals((short) 0xFFFF, MoonBridge.LI_ROT_UNKNOWN);
        assertTrue(Modifier.isStatic(MoonBridge.class.getField("VIDEO_FORMAT_H264").getModifiers()));

        MoonBridge.AudioConfiguration.class.getConstructor(int.class, int.class);
        assertEquals(int.class, MoonBridge.AudioConfiguration.class.getField("channelCount").getType());
        assertEquals(int.class, MoonBridge.AudioConfiguration.class.getField("channelMask").getType());
        assertEquals(0x00030002, new MoonBridge.AudioConfiguration(2, 0x3).getSurroundAudioInfo());
        assertEquals(0x000302CA, new MoonBridge.AudioConfiguration(2, 0x3).toInt());

        MoonBridge.class.getMethod("CAPABILITY_SLICES_PER_FRAME", byte.class);
        MoonBridge.class.getMethod("setupBridge", VideoDecoderRenderer.class, AudioRenderer.class, NvConnectionListener.class);
        MoonBridge.class.getMethod("cleanupBridge");
        MoonBridge.class.getMethod("bridgeDrSetup", int.class, int.class, int.class, int.class);
        MoonBridge.class.getMethod("bridgeArInit", int.class, int.class, int.class);
        MoonBridge.class.getMethod("bridgeClStageFailed", int.class, int.class);
        MoonBridge.class.getMethod("bridgeClSetControllerLED", short.class, byte.class, byte.class, byte.class);
        MoonBridge.class.getMethod(
                "startConnection",
                String.class,
                String.class,
                String.class,
                String.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                byte[].class,
                byte[].class,
                int.class,
                int.class,
                int.class);
        MoonBridge.class.getMethod("stopConnection");
        MoonBridge.class.getMethod("sendKeyboardInput", short.class, byte.class, byte.class, byte.class);
    }

    @Test
    public void pairingAndHttpKeepJavaCompatibleApis() throws Exception {
        assertEquals(47989, NvHTTP.DEFAULT_HTTP_PORT);
        assertEquals(3000, NvHTTP.SHORT_CONNECTION_TIMEOUT);
        assertEquals(5000, NvHTTP.LONG_CONNECTION_TIMEOUT);
        assertEquals(7000, NvHTTP.READ_TIMEOUT);
        NvHTTP.class.getConstructor(
                ComputerDetails.AddressTuple.class,
                int.class,
                String.class,
                X509Certificate.class,
                LimelightCryptoProvider.class);
        NvHTTP.class.getMethod("getHttpsUrl", boolean.class);
        NvHTTP.class.getMethod("getComputerDetails", String.class);
        NvHTTP.class.getMethod("getComputerDetails", boolean.class);
        NvHTTP.class.getMethod("getPairState", String.class);
        NvHTTP.class.getMethod("getAppListByReader", Reader.class);
        NvHTTP.class.getMethod("launchApp", ConnectionContext.class, String.class, String.class, int.class, boolean.class, boolean.class);
        NvHTTP.class.getMethod("quitApp", String.class);
        NvHTTP.class.getMethod("getClipboard");
        NvHTTP.class.getMethod("sendClipboard", String.class);
        NvHTTP.class.getDeclaredMethod("getXmlString", String.class, String.class, boolean.class);
        NvHTTP.class.getDeclaredMethod("parseServerMaxLaunchRefreshRate", String.class);
        NvHTTP.class.getDeclaredMethod("parseCurrentGameOwned", String.class);

        PairingManager.class.getConstructor(NvHTTP.class, LimelightCryptoProvider.class);
        PairingManager.class.getMethod("generatePinString");
        PairingManager.class.getMethod("getPairedCert");
        PairingManager.class.getMethod("pair", String.class, String.class, String.class);
        PairingManager.class.getMethod("pair", String.class, String.class, String.class, boolean.class);
        assertEquals(5, PairingManager.PairState.values().length);
        assertTrue(PairingManager.generatePinString().matches("\\d{4}"));
    }

    @Test
    public void nvConnectionKeepsJavaCompatibleApisAndLaunchRateBehavior() throws Exception {
        NvConnection.class.getConstructor(
                Context.class,
                ComputerDetails.AddressTuple.class,
                int.class,
                String.class,
                StreamConfiguration.class,
                LimelightCryptoProvider.class,
                X509Certificate.class);
        NvConnection.class.getMethod("stop");
        NvConnection.class.getDeclaredMethod("negotiateLaunchRefreshRate", float.class, int.class);
        NvConnection.class.getMethod("getSessionToken");
        NvConnection.class.getMethod("start", AudioRenderer.class, VideoDecoderRenderer.class, NvConnectionListener.class);
        NvConnection.class.getMethod("setWatchOnlyRequested", boolean.class);
        NvConnection.class.getMethod("sendExecServerCmd", int.class);
        NvConnection.class.getMethod("sendMouseMove", short.class, short.class);
        NvConnection.class.getMethod("sendMouseButtonDown", byte.class);
        NvConnection.class.getMethod("sendControllerInput", short.class, short.class, int.class, byte.class, byte.class, short.class, short.class, short.class, short.class);
        NvConnection.class.getMethod("sendKeyboardInput", short.class, byte.class, byte.class, byte.class);
        NvConnection.class.getMethod("sendTouchEvent", byte.class, int.class, float.class, float.class, float.class, float.class, float.class, short.class);
        NvConnection.class.getMethod("sendPenEvent", byte.class, byte.class, byte.class, float.class, float.class, float.class, float.class, float.class, short.class, byte.class);
        NvConnection.class.getMethod("sendUtf8Text", String.class);
        NvConnection.class.getMethod("findExternalAddressForMdns", String.class, int.class);

        assertEquals(60.0f, NvConnection.negotiateLaunchRefreshRate(120.0f, 60), 0.001f);
        assertEquals(75.0f, NvConnection.negotiateLaunchRefreshRate(75.0f, 120), 0.001f);
    }
}
