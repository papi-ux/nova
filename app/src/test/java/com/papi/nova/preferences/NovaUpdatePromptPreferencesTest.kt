package com.papi.nova.preferences

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaUpdatePromptPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun automaticCheckRunsAtMostOncePerInterval() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        assertTrue(NovaUpdatePromptPreferences.shouldRunAutomaticCheck(prefs, nowMs = 10_000L))

        NovaUpdatePromptPreferences.recordAutomaticCheck(prefs, nowMs = 10_000L)

        assertFalse(NovaUpdatePromptPreferences.shouldRunAutomaticCheck(prefs, nowMs = 10_000L + 60_000L))
        assertTrue(
            NovaUpdatePromptPreferences.shouldRunAutomaticCheck(
                prefs,
                nowMs = 10_000L + NovaUpdatePromptPreferences.AUTO_CHECK_INTERVAL_MS + 1L
            )
        )
    }

    @Test
    fun skippedReleaseSuppressesOnlyThatAutomaticPopup() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val skippedRelease = NovaUpdateRelease(
            tagName = "v1.3.0",
            versionName = "1.3.0",
            releaseUrl = "https://github.com/papi-ux/nova/releases/tag/v1.3.0",
            apkAssetName = "Nova-Android-arm64-v8a.apk",
            apkDownloadUrl = "https://github.com/papi-ux/nova/releases/download/v1.3.0/Nova-Android-arm64-v8a.apk"
        )
        val newerRelease = skippedRelease.copy(tagName = "v1.4.0", versionName = "1.4.0")

        NovaUpdatePromptPreferences.skipRelease(prefs, skippedRelease)

        assertFalse(NovaUpdatePromptPreferences.shouldShowAutomaticPrompt(prefs, skippedRelease))
        assertTrue(NovaUpdatePromptPreferences.shouldShowAutomaticPrompt(prefs, newerRelease))
    }
}
