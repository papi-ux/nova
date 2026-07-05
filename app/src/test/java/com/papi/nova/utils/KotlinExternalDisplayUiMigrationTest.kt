package com.papi.nova.utils

import android.app.Activity
import android.content.Context
import android.view.View
import com.papi.nova.StartExternalDisplayControlReceiver
import com.papi.nova.nvstream.http.ComputerDetails
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinExternalDisplayUiMigrationTest {
    @Test
    fun externalDisplayUiHelpersAreKotlinSources() {
        val names = arrayOf(
            "utils/ExternalDisplayControlActivity",
            "utils/UiHelper"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun externalDisplayUiHelpersKeepJavaCompatibleApis() {
        assertEquals("launchIntent", ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT)
        assertEquals(1, ExternalDisplayControlActivity.SECONDARY_SCREEN_NOTIFICATION_ID)
        assertEquals(
            "com.papi.nova.action.START_EXTERNAL_DISPLAY_CONTROL",
            StartExternalDisplayControlReceiver.ACTION_START_EXTERNAL_DISPLAY_CONTROL
        )
        assertTrue(Modifier.isStatic(ExternalDisplayControlActivity::class.java.getField("EXTRA_LAUNCH_INTENT").modifiers))
        assertTrue(Modifier.isStatic(ExternalDisplayControlActivity::class.java.getField("instance").modifiers))
        assertTrue(
            Modifier.isStatic(
                StartExternalDisplayControlReceiver::class.java
                    .getField("ACTION_START_EXTERNAL_DISPLAY_CONTROL")
                    .modifiers
            )
        )
        ExternalDisplayControlActivity::class.java.getConstructor()
        ExternalDisplayControlActivity::class.java.getMethod("closeExternalDisplayControl")
        ExternalDisplayControlActivity::class.java.getMethod("toggleKeyboard")
        ExternalDisplayControlActivity::class.java.getMethod("toggleFullKeyboard")
        ExternalDisplayControlActivity::class.java.getMethod("toggleGameMenu")
        ExternalDisplayControlActivity::class.java.getMethod("toggleZoomMode", Boolean::class.javaPrimitiveType!!)
        ExternalDisplayControlActivity::class.java.getMethod("showGameMenu")

        UiHelper::class.java.getMethod("isTvDevice", Context::class.java)
        UiHelper::class.java.getMethod("applyTvFocusStyle", View::class.java)
        UiHelper::class.java.getMethod("applyTvFocusStyle", Context::class.java, View::class.java)
        UiHelper::class.java.getMethod("notifyStreamConnecting", Context::class.java)
        UiHelper::class.java.getMethod("notifyStreamConnected", Context::class.java)
        UiHelper::class.java.getMethod("notifyStreamEnteringPiP", Context::class.java)
        UiHelper::class.java.getMethod("notifyStreamExitingPiP", Context::class.java)
        UiHelper::class.java.getMethod("notifyStreamEnded", Context::class.java)
        UiHelper::class.java.getMethod("setLocale", Activity::class.java)
        UiHelper::class.java.getMethod("applyStatusBarPadding", View::class.java)
        UiHelper::class.java.getMethod("notifyNewRootView", Activity::class.java)
        UiHelper::class.java.getMethod("showDecoderCrashDialog", Activity::class.java)
        StartExternalDisplayControlReceiver::class.java.getMethod(
            "requestFocusToExternalDisplayControl",
            Context::class.java,
            Int::class.javaPrimitiveType!!
        )
        UiHelper::class.java.getMethod(
            "displayConfirmationDialog",
            Activity::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Runnable::class.java,
            Runnable::class.java
        )
        UiHelper::class.java.getMethod(
            "displayVdisplayConfirmationDialog",
            Activity::class.java,
            ComputerDetails::class.java,
            Runnable::class.java,
            Runnable::class.java
        )
        UiHelper::class.java.getMethod(
            "displayQuitConfirmationDialog",
            Activity::class.java,
            Runnable::class.java,
            Runnable::class.java
        )
        UiHelper::class.java.getMethod(
            "displayDeletePcConfirmationDialog",
            Activity::class.java,
            ComputerDetails::class.java,
            Runnable::class.java,
            Runnable::class.java
        )
        UiHelper::class.java.getMethod("dpToPx", Context::class.java, Float::class.javaPrimitiveType!!)
    }

    @Test
    fun externalDisplayReceiverIsPrivateInManifest() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(
            Regex(
                """<receiver\s+android:name="\.StartExternalDisplayControlReceiver"\s+android:exported="false"\s*/>"""
            ).containsMatchIn(manifest)
        )
    }
}
