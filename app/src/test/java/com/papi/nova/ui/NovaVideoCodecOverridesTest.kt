package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import com.papi.nova.BuildConfig
import com.papi.nova.Game
import com.papi.nova.manager.StreamSyncManager
import com.papi.nova.manager.WorkerLaunchContract
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.preferences.PreferenceConfiguration.FormatOption
import com.papi.nova.shadows.ShadowMoonBridge
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
@RunWith(RobolectricTestRunner::class)
class NovaVideoCodecOverridesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)

    @Before @After fun clear() { preferences.edit().clear().commit() }

    @Test fun savedChoiceIsScopedToHostAndGameWithAnIndependentNumericFallback() {
        NovaVideoCodecOverrides.save(context, "host-a", "game", 12, "forceh265")
        assertEquals("forceh265", NovaVideoCodecOverrides.load(context, "host-a", "game", 12))
        assertNull(NovaVideoCodecOverrides.load(context, "host-b", "game", 12))
        assertNull(NovaVideoCodecOverrides.load(context, "host-a", "other", 12))
        assertNull(NovaVideoCodecOverrides.load(context, "host-a", null, 12))
        NovaVideoCodecOverrides.save(context, "host-a", null, 12, "neverh265")
        assertEquals("neverh265", NovaVideoCodecOverrides.load(context, "host-a", null, 12))
        assertNull(NovaVideoCodecOverrides.load(context, "host-a", "app:12", 12))
    }

    @Test fun autoOverridesTheAppDefaultWhileClearingTheChoiceInheritsIt() {
        NovaVideoCodecOverrides.save(context, "host", "game", 1, "auto")
        assertEquals(FormatOption.AUTO, NovaVideoCodecOverrides.resolve(
            NovaVideoCodecOverrides.load(context, "host", "game", 1), FormatOption.FORCE_HEVC))
        NovaVideoCodecOverrides.save(context, "host", "game", 1, null)
        assertEquals(FormatOption.FORCE_HEVC, NovaVideoCodecOverrides.resolve(
            NovaVideoCodecOverrides.load(context, "host", "game", 1), FormatOption.FORCE_HEVC))
    }

    @Test fun experimentalAndMalformedValuesCannotBecomeInvisibleChoices() {
        assertNull(NovaVideoCodecOverrides.normalize("forcepyrowave", experimental = false))
        assertEquals("forcepyrowave", NovaVideoCodecOverrides.normalize("forcepyrowave", experimental = true))
        assertNull(NovaVideoCodecOverrides.normalize("unknown", experimental = true))
        preferences.edit().putString("video_codec_override_4:host:uuid:game", "unknown").commit()
        assertNull(NovaVideoCodecOverrides.load(context, "host", "game", 1))
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun launchBoundaryAppliesOnlyThisGamesChoiceWithoutChangingAppDefaults() {
        NovaVideoCodecOverrides.save(context, "host", "game", 1, "forceh265")
        val intent = Intent().putExtra(Game.EXTRA_PC_UUID, "host")
            .putExtra(Game.EXTRA_APP_UUID, "game").putExtra(Game.EXTRA_APP_ID, 1)
        val appDefaults = PreferenceConfiguration().apply { videoFormat = FormatOption.FORCE_AV1 }
        val launch = PreferenceConfiguration().apply { videoFormat = appDefaults.videoFormat }
        NovaVideoCodecOverrides.applyToLaunch(context, intent, launch)
        assertEquals(FormatOption.FORCE_HEVC, launch.videoFormat)
        assertEquals(FormatOption.FORCE_AV1, appDefaults.videoFormat)
        val otherLaunch = PreferenceConfiguration().apply { videoFormat = appDefaults.videoFormat }
        NovaVideoCodecOverrides.applyToLaunch(context, Intent(intent).putExtra(Game.EXTRA_PC_UUID, "other"), otherLaunch)
        assertEquals(FormatOption.FORCE_AV1, otherLaunch.videoFormat)
    }

    @Test fun restoredExperimentalChoiceIsAppliedOnlyByAnExperimentalBuild() {
        preferences.edit().putString("video_codec_override_4:host:uuid:game", "forcepyrowave").commit()
        val launch = PreferenceConfiguration().apply { videoFormat = FormatOption.FORCE_HEVC }
        NovaVideoCodecOverrides.applyToLaunch(context, Intent().putExtra(Game.EXTRA_PC_UUID, "host")
            .putExtra(Game.EXTRA_APP_UUID, "game").putExtra(Game.EXTRA_APP_ID, 1), launch)
        assertEquals(if (BuildConfig.EXPERIMENTAL_CODECS) FormatOption.FORCE_PYROWAVE else FormatOption.FORCE_HEVC,
            launch.videoFormat)
        assertEquals(BuildConfig.EXPERIMENTAL_CODECS, preferences.all.isNotEmpty())
    }

    @Test fun missingIdentityAndWorkerContractsDoNotAcquireClientOverrides() {
        NovaVideoCodecOverrides.save(context, null, "game", 1, "forceh265")
        NovaVideoCodecOverrides.save(context, "host", WorkerLaunchContract.APP_UUID, WorkerLaunchContract.APP_ID, "forceh265")
        assertTrue(preferences.all.isEmpty())
        val launch = PreferenceConfiguration().apply { videoFormat = FormatOption.FORCE_H264 }
        NovaVideoCodecOverrides.applyToLaunch(context, Intent().putExtra(Game.EXTRA_PC_UUID, "host")
            .putExtra(Game.EXTRA_APP_UUID, WorkerLaunchContract.APP_UUID), launch)
        assertEquals(FormatOption.FORCE_H264, launch.videoFormat)
    }

    @Test fun rowUsesTheBuildsCodecCatalogAndCanClearWithoutTouchingGlobalSettings() {
        var selected: String? = "forceh265"
        val row = novaPlaySetupCodecRow(context, selected, FormatOption.FORCE_H264) { selected = it }
        assertEquals(NovaPlaySetupRow.VIDEO_CODEC, row.row)
        assertEquals(1, row.options.count { it.current })
        assertEquals(BuildConfig.EXPERIMENTAL_CODECS, row.options.any { it.label.contains("PyroWave") })
        row.options.first().onSelect!!.invoke()
        assertNull(selected)
        val inherited = novaPlaySetupCodecRow(context, null, FormatOption.FORCE_H264) {}
        assertTrue(inherited.options.first().current)
        assertTrue(inherited.value.contains("H.264"))
    }

    @Test fun unavailablePyrowaveRemainsVisibleWithAReasonAndStandardChoicesStillWork() {
        var selected: String? = "forcepyrowave"
        val row = novaPlaySetupCodecRow(context, selected, FormatOption.FORCE_PYROWAVE) { selected = it }
        if (BuildConfig.EXPERIMENTAL_CODECS) {
            val pyro = row.options.first { it.label.contains("PyroWave") }
            assertTrue(pyro.current)
            assertFalse(pyro.enabled)
            assertNull(pyro.onSelect)
            assertTrue(pyro.consequence.contains("unavailable"))
            assertFalse(row.options.first().enabled)
            assertNull(row.options.first().onSelect)
            assertTrue(row.caption.contains("unavailable"))
            assertEquals("forcepyrowave", selected)
        }
        val automatic = row.options.first { it.label == context.getString(com.papi.nova.R.string.videoformat_auto) }
        assertTrue(automatic.enabled)
        automatic.onSelect!!.invoke()
        assertEquals("auto", selected)
    }

    @Test fun pyrowaveSuppressesButDoesNotEraseTheOtherCodecsEncoderChoice() {
        val saved = "nvenc"
        assertEquals("", NovaVideoCodecOverrides.encoderBackend(FormatOption.FORCE_PYROWAVE, saved))
        assertEquals(saved, NovaVideoCodecOverrides.encoderBackend(FormatOption.FORCE_H264, saved))
        assertEquals(saved, NovaVideoCodecOverrides.encoderBackend(FormatOption.AUTO, saved))
    }

    @Test fun appliedSettingsReportPyrowaveInsteadOfH264() {
        val applied = StreamSyncManager.buildAppliedStreamSettings(
            20000, 1280, 720, 60f, 60f, false, false,
            MoonBridge.VIDEO_FORMAT_PYROWAVE, FormatOption.FORCE_PYROWAVE, false)
        assertEquals("pyrowave", applied.getString("preferred_codec"))
    }
}
