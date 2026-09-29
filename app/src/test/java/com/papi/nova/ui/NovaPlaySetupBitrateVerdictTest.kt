package com.papi.nova.ui

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.binding.video.PyroWaveDecoderRenderer
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.shadows.ShadowMoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * One plan, 3840x2160 at 120 FPS on PyroWave with a 300 Mbps bitrate, got two verdicts: the codec
 * preview warned "Limited by bitrate" while What Will Happen, with PyroWave saved, said nothing
 * (in-game smoke #10). The one verdict that followed measured PyroWave's growth past the saved
 * stream resolution rather than the screen, so the smoke's own settings, 4K saved on a 1080p
 * handheld, were never held back anywhere. Here the verdict runs on the inputs Play Setup gives it:
 * the settings the smoke saved, the RP6's own 1920x1080 screen as its display metrics give it, and
 * the codec's own rate model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1920dp-h1080dp-land-mdpi", shadows = [ShadowMoonBridge::class])
class NovaPlaySetupBitrateVerdictTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var preset: java.util.UUID? = null

    // The saved settings alone: a preset another test left active would lay its own bitrate over them.
    @Before
    fun noPreset() {
        preset = ProfilesManager.getInstance().getActive()?.getUuid()
        ProfilesManager.getInstance().setActive(null)
    }

    @After
    fun putThePresetBack() {
        ProfilesManager.getInstance().setActive(preset)
    }

    /** The smoke's saved settings: 3840x2160 at 120 FPS, PyroWave, 300 Mbps. */
    private fun smokeSettings(bitrateKbps: Int = 300_000, resolution: String = "3840x2160"): PreferenceConfiguration {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, resolution)
            .putString(PreferenceConfiguration.FPS_PREF_STRING, "120")
            .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, bitrateKbps)
            .putString("video_format", "forcepyrowave")
            .commit()
        return PreferenceConfiguration.readPreferences(context)
    }

    private fun verdict(preferences: PreferenceConfiguration): Int = novaPlaySetupBitrateShortfallMbps(
        format = preferences.videoFormat,
        size = preferences.width to preferences.height,
        preferences = preferences,
        screen = novaDeviceScreenPixels(context),
        fps = 120,
        television = false,
    )

    private fun need(width: Int, height: Int): Int = PyroWaveDecoderRenderer.advisedMbps(
        width, height, 120,
        chroma444 = true,
        heightFactor = PyroWaveDecoderRenderer.viewingHeightFactor(television = false, onExternalDisplay = false),
    )

    @Test
    fun fourKAt120On444PyroWaveOnAHandheldsOwn1080pScreenIsHeldBackByTheBitrate() {
        val preferences = smokeSettings()
        assertEquals("the saved stream is the smoke's 4K", 3840 to 2160, preferences.width to preferences.height)
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_PYROWAVE, preferences.videoFormat)
        assertEquals("the RP6's own screen, from its display metrics", 1920 to 1080, novaDeviceScreenPixels(context))

        val needed = need(3840, 2160)
        assertTrue("PyroWave's rate model asks more than 300 Mbps for 4K at 120 FPS: $needed", needed > 300)
        assertEquals("the plan is held back by the bitrate", needed, verdict(preferences))
    }

    @Test
    fun theScreensOwnSizeAndABitrateThatCoversItAreNotHeldBack() {
        assertEquals("1080p on a 1080p screen", 0, verdict(smokeSettings(bitrateKbps = 20_000, resolution = "1920x1080")))
        assertEquals("the bitrate covers it", 0, verdict(smokeSettings(bitrateKbps = (need(3840, 2160) + 1) * 1000)))
        val preferences = smokeSettings()
        assertEquals(
            "not PyroWave",
            0,
            novaPlaySetupBitrateShortfallMbps(
                format = PreferenceConfiguration.FormatOption.FORCE_HEVC,
                size = 3840 to 2160,
                preferences = preferences,
                screen = novaDeviceScreenPixels(context),
                fps = 120,
                television = false,
            ),
        )
    }

    @Test
    fun thePreviewTheResolutionPageAndThePlanReadTheOneVerdict() {
        val activity = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText()
        val content = File("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt").readText()
        assertTrue("the codec preview", activity.contains("limit = if (bitrateShortfallMbps(format, askedSize, preferences) > 0) limitedBy else \"\""))
        assertTrue("the Resolution page", activity.contains("val need = bitrateShortfallMbps(codec, size, preferences)"))
        assertTrue("the plan", activity.contains("playSetupBitrateShortfallMbps = planShortfallMbps()"))
        assertTrue(
            "each measured against this device's own screen",
            activity.contains("val screenSize: Pair<Int, Int> by lazy { novaDeviceScreenPixels(this@NovaGameDetailActivity) }") &&
                activity.contains("): Int = novaPlaySetupBitrateShortfallMbps(") &&
                activity.contains("            screen = screenSize,\n"),
        )
        assertTrue("What Will Happen's card", content.contains("limit = planLimit,"))
        assertTrue("the status line", content.contains("planLimit = bitrateLimit,"))
    }
}
