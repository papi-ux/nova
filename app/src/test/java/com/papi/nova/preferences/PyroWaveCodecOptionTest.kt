package com.papi.nova.preferences

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.BuildConfig
import com.papi.nova.shadows.ShadowMoonBridge
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The PyroWave codec option, which is only a real option where the picker offers it.
 *
 * The entry lives in its own resource set, which a debug build and a beta get and a release does
 * not, so a release has no way to select it. This is the other half of that switch: the value being
 * honoured only where it can be chosen, so a release build that inherited it, from a profile, a
 * backup, or a beta installed beside it, streams as it always did instead of asking a host for a
 * codec it cannot decode.
 */
@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
@RunWith(RobolectricTestRunner::class)
class PyroWaveCodecOptionTest {

    private fun formatFor(value: String): PreferenceConfiguration.FormatOption {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            // The literal, because the constant is private, and pinning it here is no loss: this is
            // the same key preferences.xml names, and the two disagreeing would be a picker that
            // writes somewhere nothing reads.
            .putString("video_format", value)
            .commit()
        return PreferenceConfiguration.readPreferences(context).videoFormat!!
    }

    @Test
    fun theCodecIsSelectableOnlyWhereThePickerOffersIt() {
        val expected = if (BuildConfig.EXPERIMENTAL_CODECS) {
            PreferenceConfiguration.FormatOption.FORCE_PYROWAVE
        } else {
            PreferenceConfiguration.FormatOption.AUTO
        }
        assertEquals(
            "a build without the picker entry must not act on the value behind it",
            expected,
            formatFor("forcepyrowave"),
        )
    }

    @Test
    fun theOtherCodecsAreUnaffected() {
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_AV1, formatFor("forceav1"))
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_HEVC, formatFor("forceh265"))
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_H264, formatFor("neverh265"))
        assertEquals(PreferenceConfiguration.FormatOption.AUTO, formatFor("auto"))
    }

    @Test
    fun anUnknownValueIsStillAuto() {
        assertEquals(PreferenceConfiguration.FormatOption.AUTO, formatFor("forcesomethingelse"))
    }
}
