package com.papi.nova.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinFoundationUtilsMigrationTest {
    @Test
    fun selectedFoundationUtilitiesAreKotlinSources() {
        val names = arrayOf(
            "CacheHelper",
            "PerformanceDataTracker",
            "DeviceUtils",
            "FileUriUtils",
            "NetHelper",
            "TrafficStatsHelper",
            "Vector2d",
            "MouseModeOption",
            "KeyConfigHelper"
        )

        for (name in names) {
            val javaFile = File("src/main/java/com/papi/nova/utils/$name.java")
            val kotlinFile = File("src/main/java/com/papi/nova/utils/$name.kt")
            assertFalse("$name should no longer be a Java source", javaFile.exists())
            assertTrue("$name should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun vector2dKeepsMutableJavaApi() {
        val vector = Vector2d()

        vector.initialize(3f, 4f)
        assertEquals(3f, vector.getX(), 0.001f)
        assertEquals(4f, vector.getY(), 0.001f)
        assertEquals(5.0, vector.getMagnitude(), 0.001)

        val normalized = Vector2d()
        vector.getNormalized(normalized)
        assertEquals(1.0, normalized.getMagnitude(), 0.001)

        vector.scalarMultiply(2.0)
        assertEquals(6f, vector.getX(), 0.001f)
        assertEquals(8f, vector.getY(), 0.001f)
        assertNotNull(Vector2d.ZERO)
    }

    @Test
    fun keyConfigHelperKeepsGsonFriendlyPublicFields() {
        val file = KeyConfigHelper.parseShortcutFile(
            "{\"data\":[{\"id\":\"paste\",\"name\":\"Paste\",\"sticky\":true,\"keys\":[\"CTRL\",\"V\"]}]}"
        )

        assertEquals(1, file.data.size)
        val shortcut = file.data[0]
        assertEquals("paste", shortcut.id)
        assertEquals("Paste", shortcut.name)
        assertTrue(shortcut.sticky)
        assertEquals(listOf("CTRL", "V"), shortcut.keys)
    }

    @Test
    fun mouseModeOptionKeepsPublicFieldsAndLabelString() {
        val option = MouseModeOption(2, "Trackpad")

        assertEquals(2, option.index)
        assertEquals("Trackpad", option.label)
        assertEquals("Trackpad", option.toString())
    }
}
