package com.papi.nova.binding.video

import android.app.Activity
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Choreographer
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.nvstream.av.video.VideoDecoderRenderer
import com.papi.nova.preferences.PreferenceConfiguration
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KotlinVideoRuntimeMigrationTest {

    @Test
    fun mediaCodecRuntimeClassesAreKotlinSources() {
        val names = arrayOf(
            "MediaCodecHelper",
            "MediaCodecDecoderRenderer"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/binding/video/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/binding/video/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun mediaCodecHelperKeepsJavaCompatibleStaticsAndPrefixDecisions() {
        MediaCodecHelper::class.java.getConstructor()
        assertEquals(Boolean::class.javaPrimitiveType!!, MediaCodecHelper::class.java.getField("SHOULD_BYPASS_SOFTWARE_BLOCK").type)
        MediaCodecHelper::class.java.getMethod("initialize", android.content.Context::class.java, String::class.java)
        MediaCodecHelper::class.java.getMethod("setPreferStabilityDecoders", Boolean::class.javaPrimitiveType!!)
        MediaCodecHelper::class.java.getMethod("findFirstDecoder", String::class.java)
        MediaCodecHelper::class.java.getMethod("findProbableSafeDecoder", String::class.java, Int::class.javaPrimitiveType!!)
        MediaCodecHelper::class.java.getMethod("dumpDecoders")
        MediaCodecHelper::class.java.getMethod("readCpuinfo")
        MediaCodecHelper::class.java.getMethod("isExynos4Device")
        MediaCodecHelper::class.java.getMethod("applyExtraVendorOptions", MediaFormat::class.java, String::class.java)
        MediaCodecHelper::class.java.getMethod(
            "setDecoderLowLatencyOptions",
            MediaFormat::class.java,
            MediaCodecInfo::class.java,
            Boolean::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        )

        MediaCodecHelper.initialize(ApplicationProvider.getApplicationContext(), "Adreno (TM) 640")

        assertTrue(MediaCodecHelper.isQualcommDecoder("OMX.qcom.video.decoder.avc"))
        assertTrue(MediaCodecHelper.isNvidiaDecoder("omx.nvidia.h264.decode"))
        assertTrue(MediaCodecHelper.decoderNeedsBaselineSpsHack("omx.intel.hw"))
        assertEquals(4.toByte(), MediaCodecHelper.getDecoderOptimalSlicesPerFrame("omx.google.h264.decoder"))
        assertEquals(1.toByte(), MediaCodecHelper.getDecoderOptimalSlicesPerFrame("omx.vendor.decoder"))

        val format = MediaFormat.createVideoFormat("video/avc", 1280, 720)
        MediaCodecHelper.applyExtraVendorOptions(format, "OMX.qcom.video.decoder.avc")
    }

    @Test
    fun mediaCodecDecoderRendererKeepsJavaCompatibleRuntimeApis() {
        assertTrue(VideoDecoderRenderer::class.java.isAssignableFrom(MediaCodecDecoderRenderer::class.java))
        assertTrue(Choreographer.FrameCallback::class.java.isAssignableFrom(MediaCodecDecoderRenderer::class.java))

        MediaCodecDecoderRenderer::class.java.getConstructor(
            Activity::class.java,
            PreferenceConfiguration::class.java,
            CrashListener::class.java,
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            String::class.java,
            PerfOverlayListener::class.java
        )
        MediaCodecDecoderRenderer::class.java.getMethod("setForceTightThresholds", Boolean::class.javaPrimitiveType!!)
        MediaCodecDecoderRenderer::class.java.getMethod("setPreferLowerDelaysTimeoutUs", Int::class.javaPrimitiveType!!)
        MediaCodecDecoderRenderer::class.java.getMethod("setPreferLowerDelays", Boolean::class.javaPrimitiveType!!)
        MediaCodecDecoderRenderer::class.java.getMethod("setRenderTarget", Surface::class.java)
        MediaCodecDecoderRenderer::class.java.getMethod("isHevcSupported")
        MediaCodecDecoderRenderer::class.java.getMethod("isAvcSupported")
        MediaCodecDecoderRenderer::class.java.getMethod("isHevcMain10Hdr10Supported")
        MediaCodecDecoderRenderer::class.java.getMethod("isAv1Supported")
        MediaCodecDecoderRenderer::class.java.getMethod("isAv1Main10Supported")
        MediaCodecDecoderRenderer::class.java.getMethod("getPreferredColorSpace")
        MediaCodecDecoderRenderer::class.java.getMethod("getPreferredColorRange")
        MediaCodecDecoderRenderer::class.java.getMethod("notifyVideoForeground")
        MediaCodecDecoderRenderer::class.java.getMethod("notifyVideoBackground")
        MediaCodecDecoderRenderer::class.java.getMethod("getActiveVideoFormat")
        MediaCodecDecoderRenderer::class.java.getMethod("getActiveDecoderName")
        MediaCodecDecoderRenderer::class.java.getMethod("initializeDecoder", Boolean::class.javaPrimitiveType!!)
        MediaCodecDecoderRenderer::class.java.getMethod(
            "setup",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        )
        MediaCodecDecoderRenderer::class.java.getMethod("doFrame", Long::class.javaPrimitiveType!!)
        MediaCodecDecoderRenderer::class.java.getMethod("start")
        MediaCodecDecoderRenderer::class.java.getMethod("prepareForStop")
        MediaCodecDecoderRenderer::class.java.getMethod("stop")
        MediaCodecDecoderRenderer::class.java.getMethod("cleanup")
        MediaCodecDecoderRenderer::class.java.getMethod("setHdrMode", Boolean::class.javaPrimitiveType!!, ByteArray::class.java)
        MediaCodecDecoderRenderer::class.java.getMethod(
            "submitDecodeUnit",
            ByteArray::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Char::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!
        )
        MediaCodecDecoderRenderer::class.java.getMethod("getCapabilities")
        MediaCodecDecoderRenderer::class.java.getMethod("getAverageEndToEndLatency")
        MediaCodecDecoderRenderer::class.java.getMethod("getAverageDecoderLatency")
        assertEquals(
            Boolean::class.javaObjectType,
            MediaCodecDecoderRenderer::class.java.getMethod("performanceWasTracked").returnType
        )
        MediaCodecDecoderRenderer::class.java.getMethod("getMinDecoderLatency")
        MediaCodecDecoderRenderer::class.java.getMethod("getMinDecoderLatencyFullLog")
    }

    @Test
    fun mediaCodecDecoderWatchdogUsesQuiescedRecoveryFlush() {
        val source = readMediaCodecDecoderRendererSource()

        assertTrue(source.contains("Decoder watchdog: no output >1.2s, scheduling codec flush to recover"))
        assertTrue(source.contains("codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_FLUSH)"))
    }

    @Test
    fun mediaCodecDecoderRefreshesWatchdogOnOutputBuffers() {
        val source = readMediaCodecDecoderRendererSource()

        val latestTimestamp = source.indexOf("lastOutputNs = nowNs")
        val latestPresent = source.indexOf("presentFrame(last, nowNs)")
        assertTrue(
            "latest-only output path must refresh watchdog before presenting",
            latestTimestamp > 0 && latestTimestamp < latestPresent
        )

        val normalOutput = source.indexOf("var lastIndex = outIndex")
        val normalTimestamp = source.indexOf("lastOutputNs = System.nanoTime()", normalOutput)
        val frameCount = source.indexOf("numFramesOut++", normalOutput)
        assertTrue(
            "normal output path must refresh watchdog before counting output frames",
            normalOutput > 0 && normalTimestamp > normalOutput && normalTimestamp < frameCount
        )
    }

    private fun readMediaCodecDecoderRendererSource(): String {
        return String(
            Files.readAllBytes(Path.of("src/main/java/com/papi/nova/binding/video/MediaCodecDecoderRenderer.kt")),
            StandardCharsets.UTF_8
        )
    }
}
