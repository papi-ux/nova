package com.papi.nova.utils

import android.app.Activity
import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinUtilsCoreMigrationTest {
    @Test
    fun coreDialogAndHelpUtilsAreKotlinSources() {
        val names = arrayOf(
            "HelpLauncher",
            "Dialog",
            "SpinnerDialog"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/utils/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/utils/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun migratedUtilsKeepJavaCompatibleApis() {
        HelpLauncher::class.java.getMethod("launchUrl", Context::class.java, String::class.java)
        HelpLauncher::class.java.getMethod("launchSetupGuide", Context::class.java)
        HelpLauncher::class.java.getMethod("launchTroubleshooting", Context::class.java)
        HelpLauncher::class.java.getMethod("launchMatrixCommunity", Context::class.java)
        HelpLauncher::class.java.getMethod("launchSponsor", Context::class.java)
        HelpLauncher::class.java.getMethod("launchGameStreamEolFaq", Context::class.java)

        // Dialog and SpinnerDialog draw on NovaSurfaces now, so neither is a Runnable or a dialog
        // listener any more; their static entry points and the spinner handle are the API.
        Dialog::class.java.getMethod(
            "displayDialog",
            Activity::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
            CharSequence::class.java,
            Runnable::class.java
        )
        Dialog::class.java.getMethod(
            "displayDialog",
            Activity::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
            CharSequence::class.java,
            Runnable::class.java,
            Boolean::class.javaPrimitiveType!!
        )
        Dialog::class.java.getMethod("closeDialogs")
        Dialog::class.java.getMethod(
            "displayDialog",
            Activity::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!
        )
        Dialog::class.java.getMethod(
            "displayDialog",
            Activity::class.java,
            String::class.java,
            String::class.java,
            Runnable::class.java
        )

        val showSpinner = SpinnerDialog::class.java.getMethod(
            "displayDialog",
            Activity::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!
        )
        assertEquals(SpinnerDialog::class.java, showSpinner.returnType)
        SpinnerDialog::class.java.getMethod("closeDialogs", Activity::class.java)
        SpinnerDialog::class.java.getMethod("dismiss")
        SpinnerDialog::class.java.getMethod("setMessage", String::class.java)
    }
}
