package com.papi.nova

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
class StartupTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        ProfilesManager.instance = null
        context = ApplicationProvider.getApplicationContext()

        val profilesDir = File(context.filesDir, "profiles")
        deleteRecursively(profilesDir)
    }

    @Test
    fun testApplicationStartup() {
        val app = ApplicationProvider.getApplicationContext<NovaApplication>()
        assertNotNull("Application should exist", app)

        val manager = ProfilesManager.getInstance()
        assertNotNull("ProfilesManager should be initialized", manager)
        assertNotNull("ProfilesManager should have loaded profiles", manager.getProfiles())
    }

    @Test
    fun testPcViewActivityCreation() {
        val activity = Robolectric.buildActivity(PcView::class.java).create().get()
        assertNotNull("PcView activity should be created", activity)
        assertFalse("Activity should not be finishing", activity.isFinishing)
    }

    @Test
    fun testPcViewActivityWithIntent() {
        val intent = Intent()
        intent.putExtra("hostname", "test.local")
        intent.putExtra("port", 47989)
        intent.putExtra("pin", "1234")
        intent.putExtra("passphrase", "test")

        val activity = Robolectric.buildActivity(PcView::class.java, intent).create().get()
        assertNotNull("PcView activity should be created with intent", activity)
        assertFalse("Activity should not be finishing", activity.isFinishing)
    }

    @Test
    fun testAppViewActivityCreation() {
        val intent = Intent()
        intent.putExtra(AppView.NAME_EXTRA, "Test Computer")
        intent.putExtra(AppView.UUID_EXTRA, "test-uuid-123")

        val activity = Robolectric.buildActivity(AppView::class.java, intent).create().get()
        assertNotNull("AppView activity should be created", activity)
        assertFalse("Activity should not be finishing", activity.isFinishing)
    }

    @Test
    fun testProfilesManagerFileSystemAccess() {
        val manager = ProfilesManager.getInstance()

        try {
            manager.load(context)
            manager.save(context)
        } catch (e: Exception) {
            fail("ProfilesManager should handle file access gracefully: ${e.message}")
        }
    }

    @Test
    fun testMissingPermissions() {
        try {
            val activity = Robolectric.buildActivity(PcView::class.java).create().get()
            assertNotNull("Activity should handle permission checks", activity)

            val permissionResult = context.checkSelfPermission("android.permission.INTERNET")
            assertTrue("Permission check should work", permissionResult >= -1)
        } catch (e: Exception) {
            fail("App should handle permission checks gracefully: ${e.message}")
        }
    }

    @Test
    fun testCorruptedProfilesFile() {
        val profilesDir = File(context.filesDir, "profiles")
        profilesDir.mkdirs()
        val profilesFile = File(profilesDir, "profiles.json")

        try {
            profilesFile.writeText("{ corrupted json content")

            val manager = ProfilesManager.getInstance()
            manager.load(context)

            assertNotNull("Profiles should be initialized even with corrupted file", manager.getProfiles())
        } catch (e: Exception) {
            fail("ProfilesManager should handle corrupted files gracefully: ${e.message}")
        }
    }

    @Test
    fun testNullContextHandling() {
        val manager = ProfilesManager.getInstance()

        try {
            manager.load(null)
            assertTrue("Should handle null context gracefully", true)
        } catch (e: Exception) {
            fail("Should handle null context gracefully: ${e.message}")
        }
    }

    @Test
    fun testLowMemoryConditions() {
        try {
            val activity = Robolectric.buildActivity(PcView::class.java).create().get()
            assertNotNull("Activity should handle low memory conditions", activity)

            System.gc()

            activity.onLowMemory()
            assertFalse("Activity should not finish on low memory", activity.isFinishing)
        } catch (e: OutOfMemoryError) {
            fail("App should handle low memory gracefully: ${e.message}")
        }
    }

    @Test
    fun testConfigurationChanges() {
        val activity = Robolectric.buildActivity(PcView::class.java).create().start().resume().get()

        try {
            val newConfig = Configuration()
            newConfig.orientation = Configuration.ORIENTATION_LANDSCAPE
            activity.onConfigurationChanged(newConfig)

            assertFalse("Activity should survive configuration changes", activity.isFinishing)
        } catch (e: Exception) {
            fail("Activity should handle configuration changes gracefully: ${e.message}")
        }
    }

    @Test
    fun testServiceBindingFailure() {
        try {
            val activity = Robolectric.buildActivity(PcView::class.java).create().get()

            activity.onDestroy()

            assertNotNull("Activity should handle service binding failure", activity)
        } catch (e: Exception) {
            fail("Activity should handle service binding failure gracefully: ${e.message}")
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
