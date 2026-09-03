package com.papi.nova.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolarisStreamDisplayModeTest {
    @Test
    fun normalizeAcceptsCurrentAndLegacyAliases() {
        assertEquals(PolarisClientSettings.MODE_HEADLESS_STREAM, PolarisStreamDisplayMode.normalize("headless"))
        assertEquals(PolarisClientSettings.MODE_HEADLESS_STREAM, PolarisStreamDisplayMode.normalize("headless_stream"))
        assertEquals(PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY, PolarisStreamDisplayMode.normalize("virtual_display"))
        assertEquals(PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY, PolarisStreamDisplayMode.normalize("host_virtual_display"))
        assertEquals(PolarisClientSettings.MODE_DESKTOP_DISPLAY, PolarisStreamDisplayMode.normalize("desktop_display"))
        assertEquals(PolarisClientSettings.MODE_DESKTOP_TAKEOVER, PolarisStreamDisplayMode.normalize("desktop_takeover"))
        assertEquals(PolarisClientSettings.MODE_GPU_NATIVE_TEST, PolarisStreamDisplayMode.normalize("windowed_stream"))
    }

    @Test
    fun labelsUseNovaPlayerFacingNames() {
        assertEquals("Private Stream", PolarisStreamDisplayMode.labelForMode(PolarisClientSettings.MODE_HEADLESS_STREAM))
        assertEquals("Host Virtual Display", PolarisStreamDisplayMode.labelForMode(PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY))
        assertEquals("Mirror Desktop", PolarisStreamDisplayMode.labelForMode(PolarisClientSettings.MODE_DESKTOP_DISPLAY))
        assertEquals("Desktop Takeover", PolarisStreamDisplayMode.labelForMode(PolarisClientSettings.MODE_DESKTOP_TAKEOVER))
        assertEquals("Private Stream (GPU-native)", PolarisStreamDisplayMode.labelForMode(PolarisClientSettings.MODE_GPU_NATIVE_TEST))
        assertEquals("Gamescope Stream", PolarisStreamDisplayMode.labelForMode(PolarisClientSettings.MODE_GAMESCOPE_STREAM))
        assertEquals("Headless Dongle", PolarisStreamDisplayMode.labelForMode(PolarisClientSettings.MODE_HEADLESS_DONGLE))
    }

    @Test
    fun polarisClientSettingsLabelForModeUsesPlayerFacingLabels() {
        assertEquals("Private Stream", PolarisClientSettings.labelForMode(PolarisClientSettings.MODE_HEADLESS_STREAM))
        assertEquals("Private Stream", PolarisClientSettings.labelForMode("headless"))
        assertEquals("Host Virtual Display", PolarisClientSettings.labelForMode(PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY))
        assertEquals("Host Virtual Display", PolarisClientSettings.labelForMode("virtual_display"))
        assertEquals("Mirror Desktop", PolarisClientSettings.labelForMode(PolarisClientSettings.MODE_DESKTOP_DISPLAY))
        assertEquals("Private Stream (GPU-native)", PolarisClientSettings.labelForMode(PolarisClientSettings.MODE_GPU_NATIVE_TEST))
    }

    @Test
    fun preflightPreservesExplicitNonVirtualMode() {
        val settings = PolarisClientSettings(
            desired = PolarisClientSettings.Desired(streamDisplayMode = PolarisClientSettings.MODE_GPU_NATIVE_TEST),
            effective = PolarisClientSettings.Effective(streamDisplayMode = PolarisClientSettings.MODE_HEADLESS_STREAM)
        )

        assertEquals(
            PolarisClientSettings.MODE_GPU_NATIVE_TEST,
            PolarisStreamDisplayMode.preflightModeForLaunch(usesVirtualDisplay = false, settings = settings)
        )
        assertEquals(
            PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY,
            PolarisStreamDisplayMode.preflightModeForLaunch(usesVirtualDisplay = true, settings = settings)
        )
    }

    @Test
    fun modeFamiliesStayDistinct() {
        assertTrue(PolarisStreamDisplayMode.isVirtual(PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY))
        assertFalse(PolarisStreamDisplayMode.isVirtual(PolarisClientSettings.MODE_GPU_NATIVE_TEST))
        assertTrue(PolarisStreamDisplayMode.isPrivateFamily(PolarisClientSettings.MODE_GPU_NATIVE_TEST))
        assertTrue(PolarisStreamDisplayMode.isPrivateFamily(PolarisClientSettings.MODE_DESKTOP_DISPLAY))
        assertTrue(PolarisStreamDisplayMode.isPrivateFamily(PolarisClientSettings.MODE_DESKTOP_TAKEOVER))
    }

    @Test
    fun preflightPushesResolvedCanonicalMode() {
        val settings = PolarisClientSettings(
            desired = PolarisClientSettings.Desired(streamDisplayMode = PolarisClientSettings.MODE_HEADLESS_STREAM),
            effective = PolarisClientSettings.Effective(streamDisplayMode = PolarisClientSettings.MODE_HEADLESS_STREAM)
        )

        // Registry ids beyond the legacy pair collapsed to the private-family default
        // before; the resolved mode must survive the preflight verbatim.
        assertEquals(
            PolarisClientSettings.MODE_GAMESCOPE_STREAM,
            PolarisStreamDisplayMode.preflightModeForLaunch(
                usesVirtualDisplay = false,
                settings = settings,
                resolvedMode = PolarisClientSettings.MODE_GAMESCOPE_STREAM
            )
        )
        // The virtual-display flag stays the strongest signal.
        assertEquals(
            PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY,
            PolarisStreamDisplayMode.preflightModeForLaunch(
                usesVirtualDisplay = true,
                settings = settings,
                resolvedMode = PolarisClientSettings.MODE_GAMESCOPE_STREAM
            )
        )
        // A blank resolved mode keeps the legacy family-preserving behavior.
        assertEquals(
            PolarisClientSettings.MODE_HEADLESS_STREAM,
            PolarisStreamDisplayMode.preflightModeForLaunch(
                usesVirtualDisplay = false,
                settings = settings,
                resolvedMode = ""
            )
        )
    }
}
