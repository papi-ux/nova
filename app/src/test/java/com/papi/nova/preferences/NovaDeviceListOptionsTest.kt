package com.papi.nova.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovaDeviceListOptionsTest {
    private val labels = NovaDeviceListOptions.Labels(
        native = "Native",
        nativeFullscreen = "Native Full-Screen",
        portrait = "(Portrait)",
        landscape = "(Landscape)",
        custom = "Custom",
        fpsSuffix = "FPS",
    )
    private val base = listOf(
        NovaSettingOption("720p", "1280x720"),
        NovaSettingOption("1080p", "1920x1080"),
    )

    @Test
    fun customResolutionIsOfferedAfterThePresets() {
        // nova#275: a custom resolution typed under Advanced never showed up
        // in the modern resolution list, only in the legacy view.
        val options = NovaDeviceListOptions.resolutionOptions(base, emptyList(), "2560x1600", labels)
        assertEquals(base + NovaSettingOption("Custom (2560x1600)", "2560x1600"), options)
    }

    @Test
    fun valuesAlreadyInTheListAreNotDuplicatedAndBadInputIsIgnored() {
        assertEquals(base, NovaDeviceListOptions.resolutionOptions(base, emptyList(), "1920x1080", labels))
        assertEquals(base, NovaDeviceListOptions.resolutionOptions(base, emptyList(), "abc", labels))
        assertEquals(base, NovaDeviceListOptions.resolutionOptions(base, emptyList(), "1920x", labels))
        assertEquals(base, NovaDeviceListOptions.resolutionOptions(base, emptyList(), null, labels))
        assertNull(NovaDeviceListOptions.parseResolution("0x1080"))
    }

    @Test
    fun squarishScreensAreListedBothWaysPortraitFirst() {
        val options = NovaDeviceListOptions.resolutionOptions(base, emptyList(), "1200x1000", labels)
        assertEquals(
            base + listOf(
                NovaSettingOption("Custom (Portrait) (1000x1200)", "1000x1200"),
                NovaSettingOption("Custom (Landscape) (1200x1000)", "1200x1000"),
            ),
            options,
        )
    }

    @Test
    fun nativeModesFollowTheCustomEntryAndNameTheNotchAdjustedOne() {
        val native = listOf(
            NovaDeviceListOptions.NativeSize(2340, 1080),
            NovaDeviceListOptions.NativeSize(2400, 1080, insetsRemoved = true),
            NovaDeviceListOptions.NativeSize(1920, 1080, insetsRemoved = true),
        )
        val options = NovaDeviceListOptions.resolutionOptions(base, native, "3840x2160", labels)
        assertEquals(
            base + listOf(
                NovaSettingOption("Custom (3840x2160)", "3840x2160"),
                NovaSettingOption("Native (2340x1080)", "2340x1080"),
                NovaSettingOption("Native Full-Screen (2400x1080)", "2400x1080"),
            ),
            options,
        )
    }

    @Test
    fun fpsListGetsTheCustomRateThenTheNativeRateInLegacyStringForms() {
        val fpsBase = listOf(NovaSettingOption("60", "60"), NovaSettingOption("120", "120"))
        val options = NovaDeviceListOptions.fpsOptions(fpsBase, 120f, "90", labels)
        assertEquals(fpsBase + NovaSettingOption("Custom (90.0 FPS)", "90.0"), options)
        assertEquals(
            fpsBase + NovaSettingOption("Native (144 FPS)", "144"),
            NovaDeviceListOptions.fpsOptions(fpsBase, 144.2f, "not-a-number", labels),
        )
        assertEquals(fpsBase, NovaDeviceListOptions.fpsOptions(fpsBase, null, "0", labels))
    }
}
