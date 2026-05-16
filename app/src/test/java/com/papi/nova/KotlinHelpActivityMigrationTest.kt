package com.papi.nova

import androidx.appcompat.app.AppCompatActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class KotlinHelpActivityMigrationTest {
    @Test
    fun helpActivityIsKotlinSource() {
        val javaFile = File("src/main/java/com/papi/nova/HelpActivity.java")
        val kotlinFile = File("src/main/java/com/papi/nova/HelpActivity.kt")

        assertFalse("HelpActivity should no longer be a Java source", javaFile.exists())
        assertTrue("HelpActivity should be migrated to Kotlin", kotlinFile.exists())
    }

    @Test
    fun helpActivityKeepsActivityContract() {
        assertTrue(AppCompatActivity::class.java.isAssignableFrom(HelpActivity::class.java))
        HelpActivity::class.java.getConstructor()
        assertEquals(Void.TYPE, HelpActivity::class.java.getMethod("onBackPressed").returnType)
    }

    @Test
    fun helpActivityKeepsHttpsOnlyUrlPolicy() {
        val method = HelpActivity::class.java.getDeclaredMethod("isSafeUrl", String::class.java)
        method.isAccessible = true
        val activity = HelpActivity()

        assertTrue(method.invoke(activity, "https://github.com/papi-ux/nova") as Boolean)
        assertTrue(method.invoke(activity, "HTTPS://github.com/papi-ux/nova") as Boolean)
        assertFalse(method.invoke(activity, "http://github.com/papi-ux/nova") as Boolean)
        assertFalse(method.invoke(activity, "file:///sdcard/help.html") as Boolean)
        assertFalse(method.invoke(activity, "github.com/papi-ux/nova") as Boolean)
    }
}
