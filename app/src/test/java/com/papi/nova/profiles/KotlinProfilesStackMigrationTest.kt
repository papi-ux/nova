package com.papi.nova.profiles

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinProfilesStackMigrationTest {
    @Test
    fun selectedProfilesStackClassesAreKotlinSources() {
        val paths = arrayOf(
            "src/main/java/com/papi/nova/profiles/ProfilesManager",
            "src/main/java/com/papi/nova/profiles/ProfilesAdapter",
            "src/main/java/com/papi/nova/ProfilesActivity",
            "src/main/java/com/papi/nova/EditProfileActivity"
        )

        for (path in paths) {
            val javaFile = File("$path.java")
            val kotlinFile = File("$path.kt")
            assertFalse("$path should no longer be a Java source", javaFile.exists())
            assertTrue("$path should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun profilesManagerKeepsJavaVisibleSingletonResetAndListenerApi() {
        ProfilesManager.instance = null
        val manager = ProfilesManager.getInstance()
        val calls = intArrayOf(0)

        val listener = ProfilesManager.ProfileChangeListener { calls[0]++ }
        manager.addListener(listener)

        val profile = SettingsProfile(
            UUID.randomUUID(),
            "Living Room",
            10L,
            20L,
            null
        )
        manager.add(profile)
        manager.setActive(profile.getUuid())

        assertEquals(2, calls[0])
        assertEquals(profile.getUuid(), manager.getActive()?.getUuid())
        assertEquals("Living Room", manager.getActiveName())

        manager.removeListener(listener)
        manager.setActive(null)

        assertEquals(2, calls[0])
        assertNull(manager.getActive())
        assertEquals("", manager.getActiveName())
    }
}
