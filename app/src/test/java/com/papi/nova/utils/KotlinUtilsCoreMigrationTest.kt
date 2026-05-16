package com.papi.nova.utils

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import java.io.File
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
        HelpLauncher::class.java.getMethod("launchGameStreamEolFaq", Context::class.java)

        assertTrue(Runnable::class.java.isAssignableFrom(Dialog::class.java))
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

        assertTrue(Runnable::class.java.isAssignableFrom(SpinnerDialog::class.java))
        assertTrue(DialogInterface.OnCancelListener::class.java.isAssignableFrom(SpinnerDialog::class.java))
        SpinnerDialog::class.java.getMethod(
            "displayDialog",
            Activity::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!
        )
        SpinnerDialog::class.java.getMethod("closeDialogs", Activity::class.java)
        SpinnerDialog::class.java.getMethod("dismiss")
        SpinnerDialog::class.java.getMethod("setMessage", String::class.java)
    }
}
