package com.papi.nova.ui

import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
import com.papi.nova.manager.PolarisProfileSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaPolarisSyncUiStateTest {
    @Test
    fun loadingStateDisablesHostControls() {
        val state = NovaPolarisSyncUiStateMapper.build(
            settings = null,
            busy = false,
            settingsUnavailable = false,
            autoSyncEnabled = false,
            hasServerUuid = true,
            novaDisplayMode = "1920x1080@60",
            novaBitrateKbps = 30000
        )

        assertEquals(NovaPolarisSyncStatus.LOADING, state.status)
        assertEquals("Loading", state.desiredModeLabel)
        assertEquals("Loading", state.effectiveModeLabel)
        assertFalse(state.aiEnabled)
        assertFalse(state.autoSyncEnabled)
        assertFalse(state.sendNovaEnabled)
        assertTrue(state.modes.none { it.enabled })
    }

    @Test
    fun availableSettingsSelectDesiredModeAndEnableActions() {
        val state = NovaPolarisSyncUiStateMapper.build(
            settings = settings(),
            busy = false,
            settingsUnavailable = false,
            autoSyncEnabled = true,
            hasServerUuid = true,
            novaDisplayMode = "1920x1080@60",
            novaBitrateKbps = 30000
        )

        assertEquals(NovaPolarisSyncStatus.SYNCED, state.status)
        assertEquals("Private Stream", state.desiredModeLabel)
        assertEquals("Host Virtual Display", state.effectiveModeLabel)
        assertTrue(state.modes.first { it.mode == PolarisClientSettings.MODE_HEADLESS_STREAM }.selected)
        assertFalse(state.modes.first { it.mode == PolarisClientSettings.MODE_GPU_NATIVE_TEST }.enabled)
        assertTrue(state.aiChecked)
        assertTrue(state.aiEnabled)
        assertTrue(state.autoSyncChecked)
        assertTrue(state.autoSyncEnabled)
        assertTrue(state.sendNovaEnabled)
        assertTrue(state.usePolarisEnabled)
        assertTrue(state.clearProfileEnabled)
    }

    @Test
    fun normalizedVirtualDisplayModeSelectsAndDisablesCanonicalRow() {
        val state = NovaPolarisSyncUiStateMapper.build(
            settings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(streamDisplayMode = "virtual_display"),
                capabilities = PolarisClientSettings.Capabilities(
                    modes = listOf(
                        PolarisClientSettings.ModeOption(
                            value = "virtual_display",
                            available = false,
                            reason = "CUDA capture path is disabled"
                        )
                    )
                )
            ),
            busy = false,
            settingsUnavailable = false,
            autoSyncEnabled = false,
            hasServerUuid = true,
            novaDisplayMode = "1920x1080@60",
            novaBitrateKbps = 30000
        )

        val virtual = state.modes.first { it.mode == PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY }
        assertTrue(virtual.selected)
        assertFalse(virtual.enabled)
    }

    @Test
    fun busyStateDisablesAllMutatingActions() {
        val state = NovaPolarisSyncUiStateMapper.build(
            settings = settings(),
            busy = true,
            settingsUnavailable = false,
            autoSyncEnabled = true,
            hasServerUuid = true,
            novaDisplayMode = "1920x1080@60",
            novaBitrateKbps = 30000
        )

        assertEquals(NovaPolarisSyncStatus.SYNCING, state.status)
        assertFalse(state.sendNovaEnabled)
        assertFalse(state.matchNovaEnabled)
        assertFalse(state.usePolarisEnabled)
        assertFalse(state.clearProfileEnabled)
        assertFalse(state.autoSyncEnabled)
        assertTrue(state.modes.none { it.enabled })
    }

    @Test
    fun profileActionsReflectProfileComparison() {
        val matched = NovaPolarisSyncUiStateMapper.build(
            settings = settings(displayMode = "1920x1080@60", bitrateKbps = 30000),
            busy = false,
            settingsUnavailable = false,
            autoSyncEnabled = false,
            hasServerUuid = true,
            novaDisplayMode = "1920x1080@60",
            novaBitrateKbps = 30000
        )
        val different = NovaPolarisSyncUiStateMapper.build(
            settings = settings(displayMode = "2560x1440@60", bitrateKbps = 45000),
            busy = false,
            settingsUnavailable = false,
            autoSyncEnabled = false,
            hasServerUuid = true,
            novaDisplayMode = "1920x1080@60",
            novaBitrateKbps = 30000
        )

        assertEquals(PolarisProfileSync.ProfileState.MATCHED, matched.profileState)
        assertFalse(matched.matchNovaVisible)
        assertEquals(PolarisProfileSync.ProfileState.DIFFERENT, different.profileState)
        assertTrue(different.matchNovaVisible)
        assertTrue(different.matchNovaEnabled)
    }

    @Test
    fun modeMapperNormalizesCapabilitiesAndMarksPendingRelaunch() {
        val state = NovaPolarisSyncUiStateMapper.build(
            settings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(
                    streamDisplayMode = PolarisClientSettings.MODE_GPU_NATIVE_TEST
                ),
                effective = PolarisClientSettings.Effective(
                    streamDisplayMode = PolarisClientSettings.MODE_HEADLESS_STREAM
                ),
                capabilities = PolarisClientSettings.Capabilities(
                    modes = PolarisStreamDisplayMode.ORDER.map {
                        PolarisClientSettings.ModeOption(value = it, available = true)
                    }
                ),
                relaunchRequired = true
            ),
            busy = false,
            settingsUnavailable = false,
            autoSyncEnabled = false,
            hasServerUuid = true,
            novaDisplayMode = "1920x1080@60",
            novaBitrateKbps = 30000
        )

        val gpuNative = state.modes.first { it.mode == PolarisClientSettings.MODE_GPU_NATIVE_TEST }
        assertTrue(gpuNative.selectedDesired)
        assertFalse(gpuNative.selectedEffective)
        assertEquals("GPU-Native Test", gpuNative.label)
        assertEquals("Saved — applies after relaunch", gpuNative.statusLabel)
        assertTrue(state.relaunchRequired)
    }

    @Test
    fun unavailableHostVirtualDisplayCarriesReason() {
        val state = NovaPolarisSyncUiStateMapper.build(
            settings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(
                    streamDisplayMode = PolarisClientSettings.MODE_HEADLESS_STREAM
                ),
                effective = PolarisClientSettings.Effective(
                    streamDisplayMode = PolarisClientSettings.MODE_HEADLESS_STREAM
                ),
                capabilities = PolarisClientSettings.Capabilities(
                    modes = listOf(
                        PolarisClientSettings.ModeOption(
                            value = PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY,
                            available = false,
                            reason = "No virtual display backend"
                        )
                    )
                )
            ),
            busy = false,
            settingsUnavailable = false,
            autoSyncEnabled = false,
            hasServerUuid = true,
            novaDisplayMode = "1920x1080@60",
            novaBitrateKbps = 30000
        )

        val virtual = state.modes.first { it.mode == PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY }
        assertFalse(virtual.enabled)
        assertEquals("No virtual display backend", virtual.reason)
    }

    private fun settings(
        displayMode: String = "2560x1440@60",
        bitrateKbps: Int = 45000
    ) = PolarisClientSettings(
        desired = PolarisClientSettings.Desired(
            streamDisplayMode = PolarisClientSettings.MODE_HEADLESS_STREAM,
            displayMode = displayMode,
            targetBitrateKbps = bitrateKbps,
            aiAutoQualityEnabled = true
        ),
        effective = PolarisClientSettings.Effective(
            streamDisplayMode = PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY,
            adaptiveBitrateEnabled = true
        ),
        capabilities = PolarisClientSettings.Capabilities(
            modes = listOf(
                PolarisClientSettings.ModeOption(
                    value = PolarisClientSettings.MODE_HEADLESS_STREAM,
                    available = true
                ),
                PolarisClientSettings.ModeOption(
                    value = PolarisClientSettings.MODE_GPU_NATIVE_TEST,
                    available = false
                )
            ),
            aiAutoQualityControl = true
        )
    )
}
