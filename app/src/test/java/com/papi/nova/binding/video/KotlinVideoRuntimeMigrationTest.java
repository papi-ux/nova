package com.papi.nova.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Choreographer;
import android.view.Surface;

import androidx.test.core.app.ApplicationProvider;

import com.papi.nova.nvstream.av.video.VideoDecoderRenderer;
import com.papi.nova.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
public class KotlinVideoRuntimeMigrationTest {
    @Test
    public void mediaCodecRuntimeClassesAreKotlinSources() {
        String[] names = {
                "MediaCodecHelper"
        };

        for (String name : names) {
            File javaFile = new File("src/main/java/com/papi/nova/binding/video/" + name + ".java");
            File kotlinFile = new File("src/main/java/com/papi/nova/binding/video/" + name + ".kt");
            assertFalse(name + " should no longer be a Java source", javaFile.exists());
            assertTrue(name + " should be migrated to Kotlin", kotlinFile.exists());
        }
    }

    @Test
    public void mediaCodecHelperKeepsJavaCompatibleStaticsAndPrefixDecisions() throws Exception {
        MediaCodecHelper.class.getConstructor();
        assertEquals(boolean.class, MediaCodecHelper.class.getField("SHOULD_BYPASS_SOFTWARE_BLOCK").getType());
        MediaCodecHelper.class.getMethod("initialize", android.content.Context.class, String.class);
        MediaCodecHelper.class.getMethod("setPreferStabilityDecoders", boolean.class);
        MediaCodecHelper.class.getMethod("findFirstDecoder", String.class);
        MediaCodecHelper.class.getMethod("findProbableSafeDecoder", String.class, int.class);
        MediaCodecHelper.class.getMethod("dumpDecoders");
        MediaCodecHelper.class.getMethod("readCpuinfo");
        MediaCodecHelper.class.getMethod("isExynos4Device");
        MediaCodecHelper.class.getMethod("applyExtraVendorOptions", MediaFormat.class, String.class);
        MediaCodecHelper.class.getMethod("setDecoderLowLatencyOptions",
                MediaFormat.class, MediaCodecInfo.class, boolean.class, int.class);

        MediaCodecHelper.initialize(ApplicationProvider.getApplicationContext(), "Adreno (TM) 640");

        assertTrue(MediaCodecHelper.isQualcommDecoder("OMX.qcom.video.decoder.avc"));
        assertTrue(MediaCodecHelper.isNvidiaDecoder("omx.nvidia.h264.decode"));
        assertTrue(MediaCodecHelper.decoderNeedsBaselineSpsHack("omx.intel.hw"));
        assertEquals(4, MediaCodecHelper.getDecoderOptimalSlicesPerFrame("omx.google.h264.decoder"));
        assertEquals(1, MediaCodecHelper.getDecoderOptimalSlicesPerFrame("omx.vendor.decoder"));

        MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
        MediaCodecHelper.applyExtraVendorOptions(format, "OMX.qcom.video.decoder.avc");
    }

    @Test
    public void mediaCodecDecoderRendererKeepsJavaCompatibleRuntimeApis() throws Exception {
        assertTrue(VideoDecoderRenderer.class.isAssignableFrom(MediaCodecDecoderRenderer.class));
        assertTrue(Choreographer.FrameCallback.class.isAssignableFrom(MediaCodecDecoderRenderer.class));

        MediaCodecDecoderRenderer.class.getConstructor(Activity.class, PreferenceConfiguration.class,
                CrashListener.class, int.class, boolean.class, boolean.class, boolean.class,
                String.class, PerfOverlayListener.class);
        MediaCodecDecoderRenderer.class.getMethod("setForceTightThresholds", boolean.class);
        MediaCodecDecoderRenderer.class.getMethod("setPreferLowerDelaysTimeoutUs", int.class);
        MediaCodecDecoderRenderer.class.getMethod("setPreferLowerDelays", boolean.class);
        MediaCodecDecoderRenderer.class.getMethod("setRenderTarget", Surface.class);
        MediaCodecDecoderRenderer.class.getMethod("isHevcSupported");
        MediaCodecDecoderRenderer.class.getMethod("isAvcSupported");
        MediaCodecDecoderRenderer.class.getMethod("isHevcMain10Hdr10Supported");
        MediaCodecDecoderRenderer.class.getMethod("isAv1Supported");
        MediaCodecDecoderRenderer.class.getMethod("isAv1Main10Supported");
        MediaCodecDecoderRenderer.class.getMethod("getPreferredColorSpace");
        MediaCodecDecoderRenderer.class.getMethod("getPreferredColorRange");
        MediaCodecDecoderRenderer.class.getMethod("notifyVideoForeground");
        MediaCodecDecoderRenderer.class.getMethod("notifyVideoBackground");
        MediaCodecDecoderRenderer.class.getMethod("getActiveVideoFormat");
        MediaCodecDecoderRenderer.class.getMethod("getActiveDecoderName");
        MediaCodecDecoderRenderer.class.getMethod("initializeDecoder", boolean.class);
        MediaCodecDecoderRenderer.class.getMethod("setup", int.class, int.class, int.class, int.class);
        MediaCodecDecoderRenderer.class.getMethod("doFrame", long.class);
        MediaCodecDecoderRenderer.class.getMethod("start");
        MediaCodecDecoderRenderer.class.getMethod("prepareForStop");
        MediaCodecDecoderRenderer.class.getMethod("stop");
        MediaCodecDecoderRenderer.class.getMethod("cleanup");
        MediaCodecDecoderRenderer.class.getMethod("setHdrMode", boolean.class, byte[].class);
        MediaCodecDecoderRenderer.class.getMethod("submitDecodeUnit",
                byte[].class, int.class, int.class, int.class, int.class, char.class, long.class, long.class);
        MediaCodecDecoderRenderer.class.getMethod("getCapabilities");
        MediaCodecDecoderRenderer.class.getMethod("getAverageEndToEndLatency");
        MediaCodecDecoderRenderer.class.getMethod("getAverageDecoderLatency");
        assertEquals(Boolean.class, MediaCodecDecoderRenderer.class.getMethod("performanceWasTracked").getReturnType());
        MediaCodecDecoderRenderer.class.getMethod("getMinDecoderLatency");
        MediaCodecDecoderRenderer.class.getMethod("getMinDecoderLatencyFullLog");
    }
}
