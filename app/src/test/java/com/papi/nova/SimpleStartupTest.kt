package com.papi.nova

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class SimpleStartupTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        ProfilesManager.instance = null
        context = ApplicationProvider.getApplicationContext()

        val profilesDir = File(context.filesDir, "profiles")
        deleteRecursively(profilesDir)
    }

    @Test
    fun testApplicationCreation() {
        val app = NovaApplication()
        assertNotNull("Application should be created", app)
    }

    @Test
    fun testApplicationOnCreate() {
        val app = ApplicationProvider.getApplicationContext<NovaApplication>()
        assertNotNull("Application should exist", app)

        val manager = ProfilesManager.getInstance()
        assertNotNull("ProfilesManager should be initialized", manager)
        assertNotNull("Profiles list should be initialized", manager.getProfiles())
    }

    @Test
    fun testProfilesManagerSingleton() {
        val manager1 = ProfilesManager.getInstance()
        val manager2 = ProfilesManager.getInstance()

        assertNotNull("First instance should not be null", manager1)
        assertNotNull("Second instance should not be null", manager2)
        assertSame("Should return same instance", manager1, manager2)
    }

    @Test
    fun testProfilesManagerLoad() {
        try {
            val manager = ProfilesManager.getInstance()
            manager.load(context)

            assertNotNull("Profiles should be loaded", manager.getProfiles())
            assertEquals("Should start with empty profiles", 0, manager.getProfiles().size)
        } catch (e: Exception) {
            fail("ProfilesManager load should not crash: ${e.message}")
        }
    }

    @Test
    fun testProfilesManagerSave() {
        try {
            val manager = ProfilesManager.getInstance()
            manager.load(context)
            manager.save(context)

            assertTrue("Save operation should complete", true)
        } catch (e: Exception) {
            fail("ProfilesManager save should not crash: ${e.message}")
        }
    }

    @Test
    fun testContextFileAccess() {
        try {
            val filesDir = context.filesDir
            assertNotNull("Files directory should be accessible", filesDir)

            val testDir = File(filesDir, "test")
            val created = testDir.mkdirs()
            assertTrue("Should be able to create directories", created || testDir.exists())

            val deleted = testDir.delete()
            assertTrue("Should be able to delete directories", deleted)
        } catch (e: Exception) {
            fail("Basic file operations should work: ${e.message}")
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
