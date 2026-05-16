package com.papi.nova.manager

import android.content.Context
import android.view.Display
import com.papi.nova.binding.video.MediaCodecDecoderRenderer
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.preferences.PreferenceConfiguration
import java.io.File
import java.lang.reflect.Modifier
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class KotlinStreamSyncManagerMigrationTest {
    @Test
    fun streamSyncManagerIsKotlinSource() {
        val path = "src/main/java/com/papi/nova/manager/StreamSyncManager"

        assertFalse("$path should no longer be a Java source", File("$path.java").exists())
        assertTrue("$path should be migrated to Kotlin", File("$path.kt").exists())
    }

    @Test
    fun streamSyncManagerKeepsJavaCompatibleApis() {
        val intType = Int::class.javaPrimitiveType!!
        val floatType = Float::class.javaPrimitiveType!!
        val booleanType = Boolean::class.javaPrimitiveType!!

        val syncMode = StreamSyncManager::class.java.getField("SYNC_MODE_AUTO_SAFE")
        assertEquals(String::class.java, syncMode.type)
        assertTrue(Modifier.isStatic(syncMode.modifiers))
        assertEquals("auto_safe", syncMode.get(null))

        StreamSyncManager.StreamResolution::class.java.getConstructor(intType, intType)
        assertEquals(intType, StreamSyncManager.StreamResolution::class.java.getField("width").type)
        assertEquals(intType, StreamSyncManager.StreamResolution::class.java.getField("height").type)
        assertEquals(booleanType, StreamSyncManager.StreamResolution::class.java.getMethod("isValid").returnType)

        assertEquals(
            intType,
            StreamSyncManager::class.java.getMethod("resolveAutoSafeBitrateKbps", intType, JSONObject::class.java).returnType
        )
        assertEquals(
            StreamSyncManager.StreamResolution::class.java,
            StreamSyncManager::class.java.getMethod(
                "resolveAutoSafeResolution",
                intType,
                intType,
                JSONObject::class.java
            ).returnType
        )
        assertEquals(
            floatType,
            StreamSyncManager::class.java.getMethod("resolveAutoSafeTargetFps", floatType, JSONObject::class.java).returnType
        )
        assertEquals(
            floatType,
            StreamSyncManager::class.java.getMethod(
                "resolveDisplayCompatibleAutoSafeTargetFps",
                floatType,
                floatType,
                FloatArray::class.java
            ).returnType
        )
        assertEquals(
            booleanType,
            StreamSyncManager::class.java.getMethod("shouldPreferStabilityDecoder", JSONObject::class.java).returnType
        )
        assertEquals(
            booleanType,
            StreamSyncManager::class.java.getMethod("shouldForceFreshLaunch", JSONObject::class.java).returnType
        )
        assertEquals(
            booleanType,
            StreamSyncManager::class.java.getMethod(
                "shouldPreferStableRefreshMultiple",
                JSONObject::class.java,
                floatType
            ).returnType
        )
        assertEquals(
            JSONObject::class.java,
            StreamSyncManager::class.java.getMethod(
                "buildDeviceCapabilities",
                Context::class.java,
                Display::class.java,
                MediaCodecDecoderRenderer::class.java,
                intType,
                booleanType,
                booleanType
            ).returnType
        )
        assertEquals(
            JSONObject::class.java,
            StreamSyncManager::class.java.getMethod(
                "buildClientRuntime",
                Context::class.java,
                MediaCodecDecoderRenderer::class.java,
                floatType,
                intType,
                String::class.java,
                intType
            ).returnType
        )
        assertEquals(
            JSONObject::class.java,
            StreamSyncManager::class.java.getMethod(
                "buildAppliedStreamSettings",
                intType,
                intType,
                intType,
                floatType,
                floatType,
                booleanType,
                booleanType,
                intType,
                PreferenceConfiguration.FormatOption::class.java,
                booleanType
            ).returnType
        )
    }

    @Test
    fun streamSyncManagerKeepsJsonBuilderBehavior() {
        val applied = StreamSyncManager.buildAppliedStreamSettings(
            12000,
            1280,
            720,
            60f,
            59.94f,
            true,
            false,
            MoonBridge.VIDEO_FORMAT_AV1_MAIN8,
            PreferenceConfiguration.FormatOption.AUTO,
            true
        )

        assertEquals(12000, applied.getInt("target_bitrate_kbps"))
        assertEquals("1280x720x60", applied.getString("display_mode"))
        assertEquals(1280, applied.getInt("width"))
        assertEquals(720, applied.getInt("height"))
        assertEquals(60f, applied.getDouble("launch_refresh_rate_hz").toFloat(), 0.01f)
        assertEquals(59.94f, applied.getDouble("render_refresh_rate_hz").toFloat(), 0.01f)
        assertTrue(applied.getBoolean("virtual_display"))
        assertFalse(applied.getBoolean("hdr"))
        assertTrue(applied.getBoolean("display_mode_explicit"))
        assertEquals("av1", applied.getString("preferred_codec"))
    }
}
