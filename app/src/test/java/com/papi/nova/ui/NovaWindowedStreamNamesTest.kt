package com.papi.nova.ui

import android.content.Context
import com.papi.nova.R
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * windowed_stream is Private Stream (GPU-native), the name Polaris gives it in its mode catalog
 * and sends as its label; headless_stream is Private Stream. Round 4 renamed windowed_stream
 * Private Stream in Nova's own names, and the game page then named it twice over: its Host Default
 * line said "currently Private Stream" above the active row the host calls Private Stream
 * (GPU-native), with a Private Stream row of headless_stream's beside it, and the safe fallback's
 * sentence said "The host default, Private Stream, is not ready. Nova will use Private Stream for
 * this launch." Each surface here names each mode as the host does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaWindowedStreamNamesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val gpuNative = "Private Stream (GPU-native)"

    /** A Polaris host whose default display is [mode], with its catalog and its own labels. */
    private fun host(mode: String, catalog: Boolean = true): PolarisClientSettings {
        val label = if (catalog) (if (mode == PolarisClientSettings.MODE_GPU_NATIVE_TEST) gpuNative else "Private Stream") else ""
        return PolarisClientSettings(
            desired = PolarisClientSettings.Desired(streamDisplayMode = mode, streamDisplayModeLabel = label),
            effective = PolarisClientSettings.Effective(streamDisplayMode = mode, streamDisplayModeLabel = label),
            capabilities = PolarisClientSettings.Capabilities(
                modes = if (!catalog) emptyList() else listOf(
                    PolarisClientSettings.ModeOption(value = "headless_stream", label = "Private Stream", group = "private"),
                    PolarisClientSettings.ModeOption(value = "windowed_stream", label = gpuNative, group = "private"),
                    PolarisClientSettings.ModeOption(value = "desktop_display", label = "Mirror Desktop", group = "host"),
                ),
            ),
        )
    }

    private fun sync(settings: PolarisClientSettings) = NovaPolarisSyncUiStateMapper.build(
        settings = settings, busy = false, settingsUnavailable = false, autoSyncEnabled = false,
        hasServerUuid = true, novaDisplayMode = "1920x1080@60", novaBitrateKbps = 30000,
        loadingLabel = "Loading", unavailableLabel = "Unavailable", unsetLabel = "Unset", savedAfterRelaunchLabel = "Saved",
        selectedLabel = "Selected", activeNowLabel = "Active now", availableLabel = "Available",
    )

    private val portal = PolarisGame(id = "g", name = "Portal", source = "steam", runtime = "native")

    @Test
    fun thePickerTheSettingsAndThePillNameWindowedStreamAlike() {
        val sync = sync(host(PolarisClientSettings.MODE_GPU_NATIVE_TEST))
        val pill = NovaQuickMenuUiState.from(
            context = context,
            status = PolarisSessionStatus(
                state = "streaming",
                displayMode = PolarisSessionStatus.DisplayModeStatus(
                    requested = "windowed_stream",
                    selection = "windowed_stream",
                    label = gpuNative,
                ),
            ),
            apiAvailable = true,
            adaptiveSupported = true,
            aiSupported = true,
            adaptiveEnabled = false,
            aiEnabled = false,
            mangoHudEnabled = false,
            stabilityApplied = false,
            advancedExpanded = false,
            profileClearInProgress = false,
            currentGameName = "Portal",
            currentGameUuid = "g",
            profilePreference = "auto",
            hudShowing = false,
            perfOverlayEnabled = false,
            onscreenControllerEnabled = false,
            keyboardVisible = false,
            mouseModeLabel = "",
            allowChangeMouseMode = true,
            isOnExternalDisplay = false,
            fallbackBitrateKbps = 20000,
            fallbackTargetFps = 60.0,
        ).sessionMode.label
        assertEquals("the picker's row", gpuNative, sync.modes.first { it.mode == "windowed_stream" }.label)
        assertEquals("the settings", gpuNative, sync.desiredModeLabel)
        assertEquals("the pill", gpuNative, pill.substringBefore(" · "))
    }

    @Test
    fun theHostDefaultLineNamesTheRowItFollows() {
        val settings = host(PolarisClientSettings.MODE_GPU_NATIVE_TEST)
        val state = NovaGameDetailUiState.from(game = portal, defaultToVirtualDisplay = false, clientSettings = settings, profilePreference = "auto")
        val picker = buildGameModePickerState(
            modes = sync(settings).modes,
            allowedModes = emptyList(),
            playMode = state.playMode,
            hasExplicitOverride = state.hasExplicitOverride,
            title = context.getString(R.string.nova_game_detail_where_it_runs),
            hostDefaultLabel = context.getString(R.string.nova_play_setup_host_default_entry_detail, state.hostStreamDisplayModeLabel),
        )
        val active = picker.choices.single { it.active }
        assertEquals(gpuNative, active.label)
        assertEquals("Follow the host's Default Display, currently $gpuNative", picker.hostDefaultLabel)
        assertEquals("Launch $gpuNative", context.getString(R.string.nova_library_play_mode, state.playModeLabel))
        assertEquals("no two rows share a name", picker.choices.map { it.label }.distinct(), picker.choices.map { it.label })
    }

    @Test
    fun theSafeFallbackSentenceNamesTwoModes() {
        val game = portal.copy(
            launchMode = PolarisGame.LaunchModeContract(
                preferredMode = "headless_stream",
                recommendedMode = "headless_stream",
                allowedModes = listOf("headless_stream", "host_virtual_display"),
            ),
        )
        val state = NovaGameDetailUiState.from(
            game = game,
            defaultToVirtualDisplay = false,
            clientSettings = host(PolarisClientSettings.MODE_GPU_NATIVE_TEST),
            profilePreference = "auto",
        )
        assertTrue(state.usesSafeHostFallback)
        assertEquals(
            "The host default, $gpuNative, is not ready. Nova will use Private Stream for this launch.",
            context.getString(R.string.nova_library_launch_intro_safe_fallback, state.hostStreamDisplayModeLabel, state.playModeLabel),
        )
    }

    /** A host that predates the catalog and sends no labels: Nova's own names, still two of them. */
    @Test
    fun withoutTheHostsNamesNovasNeverShareOne() {
        val rows = sync(host(PolarisClientSettings.MODE_GPU_NATIVE_TEST, catalog = false)).modes.associate { it.mode to it.label }
        assertEquals(gpuNative, rows.getValue(PolarisClientSettings.MODE_GPU_NATIVE_TEST))
        assertNotEquals(rows.getValue(PolarisClientSettings.MODE_HEADLESS_STREAM), rows.getValue(PolarisClientSettings.MODE_GPU_NATIVE_TEST))
        val state = NovaGameDetailUiState.from(
            game = portal,
            defaultToVirtualDisplay = false,
            clientSettings = host(PolarisClientSettings.MODE_GPU_NATIVE_TEST, catalog = false),
            profilePreference = "auto",
        )
        assertEquals("Launch $gpuNative", context.getString(R.string.nova_library_play_mode, state.playModeLabel))
    }
}
