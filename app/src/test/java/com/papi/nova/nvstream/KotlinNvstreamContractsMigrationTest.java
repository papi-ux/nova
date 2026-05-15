package com.papi.nova.nvstream;

import com.papi.nova.api.PolarisGame;
import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.nvstream.http.NvApp;
import com.papi.nova.nvstream.http.NvHTTP;
import com.papi.nova.nvstream.http.PairingManager;
import com.papi.nova.nvstream.jni.MoonBridge;
import com.papi.nova.shadows.ShadowMoonBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {ShadowMoonBridge.class})
public class KotlinNvstreamContractsMigrationTest {
    @Test
    public void nvstreamContractClassesAreKotlinSources() {
        String[] paths = {
                "src/main/java/com/papi/nova/nvstream/http/NvApp",
                "src/main/java/com/papi/nova/nvstream/http/ComputerDetails",
                "src/main/java/com/papi/nova/nvstream/StreamConfiguration"
        };

        for (String path : paths) {
            File javaFile = new File(path + ".java");
            File kotlinFile = new File(path + ".kt");
            assertFalse(path + " should no longer be a Java source", javaFile.exists());
            assertTrue(path + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void contractClassesKeepJavaCompatibleApis() throws Exception {
        NvApp.class.getConstructor();
        NvApp.class.getConstructor(String.class);
        NvApp.class.getConstructor(String.class, String.class, int.class, boolean.class);
        assertEquals(String.class, NvApp.class.getField("REMOTE_INPUT_UUID").getType());
        assertTrue(Modifier.isStatic(NvApp.class.getField("REMOTE_INPUT_UUID").getModifiers()));
        NvApp.class.getMethod("setAppName", String.class);
        NvApp.class.getMethod("setAppUUID", String.class);
        NvApp.class.getMethod("setAppId", String.class);
        NvApp.class.getMethod("setAppId", int.class);
        NvApp.class.getMethod("setAppIndex", String.class);
        NvApp.class.getMethod("setAppIndex", int.class);
        NvApp.class.getMethod("setHdrSupported", boolean.class);
        assertEquals(boolean.class, NvApp.class.getMethod("applyPolarisMetadata", PolarisGame.class).getReturnType());
        assertEquals(String.class, NvApp.class.getMethod("getMetadataKey").getReturnType());

        ComputerDetails.class.getConstructor();
        ComputerDetails.class.getConstructor(ComputerDetails.class);
        ComputerDetails.class.getMethod("guessExternalPort");
        ComputerDetails.class.getMethod("update", ComputerDetails.class);
        ComputerDetails.AddressTuple.class.getConstructor(String.class, int.class);
        assertEquals(String.class, ComputerDetails.AddressTuple.class.getField("address").getType());
        assertEquals(int.class, ComputerDetails.AddressTuple.class.getField("port").getType());
        assertPublicField("uuid", String.class);
        assertPublicField("localAddress", ComputerDetails.AddressTuple.class);
        assertPublicField("serverCert", X509Certificate.class);
        assertPublicField("state", ComputerDetails.State.class);
        assertPublicField("permission", int.class);
        assertPublicField("serverCommands", List.class);

        assertEquals(0, StreamConfiguration.INVALID_APP_ID);
        assertEquals(0, StreamConfiguration.STREAM_CFG_LOCAL);
        assertEquals(1, StreamConfiguration.STREAM_CFG_REMOTE);
        assertEquals(2, StreamConfiguration.STREAM_CFG_AUTO);
        StreamConfiguration.Builder.class.getConstructor();
        assertBuilderMethod("setApp", NvApp.class);
        assertBuilderMethod("setRemoteConfiguration", int.class);
        assertBuilderMethod("setResolution", int.class, int.class);
        assertBuilderMethod("setRefreshRate", float.class);
        assertBuilderMethod("setLaunchRefreshRate", float.class);
        assertBuilderMethod("setVirtualDisplay", boolean.class);
        assertBuilderMethod("setDisplayModeExplicit", boolean.class);
        assertBuilderMethod("setResolutionScaleFactor", int.class);
        assertBuilderMethod("setBitrate", int.class);
        assertBuilderMethod("setEnableSops", boolean.class);
        assertBuilderMethod("enableAdaptiveResolution", boolean.class);
        assertBuilderMethod("enableLocalAudioPlayback", boolean.class);
        assertBuilderMethod("setMaxPacketSize", int.class);
        assertBuilderMethod("setAttachedGamepadMask", int.class);
        assertBuilderMethod("setAttachedGamepadMaskByCount", int.class);
        assertBuilderMethod("setPersistGamepadsAfterDisconnect", boolean.class);
        assertBuilderMethod("setClientRefreshRateX100", int.class);
        assertBuilderMethod("setAudioConfiguration", MoonBridge.AudioConfiguration.class);
        assertBuilderMethod("setSupportedVideoFormats", int.class);
        assertBuilderMethod("setColorRange", int.class);
        assertBuilderMethod("setColorSpace", int.class);
        assertBuilderMethod("setEnableUltraLowLatency", boolean.class);
        assertBuilderMethod("setForceFreshLaunch", boolean.class);
        assertEquals(StreamConfiguration.class, StreamConfiguration.Builder.class.getMethod("build").getReturnType());
    }

    @Test
    public void addressTupleKeepsValidationNormalizationAndEquality() {
        ComputerDetails.AddressTuple ipv4 = new ComputerDetails.AddressTuple("10.0.0.2", 47984);
        ComputerDetails.AddressTuple same = new ComputerDetails.AddressTuple("10.0.0.2", 47984);
        ComputerDetails.AddressTuple different = new ComputerDetails.AddressTuple("10.0.0.3", 47984);
        ComputerDetails.AddressTuple ipv6 = new ComputerDetails.AddressTuple("[2001:db8::1]", 47989);

        assertEquals("10.0.0.2", ipv4.address);
        assertEquals(47984, ipv4.port);
        assertEquals("10.0.0.2:47984", ipv4.toString());
        assertEquals("2001:db8::1", ipv6.address);
        assertEquals("[2001:db8::1]:47989", ipv6.toString());
        assertEquals(ipv4, same);
        assertEquals(ipv4.hashCode(), same.hashCode());
        assertNotEquals(ipv4, different);

        assertAddressTupleThrows(null, 47989, "Address cannot be null");
        assertAddressTupleThrows("10.0.0.2", 0, "Invalid port");
    }

    @Test
    public void computerDetailsKeepsPortPrecedenceAndUpdateRules() {
        ComputerDetails details = new ComputerDetails();
        assertEquals(ComputerDetails.State.UNKNOWN, details.state);
        assertEquals(NvHTTP.DEFAULT_HTTP_PORT, details.guessExternalPort());

        details.localAddress = new ComputerDetails.AddressTuple("local", 1);
        assertEquals(1, details.guessExternalPort());
        details.ipv6Address = new ComputerDetails.AddressTuple("::1", 2);
        assertEquals(2, details.guessExternalPort());
        details.activeAddress = new ComputerDetails.AddressTuple("active", 3);
        assertEquals(3, details.guessExternalPort());
        details.remoteAddress = new ComputerDetails.AddressTuple("remote", 4);
        assertEquals(4, details.guessExternalPort());
        details.externalPort = 5;
        assertEquals(5, details.guessExternalPort());

        ComputerDetails base = new ComputerDetails();
        base.remoteAddress = new ComputerDetails.AddressTuple("remote", 1111);
        base.macAddress = "AA:BB:CC:DD:EE:FF";
        base.libraryState = ComputerDetails.LibraryState.AVAILABLE;

        ComputerDetails update = new ComputerDetails();
        update.state = ComputerDetails.State.ONLINE;
        update.pairState = PairingManager.PairState.PAIRED;
        update.localAddress = new ComputerDetails.AddressTuple("127.0.0.1", 2222);
        update.externalPort = 3333;
        update.macAddress = "00:00:00:00:00:00";
        update.libraryState = ComputerDetails.LibraryState.UNKNOWN;

        base.update(update);

        assertNull(base.localAddress);
        assertEquals(3333, base.remoteAddress.port);
        assertEquals("AA:BB:CC:DD:EE:FF", base.macAddress);
        assertEquals(ComputerDetails.LibraryState.AVAILABLE, base.libraryState);

        ComputerDetails copy = new ComputerDetails(base);
        assertEquals(base.state, copy.state);
        assertSame(base.remoteAddress, copy.remoteAddress);
        assertEquals(base.libraryState, copy.libraryState);
    }

    @Test
    public void nvAppKeepsParsingMetadataLabelsAndStringOutput() throws Exception {
        NvApp app = new NvApp("Game");
        assertEquals("Game", app.getAppName());
        assertEquals("", app.getAppUUID());
        assertFalse(app.isInitialized());

        app.setAppId("42");
        app.setAppIndex("7");
        app.setAppId("bad-id");
        app.setAppIndex("bad-index");
        app.setHdrSupported(true);

        assertEquals(42, app.getAppId());
        assertEquals(7, app.getAppIndex());
        assertTrue(app.isInitialized());
        assertTrue(app.isHdrSupported());
        assertFalse(app.applyPolarisMetadata(null));

        PolarisGame game = new PolarisGame(
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
                Collections.emptyList(),
                0L,
                false,
                false,
                null);

        assertTrue(app.applyPolarisMetadata(game));
        assertFalse(app.applyPolarisMetadata(game));
        assertEquals("other", app.getSource());
        assertEquals("steam", app.getLauncherSource());
        assertEquals("linux", app.getPlatform());
        assertEquals("proton", app.getRuntime());
        assertEquals("12345", app.getSteamAppid());
        assertEquals("Steam", app.getSourceLabel());
        assertEquals("Linux", app.getPlatformLabel());
        assertEquals("Proton", app.getRuntimeLabel());
        assertEquals("Steam · Linux · Proton", app.getMetadataLabel());
        assertEquals("other|steam|big picture|linux|proton|12345|desktop", app.getMetadataKey());

        String appText = app.toString();
        assertTrue(appText.contains("Name: Game"));
        assertTrue(appText.contains("ID: 42"));
        assertTrue(appText.contains("HDR Supported: Yes"));
        assertTrue(appText.contains("Source: Steam · Linux · Proton"));

        NvApp initialized = new NvApp("Other", "uuid", 100, false);
        assertTrue(initialized.isInitialized());
        assertEquals("uuid", initialized.getAppUUID());
        assertEquals(100, initialized.getAppId());
    }

    @Test
    public void streamConfigurationKeepsDefaultsAndBuilderSemantics() {
        StreamConfiguration defaults = new StreamConfiguration.Builder().build();

        assertEquals("Steam", defaults.getApp().getAppName());
        assertEquals(1280, defaults.getWidth());
        assertEquals(720, defaults.getHeight());
        assertEquals(60, defaults.getRefreshRate());
        assertEquals(60, defaults.getLaunchRefreshRate());
        assertFalse(defaults.getVirtualDisplay());
        assertFalse(defaults.getDisplayModeExplicit());
        assertEquals(100, defaults.getResolutionScaleFactor());
        assertEquals(10000, defaults.getBitrate());
        assertEquals(1024, defaults.getMaxPacketSize());
        assertEquals(StreamConfiguration.STREAM_CFG_AUTO, defaults.getRemote());
        assertTrue(defaults.getSops());
        assertFalse(defaults.getAdaptiveResolutionEnabled());
        assertFalse(defaults.getPlayLocalAudio());
        assertSame(MoonBridge.AUDIO_CONFIGURATION_STEREO, defaults.getAudioConfiguration());
        assertEquals(MoonBridge.VIDEO_FORMAT_H264, defaults.getSupportedVideoFormats());
        assertEquals(0, defaults.getAttachedGamepadMask());
        assertFalse(defaults.getPersistGamepadsAfterDisconnect());
        assertEquals(0, defaults.getClientRefreshRateX100());
        assertEquals(0, defaults.getColorRange());
        assertEquals(0, defaults.getColorSpace());
        assertFalse(defaults.getEnableUltraLowLatency());
        assertFalse(defaults.getForceFreshLaunch());

        NvApp customApp = new NvApp("Custom");
        StreamConfiguration.Builder builder = new StreamConfiguration.Builder();
        assertSame(builder, builder.setApp(customApp));

        StreamConfiguration config = builder
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
                .build();

        assertSame(customApp, config.getApp());
        assertEquals(StreamConfiguration.STREAM_CFG_REMOTE, config.getRemote());
        assertEquals(1920, config.getWidth());
        assertEquals(1080, config.getHeight());
        assertEquals(59940, config.getRefreshRate());
        assertEquals(119880, config.getLaunchRefreshRate());
        assertTrue(config.getVirtualDisplay());
        assertTrue(config.getDisplayModeExplicit());
        assertEquals(75, config.getResolutionScaleFactor());
        assertEquals(20000, config.getBitrate());
        assertFalse(config.getSops());
        assertTrue(config.getAdaptiveResolutionEnabled());
        assertTrue(config.getPlayLocalAudio());
        assertEquals(1200, config.getMaxPacketSize());
        assertEquals(0b111, config.getAttachedGamepadMask());
        assertTrue(config.getPersistGamepadsAfterDisconnect());
        assertEquals(5994, config.getClientRefreshRateX100());
        assertSame(MoonBridge.AUDIO_CONFIGURATION_51_SURROUND, config.getAudioConfiguration());
        assertEquals(MoonBridge.VIDEO_FORMAT_H265, config.getSupportedVideoFormats());
        assertEquals(MoonBridge.COLOR_RANGE_FULL, config.getColorRange());
        assertEquals(MoonBridge.COLORSPACE_REC_2020, config.getColorSpace());
        assertTrue(config.getEnableUltraLowLatency());
        assertTrue(config.getForceFreshLaunch());
    }

    private static void assertPublicField(String name, Class<?> type) throws NoSuchFieldException {
        Field field = ComputerDetails.class.getField(name);
        assertEquals(type, field.getType());
        assertTrue(Modifier.isPublic(field.getModifiers()));
    }

    private static void assertBuilderMethod(String name, Class<?>... params) throws NoSuchMethodException {
        assertEquals(StreamConfiguration.Builder.class, StreamConfiguration.Builder.class.getMethod(name, params).getReturnType());
    }

    private static void assertAddressTupleThrows(String address, int port, String message) {
        try {
            new ComputerDetails.AddressTuple(address, port);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertEquals(message, e.getMessage());
        }
    }
}
