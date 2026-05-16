package com.papi.nova

import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.profiles.SettingsProfile
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinModelsPreferencesMigrationTest {
    @Test
    fun selectedModelAndPreferenceClassesAreKotlinSources() {
        val paths = arrayOf(
            "src/main/java/com/papi/nova/SensitivityBean",
            "src/main/java/com/papi/nova/profiles/SettingsProfile",
            "src/main/java/com/papi/nova/preferences/PreferenceConfiguration",
            "src/main/java/com/papi/nova/service/NovaStreamPendingIntents"
        )

        for (path in paths) {
            val javaFile = File("$path.java")
            val kotlinFile = File("$path.kt")
            assertFalse("$path should no longer be a Java source", javaFile.exists())
            assertTrue("$path should be migrated to Kotlin", kotlinFile.exists())
        }
    }

    @Test
    fun sensitivityBeanKeepsJavaBeanAccessors() {
        val bean = SensitivityBean()

        assertEquals(-1f, bean.getLastAbsoluteX(), 0.001f)
        assertEquals(-1f, bean.getLastAbsoluteY(), 0.001f)
        assertEquals(-1f, bean.getLastRelativelyX(), 0.001f)
        assertEquals(-1f, bean.getLastRelativelyY(), 0.001f)

        bean.setLastAbsoluteX(12.5f)
        bean.setLastAbsoluteY(13.5f)
        bean.setLastRelativelyX(1.5f)
        bean.setLastRelativelyY(2.5f)

        assertEquals(12.5f, bean.getLastAbsoluteX(), 0.001f)
        assertEquals(13.5f, bean.getLastAbsoluteY(), 0.001f)
        assertEquals(1.5f, bean.getLastRelativelyX(), 0.001f)
        assertEquals(2.5f, bean.getLastRelativelyY(), 0.001f)
    }

    @Test
    fun settingsProfileKeepsMutableProfileApi() {
        val id = UUID.randomUUID()
        val options: MutableMap<String, Any> = HashMap()
        options["checkbox_ultra_low_latency"] = true
        val profile = SettingsProfile(id, "Handheld", 10L, 20L, options)

        assertEquals(id, profile.getUuid())
        assertEquals("Handheld", profile.getName())
        assertEquals(10L, profile.getCreatedUtc())
        assertEquals(20L, profile.getModifiedUtc())
        assertSame(options, profile.getOptions())
        assertFalse(profile.isActive())

        profile.setName("TV")
        profile.setModifiedUtc(30L)
        profile.setActive(true)

        assertEquals("TV", profile.getName())
        assertEquals(30L, profile.getModifiedUtc())
        assertTrue(profile.isActive())
    }

    @Test
    fun preferenceConfigurationKeepsJavaFieldAndStaticApi() {
        val config = PreferenceConfiguration()
        config.width = 1920
        config.height = 1080
        config.fps = 119.88f
        config.videoScaleMode = PreferenceConfiguration.ScaleMode.FILL
        config.videoFormat = PreferenceConfiguration.FormatOption.FORCE_HEVC
        config.analogStickForScrolling = PreferenceConfiguration.AnalogStickForScrolling.LEFT

        assertEquals(1920, config.width)
        assertEquals(1080, config.height)
        assertEquals(119.88f, config.fps, 0.001f)
        assertEquals(PreferenceConfiguration.ScaleMode.FILL, config.videoScaleMode)
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_HEVC, config.videoFormat)
        assertEquals(PreferenceConfiguration.AnalogStickForScrolling.LEFT, config.analogStickForScrolling)
        assertEquals(PreferenceConfiguration.FRAME_PACING_BALANCED, 1)
        assertEquals(
            "1920x1080x119.88",
            PreferenceConfiguration.formatStreamingDisplayMode(config.width, config.height, config.fps)
        )
        assertFalse(PreferenceConfiguration.isNativeResolution(1920, 1080))
        assertTrue(PreferenceConfiguration.isNativeResolution(2000, 1000))
        assertTrue(PreferenceConfiguration.isSquarishScreen(1200, 1000))
        assertFalse(PreferenceConfiguration.isSquarishScreen(1920, 1080))
    }
}
