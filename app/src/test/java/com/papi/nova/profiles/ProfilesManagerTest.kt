package com.papi.nova.profiles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.TestLogSuppressor
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class ProfilesManagerTest {
    private lateinit var context: Context
    private lateinit var manager: ProfilesManager
    private lateinit var profilesDir: File

    @Before
    fun setUp() {
        ProfilesManager.instance = null
        context = ApplicationProvider.getApplicationContext()
        profilesDir = File(context.filesDir, "profiles")
        deleteRecursively(profilesDir)
        manager = ProfilesManager.getInstance()
        manager.load(context)
    }

    @After
    fun tearDown() {
        deleteRecursively(profilesDir)
    }

    @Test
    fun addAndRetrieveProfile() {
        val p = SettingsProfile(UUID.randomUUID(), "Test", System.currentTimeMillis(), System.currentTimeMillis(), null)
        manager.add(p)
        assertEquals(1, manager.getProfiles().size)
        assertEquals(p.getUuid(), manager.getProfiles()[0].getUuid())
    }

    @Test
    fun setActivePersists() {
        val p = SettingsProfile(UUID.randomUUID(), "Active", System.currentTimeMillis(), System.currentTimeMillis(), null)
        manager.add(p)
        manager.setActive(p.getUuid())

        val fresh = ProfilesManager.getInstance()
        fresh.load(context)

        assertNotNull(fresh.getActive())
        assertEquals(p.getUuid(), fresh.getActive()!!.getUuid())
    }

    @Test
    fun updateAndSaveProfile() {
        val p = SettingsProfile(UUID.randomUUID(), "Original", System.currentTimeMillis(), System.currentTimeMillis(), null)
        manager.add(p)
        p.setName("Updated")
        manager.update(p)

        val fresh = ProfilesManager.getInstance()
        fresh.load(context)

        assertEquals("Updated", fresh.getProfiles()[0].getName())
    }

    @Test
    fun deleteProfile() {
        val p = SettingsProfile(UUID.randomUUID(), "ToDelete", System.currentTimeMillis(), System.currentTimeMillis(), null)
        manager.add(p)
        assertEquals(1, manager.getProfiles().size)

        manager.delete(p.getUuid())
        assertEquals(0, manager.getProfiles().size)
    }

    @Test
    fun deleteActiveProfile_resetsActive() {
        val p = SettingsProfile(UUID.randomUUID(), "ActiveToDelete", System.currentTimeMillis(), System.currentTimeMillis(), null)
        manager.add(p)
        manager.setActive(p.getUuid())
        assertNotNull(manager.getActive())

        manager.delete(p.getUuid())
        assertNull(manager.getActive())

        val fresh = ProfilesManager.getInstance()
        fresh.load(context)
        assertNull(fresh.getActive())
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
