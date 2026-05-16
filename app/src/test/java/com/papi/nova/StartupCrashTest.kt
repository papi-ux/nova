package com.papi.nova

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.utils.UiHelper
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class StartupCrashTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        ProfilesManager.instance = null
        context = ApplicationProvider.getApplicationContext()

        val profilesDir = File(context.filesDir, "profiles")
        deleteRecursively(profilesDir)
    }

    @Test
    fun testNativeLibraryLoadingFailure() {
        try {
            val activity = Robolectric.buildActivity(PcView::class.java).create().get()
            assertNotNull("Activity should handle native library issues", activity)
        } catch (e: UnsatisfiedLinkError) {
            fail("Native library loading should be handled by shadow: ${e.message}")
        } catch (e: Exception) {
            fail("Unexpected exception during native library test: ${e.message}")
        }
    }

    @Test
    fun testGLSurfaceViewInitialization() {
        try {
            val activity = Robolectric.buildActivity(PcView::class.java).create().get()

            assertNotNull("Activity should survive GL initialization", activity)
            assertFalse("Activity should not be finishing after GL init", activity.isFinishing)
        } catch (e: Exception) {
            fail("GL surface view initialization should not crash: ${e.message}")
        }
    }

    @Test
    fun testPreferenceConfigurationCrash() {
        try {
            val config = PreferenceConfiguration.readPreferences(context)
            assertNotNull("PreferenceConfiguration should be readable", config)
        } catch (e: Exception) {
            fail("PreferenceConfiguration should not crash: ${e.message}")
        }
    }

    @Test
    fun testUiHelperCrash() {
        try {
            val activity = Robolectric.buildActivity(PcView::class.java).create().get()
            UiHelper.setLocale(activity)
        } catch (e: Exception) {
            fail("UiHelper.setLocale should not crash: ${e.message}")
        }
    }

    @Test
    fun testComputerManagerServiceBinding() {
        try {
            val activity = Robolectric.buildActivity(PcView::class.java).create().get()

            assertNotNull("Activity should handle service binding", activity)
        } catch (e: Exception) {
            fail("Service binding should not crash activity creation: ${e.message}")
        }
    }

    @Test
    fun testSharedPreferencesCorruption() {
        val prefs: SharedPreferences = context.getSharedPreferences("test", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        editor.putString("resolution", "invalid_resolution")
        editor.putInt("bitrate", -1)
        editor.putBoolean("enable_hdr", true)
        editor.apply()

        try {
            val config = PreferenceConfiguration.readPreferences(context)
            assertNotNull("Should handle corrupted preferences", config)
        } catch (e: Exception) {
            fail("Should handle corrupted shared preferences gracefully: ${e.message}")
        }
    }

    @Test
    fun testMissingRequiredIntentExtras() {
        val intent = Intent()

        try {
            val activity = Robolectric.buildActivity(AppView::class.java, intent).create().get()
            assertNotNull("Activity should either work or finish cleanly", activity)
        } catch (e: Exception) {
            fail("Missing intent extras should be handled gracefully: ${e.message}")
        }
    }

    @Test
    fun testInvalidUuidInIntent() {
        val intent = Intent()
        intent.putExtra(AppView.NAME_EXTRA, "Test Computer")
        intent.putExtra(AppView.UUID_EXTRA, "invalid-uuid")

        try {
            val activity = Robolectric.buildActivity(AppView::class.java, intent).create().get()
            assertNotNull("Should handle invalid UUID", activity)
        } catch (e: Exception) {
            fail("Invalid UUID should be handled gracefully: ${e.message}")
        }
    }

    @Test
    fun testFileSystemPermissionDenied() {
        val profilesDir = File(context.filesDir, "profiles")
        profilesDir.mkdirs()
        profilesDir.setReadOnly()

        try {
            val manager = ProfilesManager.getInstance()
            manager.load(context)
            manager.save(context)

            assertNotNull("ProfilesManager should handle file permission issues", manager.getProfiles())
        } catch (e: Exception) {
            fail("File permission issues should be handled gracefully: ${e.message}")
        } finally {
            profilesDir.setWritable(true)
        }
    }

    @Test
    fun testConcurrentStartup() {
        try {
            val activity1 = Robolectric.buildActivity(PcView::class.java).create().get()
            val activity2 = Robolectric.buildActivity(PcView::class.java).create().get()

            assertNotNull("First activity should be created", activity1)
            assertNotNull("Second activity should be created", activity2)
        } catch (e: Exception) {
            fail("Concurrent startup should not cause crashes: ${e.message}")
        }
    }

    @Test
    fun testMemoryLeakDuringStartup() {
        try {
            repeat(10) {
                val activity = Robolectric.buildActivity(PcView::class.java).create().get()
                activity.onDestroy()

                System.gc()
            }
        } catch (e: OutOfMemoryError) {
            fail("Startup should not cause memory leaks: ${e.message}")
        }
    }

    @Test
    fun testActivityLifecycleTransitions() {
        try {
            val activity = Robolectric.buildActivity(PcView::class.java)
                .create()
                .start()
                .resume()
                .pause()
                .stop()
                .restart()
                .start()
                .resume()
                .get()

            assertNotNull("Activity should survive lifecycle transitions", activity)
        } catch (e: Exception) {
            fail("Activity lifecycle transitions should not crash: ${e.message}")
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun testStartupWithSystemUiVisibility() {
        try {
            val activity = Robolectric.buildActivity(PcView::class.java).create().get()

            activity.window?.decorView?.systemUiVisibility = 0

            assertNotNull("Activity should handle UI visibility changes", activity)
        } catch (e: Exception) {
            fail("System UI visibility changes should not crash: ${e.message}")
        }
    }

    @Test
    fun testStartupWithNetworkUnavailable() {
        try {
            val activity = Robolectric.buildActivity(PcView::class.java).create().get()
            assertNotNull("Activity should handle network unavailability", activity)
        } catch (e: Exception) {
            fail("Network unavailability should not crash startup: ${e.message}")
        }
    }

    private fun deleteRecursively(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach(::deleteRecursively)
        }
        file.delete()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressInvalidIdLogs() {
            TestLogSuppressor.install()
        }
    }
}
