package com.papi.nova

import android.app.Activity
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.discovery.DiscoveryService
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.mdns.MdnsDiscoveryListener
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.utils.ServerHelper
import com.papi.nova.utils.ShortcutHelper
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class KotlinLaunchHelpersMigrationTest {
    @Test
    fun launchHelpersAreKotlinSources() {
        val names = arrayOf(
            "ShortcutTrampoline",
            "discovery/DiscoveryService",
            "utils/ServerHelper",
            "utils/ShortcutHelper",
            "utils/TvChannelHelper"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun launchHelpersKeepJavaCompatibleApis() {
        val booleanType = Boolean::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!
        val longObjectType = Long::class.javaObjectType

        assertTrue(Activity::class.java.isAssignableFrom(ShortcutTrampoline::class.java))

        assertTrue(Service::class.java.isAssignableFrom(DiscoveryService::class.java))
        DiscoveryService.DiscoveryBinder::class.java.getMethod("setListener", MdnsDiscoveryListener::class.java)
        DiscoveryService.DiscoveryBinder::class.java.getMethod("startDiscovery", intType)
        DiscoveryService.DiscoveryBinder::class.java.getMethod("stopDiscovery")
        assertEquals(List::class.java, DiscoveryService.DiscoveryBinder::class.java.getMethod("getComputerSet").returnType)

        assertEquals("android.conntest.moonlight-stream.org", ServerHelper.CONNECTION_TEST_SERVER)
        ServerHelper::class.java.getMethod("getCurrentAddressFromComputer", ComputerDetails::class.java)
        ServerHelper::class.java.getMethod("createPcShortcutIntent", Activity::class.java, ComputerDetails::class.java)
        ServerHelper::class.java.getMethod("createAppShortcutIntent", Activity::class.java, ComputerDetails::class.java, NvApp::class.java)
        ServerHelper::class.java.getMethod("getActiveDisplay", Context::class.java, PreferenceConfiguration::class.java)
        ServerHelper::class.java.getMethod("getSecondaryDisplay", Context::class.java)
        ServerHelper::class.java.getMethod(
            "createStartIntent",
            Activity::class.java,
            NvApp::class.java,
            ComputerDetails::class.java,
            ComputerManagerService.ComputerManagerBinder::class.java,
            booleanType
        )
        ServerHelper::class.java.getMethod("doNetworkTest", Activity::class.java)

        assertEquals(778, ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE)
        assertEquals("host_uuid", ShortcutHelper.KEY_HOST_UUID)
        assertEquals("host_name", ShortcutHelper.KEY_HOST_NAME)
        assertEquals("app_uuid", ShortcutHelper.KEY_APP_UUID)
        assertEquals("app_name", ShortcutHelper.KEY_APP_NAME)
        assertEquals("app_id", ShortcutHelper.KEY_APP_ID)
        ShortcutHelper::class.java.getConstructor(Activity::class.java)
        ShortcutHelper::class.java.getMethod("reportComputerShortcutUsed", ComputerDetails::class.java)
        ShortcutHelper::class.java.getMethod(
            "createPinnedGameShortcut",
            ComputerDetails::class.java,
            NvApp::class.java,
            Bitmap::class.java
        )
        ShortcutHelper::class.java.getMethod("writeArtFileToUri", Activity::class.java, Uri::class.java)

        val previewBuilder = Class.forName("com.papi.nova.utils.TvChannelHelper\$PreviewProgramBuilder")
        previewBuilder.getDeclaredConstructor()
        previewBuilder.getDeclaredMethod("setChannelId", longObjectType)
        previewBuilder.getDeclaredMethod("setType", intType)
        previewBuilder.getDeclaredMethod("setTitle", String::class.java)
        previewBuilder.getDeclaredMethod("setPosterArtAspectRatio", intType)
        previewBuilder.getDeclaredMethod("setIntent", Intent::class.java)
        previewBuilder.getDeclaredMethod("setIntentUri", Uri::class.java)
        previewBuilder.getDeclaredMethod("setInternalProviderId", String::class.java)
        previewBuilder.getDeclaredMethod("setPosterArtUri", Uri::class.java)
        previewBuilder.getDeclaredMethod("setWeight", intType)
        previewBuilder.getDeclaredMethod("toContentValues")
        val channelBuilder = Class.forName("com.papi.nova.utils.TvChannelHelper\$ChannelBuilder")
        channelBuilder.getDeclaredConstructor()
        channelBuilder.getDeclaredMethod("setType", String::class.java)
        channelBuilder.getDeclaredMethod("setDisplayName", String::class.java)
        channelBuilder.getDeclaredMethod("setInternalProviderId", String::class.java)
        channelBuilder.getDeclaredMethod("setAppLinkIntent", Intent::class.java)
        channelBuilder.getDeclaredMethod("toContentValues")
    }

    @Test
    fun tvChannelBuildersKeepContentValuesShape() {
        val intType = Int::class.javaPrimitiveType!!
        val poster = Uri.parse("content://poster")
        val previewBuilder = newPrivateBuilder("com.papi.nova.utils.TvChannelHelper\$PreviewProgramBuilder")
        invokeBuilder(previewBuilder, "setChannelId", Long::class.javaObjectType, 12L)
        invokeBuilder(previewBuilder, "setType", intType, 0)
        invokeBuilder(previewBuilder, "setTitle", String::class.java, "Nova")
        invokeBuilder(previewBuilder, "setPosterArtAspectRatio", intType, 1)
        invokeBuilder(previewBuilder, "setIntentUri", Uri::class.java, Uri.parse("nova://launch"))
        invokeBuilder(previewBuilder, "setInternalProviderId", String::class.java, "game-1")
        invokeBuilder(previewBuilder, "setPosterArtUri", Uri::class.java, poster)
        invokeBuilder(previewBuilder, "setWeight", intType, 7)
        val values = toContentValues(previewBuilder)

        assertEquals(12L, values.getAsLong("channel_id").toLong())
        assertEquals("Nova", values.getAsString("title"))
        assertEquals("game-1", values.getAsString("internal_provider_id"))
        assertEquals(poster.toString(), values.getAsString("poster_art_uri"))

        val channelBuilder = newPrivateBuilder("com.papi.nova.utils.TvChannelHelper\$ChannelBuilder")
        invokeBuilder(channelBuilder, "setType", String::class.java, "TYPE_PREVIEW")
        invokeBuilder(channelBuilder, "setDisplayName", String::class.java, "Nova")
        invokeBuilder(channelBuilder, "setInternalProviderId", String::class.java, "nova")
        invokeBuilder(
            channelBuilder,
            "setAppLinkIntent",
            Intent::class.java,
            Intent(Intent.ACTION_VIEW, Uri.parse("nova://home"))
        )
        val channel = toContentValues(channelBuilder)

        assertEquals("TYPE_PREVIEW", channel.getAsString("type"))
        assertEquals("Nova", channel.getAsString("display_name"))
        assertEquals("nova", channel.getAsString("internal_provider_id"))
    }

    @Test
    fun artFileUrisRejectPrivateAndInternalContentTargets() {
        val activity = Robolectric.buildActivity(ShortcutTrampoline::class.java).get()

        assertFalse(
            isSafeArtFileUri(
                activity,
                Uri.parse("file:///data/data/com.papi.nova/private.art")
            )
        )
        assertFalse(
            isSafeArtFileUri(
                activity,
                Uri.parse("content://${BuildConfig.APPLICATION_ID}.fileprovider/cache/private.art")
            )
        )
        assertFalse(
            isSafeArtFileUri(
                activity,
                Uri.parse("content://${PosterContentProvider.AUTHORITY}/boxart/host/private.art")
            )
        )
        assertFalse(
            isSafeArtFileUri(
                activity,
                Uri.parse("content://com.example.provider/document/not-art.txt")
            )
        )
        assertTrue(
            isSafeArtFileUri(
                activity,
                Uri.parse("file:///sdcard/Download/game.art")
            )
        )
    }

    private fun newPrivateBuilder(className: String): Any {
        return try {
            val constructor: Constructor<*> = Class.forName(className).getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance()
        } catch (e: Exception) {
            throw AssertionError("Unable to construct $className", e)
        }
    }

    private fun invokeBuilder(target: Any, methodName: String, paramType: Class<*>, value: Any?) {
        try {
            val method: Method = target.javaClass.getDeclaredMethod(methodName, paramType)
            method.isAccessible = true
            method.invoke(target, value)
        } catch (e: Exception) {
            throw AssertionError("Unable to call $methodName", e)
        }
    }

    private fun isSafeArtFileUri(activity: ShortcutTrampoline, uri: Uri): Boolean {
        return try {
            val method = ShortcutTrampoline::class.java.getDeclaredMethod("isSafeArtFileUri", Uri::class.java)
            method.isAccessible = true
            method.invoke(activity, uri) as Boolean
        } catch (e: Exception) {
            throw AssertionError("Unable to call isSafeArtFileUri", e)
        }
    }

    private fun toContentValues(target: Any): ContentValues {
        return try {
            val method: Method = target.javaClass.getDeclaredMethod("toContentValues")
            method.isAccessible = true
            method.invoke(target) as ContentValues
        } catch (e: Exception) {
            throw AssertionError("Unable to call toContentValues", e)
        }
    }
}
