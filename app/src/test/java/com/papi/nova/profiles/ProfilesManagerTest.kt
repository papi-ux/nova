package com.papi.nova.profiles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.TestLogSuppressor
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun aPartialOrCloseFailurePreservesTheOldFileAndActiveSelection() {
        val first = SettingsProfile(UUID.randomUUID(), "First", 1L, 1L, mapOf("frame_pacing" to "latency"))
        val second = SettingsProfile(UUID.randomUUID(), "Second", 1L, 1L, null)
        manager.add(first)
        manager.add(second)
        manager.setActive(first.getUuid())
        val file = File(profilesDir, "profiles.json")
        val before = file.readBytes()
        for (closeFailure in listOf(false, true)) {
            manager.openProfileWriter = { path -> object : FileOutputStream(path) {
                override fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)
                override fun write(bytes: ByteArray, off: Int, len: Int) {
                    if (closeFailure) super.write(bytes, off, len) else {
                        super.write(bytes, off, minOf(len, 7))
                        throw IOException("injected partial write")
                    }
                }
                override fun close() {
                    super.close()
                    if (closeFailure) throw IOException("injected close failure")
                }
            } }
            val edited = SettingsProfile(first.getUuid(), "Edited", 1L, 2L, mapOf("frame_pacing" to "balanced"))
            assertFalse(manager.commit(context, edited))
            assertArrayEquals("failed replacement keeps the prior bytes", before, file.readBytes())
            assertEquals("First", manager.getActive()!!.getName())
            ProfilesManager.instance = null
            val cold = ProfilesManager.getInstance()
            assertTrue(cold.load(context))
            assertEquals(setOf("First", "Second"), cold.getProfiles().map { it.getName() }.toSet())
            assertEquals(first.getUuid(), cold.getActive()!!.getUuid())
        }
        manager.openProfileWriter = { FileOutputStream(it) }
        assertTrue(manager.commit(context, SettingsProfile(first.getUuid(), "Edited", 1L, 2L, null)))
        ProfilesManager.instance = null
        val cold = ProfilesManager.getInstance()
        assertTrue(cold.load(context))
        assertEquals("Edited", cold.getActive()!!.getName())
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

    // A preset save that failed still changed the list: the editor put the edit into the manager,
    // then saved, and the preset list showed what the file did not have.
    @Test
    fun commitKeepsAPresetOnlyOnceTheFileHasSaved() {
        val kept = SettingsProfile(UUID.randomUUID(), "Kept", 1L, 1L, mapOf("frame_pacing" to "latency"))
        assertTrue(manager.commit(context, kept))
        assertEquals("Kept", ProfilesManager.getInstance().also { it.load(context) }.getProfiles().single().getName())

        // A file where the directory should be: nothing can be written.
        deleteRecursively(profilesDir)
        profilesDir.writeText("blocked")
        val renamed = SettingsProfile(kept.getUuid(), "Renamed", 1L, 2L, mapOf("frame_pacing" to "balanced"))
        val added = SettingsProfile(UUID.randomUUID(), "Added", 3L, 3L, null)

        assertFalse(manager.commit(context, renamed))
        assertFalse(manager.commit(context, added))
        val profiles = manager.getProfiles()
        assertEquals("the new preset is not in the list", 1, profiles.size)
        assertEquals("the preset keeps its old name", "Kept", profiles.single().getName())
        assertEquals("and its old values", "latency", profiles.single().getOptions()!!["frame_pacing"])
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
