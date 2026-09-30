package com.papi.nova.ui

import com.papi.nova.api.PolarisClientSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the System header's own label resolver, including a host's custom mode name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaLibrarySystemModeTest {
    private fun label(settings: PolarisClientSettings): String? {
        val activity = Robolectric.buildActivity(NovaLibraryActivity::class.java).get()
        return NovaLibraryActivity::class.java.getDeclaredMethod(
            "compactStatusModeLabel", PolarisClientSettings::class.java
        ).apply { isAccessible = true }.invoke(activity, settings) as String?
    }

    @Test fun systemUsesTheHostsNamesForDesiredAndEffectiveModes() {
        assertEquals("My private screen → Mirror Desktop", label(PolarisClientSettings(
            desired = PolarisClientSettings.Desired(
                streamDisplayMode = "windowed_stream", streamDisplayModeLabel = "My private screen"
            ),
            effective = PolarisClientSettings.Effective(
                streamDisplayMode = "desktop_display", streamDisplayModeLabel = "Mirror Desktop"
            )
        )))
    }

    @Test fun anOlderHostFallsBackToTheSharedModeName() {
        assertEquals("Private Stream", label(PolarisClientSettings(
            desired = PolarisClientSettings.Desired(streamDisplayMode = "headless_stream"),
            effective = PolarisClientSettings.Effective(streamDisplayMode = "headless_stream")
        )))
    }
}
