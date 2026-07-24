package com.papi.nova.utils

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.view.Display
import android.view.View
import com.papi.nova.StartExternalDisplayControlReceiver
import com.papi.nova.binding.input.GameInputDevice
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
            "utils/ExternalDisplayControlPresentation",
            "utils/GameDisplayLaunchTrampolineActivity",
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
        assertEquals("launchIntent", GameDisplayLaunchTrampolineActivity.EXTRA_LAUNCH_INTENT)
        assertEquals(1, ExternalDisplayControlPresentation.SECONDARY_SCREEN_NOTIFICATION_ID)
        assertEquals(
            "com.papi.nova.action.START_EXTERNAL_DISPLAY_CONTROL",
            StartExternalDisplayControlReceiver.ACTION_START_EXTERNAL_DISPLAY_CONTROL
        )
        assertTrue(
            Modifier.isStatic(
                GameDisplayLaunchTrampolineActivity::class.java
                    .getField("EXTRA_LAUNCH_INTENT")
                    .modifiers
            )
        )
        assertTrue(Presentation::class.java.isAssignableFrom(ExternalDisplayControlPresentation::class.java))
        assertTrue(
            Modifier.isStatic(
                StartExternalDisplayControlReceiver::class.java
                    .getField("ACTION_START_EXTERNAL_DISPLAY_CONTROL")
                    .modifiers
            )
        )
        ExternalDisplayControlPresentation::class.java.getConstructor(
            com.papi.nova.Game::class.java,
            Display::class.java
        )
        ExternalDisplayControlPresentation::class.java.getMethod("toggleKeyboard")
        ExternalDisplayControlPresentation::class.java.getMethod("toggleFullKeyboard")
        ExternalDisplayControlPresentation::class.java.getMethod("toggleGameMenu")
        ExternalDisplayControlPresentation::class.java.getMethod("toggleZoomMode", Boolean::class.javaPrimitiveType!!)
        ExternalDisplayControlPresentation::class.java.getMethod("showGameMenu")
        ExternalDisplayControlPresentation::class.java.getMethod(
            "showGameMenuOnCompanion",
            GameInputDevice::class.java,
        ).also { assertEquals(Boolean::class.javaPrimitiveType, it.returnType) }
        ExternalDisplayControlPresentation::class.java.getMethod("isCompanionDisplayAvailable")
        ExternalDisplayControlPresentation::class.java.getMethod(
            "shouldMigrateOpenMenuToStream",
            Boolean::class.javaPrimitiveType,
        )
        ExternalDisplayControlPresentation::class.java.getMethod("hideGameMenu")
        ExternalDisplayControlPresentation::class.java.getMethod("isGameMenuOpen")
        com.papi.nova.GameMenu::class.java.getMethod(
            "setOnMenuDismissedListener",
            Function0::class.java,
        )
        com.papi.nova.Game::class.java.getMethod(
            "showGameMenuFromDisplay",
            Int::class.javaPrimitiveType!!,
            GameInputDevice::class.java,
        )
        com.papi.nova.Game::class.java.getMethod(
            "handleQuickMenuBackFromDisplay",
            Int::class.javaPrimitiveType!!,
        )
        com.papi.nova.GameMenu::class.java.getConstructor(com.papi.nova.Game::class.java)
        com.papi.nova.GameMenu::class.java.getConstructor(
            com.papi.nova.Game::class.java,
            Context::class.java
        )
        com.papi.nova.GameMenu::class.java.getConstructor(
            com.papi.nova.Game::class.java,
            Context::class.java,
            Int::class.javaObjectType
        )
        com.papi.nova.Game::class.java.getMethod("selectMouseMode", Context::class.java)
        com.papi.nova.Game::class.java.getMethod(
            "selectMouseMode",
            Context::class.java,
            Int::class.javaObjectType
        )
        GameDisplayLaunchTrampolineActivity::class.java.getMethod(
            "launchGameOnRequestedDisplay",
            Context::class.java,
            android.content.Intent::class.java
        )

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
            Context::class.java
        )
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

    @Test
    fun displayFocusTelemetryKeepsStablePrivacySafeJvmContract() {
        val telemetryClass = runCatching {
            Class.forName("com.papi.nova.utils.DisplayFocusTelemetry")
        }.getOrElse {
            assertTrue("DisplayFocusTelemetry must exist for Thor field evidence", false)
            return
        }
        val game = telemetryClass.getMethod(
            "game",
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
        )
        val companion = telemetryClass.getMethod(
            "companion",
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
        )

        assertTrue(Modifier.isStatic(game.modifiers))
        assertTrue(Modifier.isStatic(companion.modifiers))
        assertEquals(
            "Nova: Android display focus role=game display_id=7 window=true game_top_resumed=false",
            game.invoke(null, 7, true, false),
        )
        assertEquals(
            "Nova: Android display focus role=companion display_id=3 window=false game_top_resumed=true",
            companion.invoke(null, 3, false, true),
        )
    }
}
