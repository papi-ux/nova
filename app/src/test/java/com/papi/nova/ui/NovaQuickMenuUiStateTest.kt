package com.papi.nova.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import com.papi.nova.api.PolarisSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaQuickMenuUiStateTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun viewerSessionShowsLeaveAndLocksOwnerOnlyControls() {
        val state = quickState(
            status = status(
                clientRole = "viewer",
                controls = PolarisSessionStatus.ControlsStatus(
                    hostTuningAllowed = false,
                    quitAllowed = false
                )
            ),
            currentGameName = "Portal"
        )

        assertEquals("Leave", state.endAction.label)
        assertFalse(state.controlRows.first { it.id == NovaQuickMenuActionId.MOUSE_MODE }.enabled)
        assertFalse(state.controlRows.first { it.id == NovaQuickMenuActionId.KEYBOARD }.enabled)
        assertTrue("pinned and grid quick keys cannot send viewer input", state.quickKeys.none { it.enabled })
        assertTrue(state.pinnedQuickKeys.none { it.enabled })
        assertEquals(
            listOf(NovaQuickMenuActionId.CLEAR_GAME_PROFILE, NovaQuickMenuActionId.MANGOHUD),
            state.advancedRows.map { it.id }
        )
        assertEquals("Owner", state.stability.chip.label)
        assertEquals(NovaQuickMenuTone.MUTED, state.stability.chip.tone)
    }

    @Test
    fun losingQuitAuthorityNeverEnablesEndAndRecoveryRequiresAnAllowedReading() {
        val denied = status(ownedByClient = false, controls = PolarisSessionStatus.ControlsStatus(hostTuningAllowed = false, quitAllowed = false))
        assertFalse("the fixture independently denies End", denied.canQuit)
        assertFalse(quickState(status = denied).endAction.enabled)
        val unavailable = quickState(status = null, lastStatus = denied, hostStateUnavailable = true)
        assertFalse("a failed read grants no End authority", unavailable.endAction.enabled)
        assertTrue("safe local disconnect remains available", unavailable.disconnectAction.enabled)
        assertTrue(quickState(status = status()).endAction.enabled)
    }

    @Test
    fun syncNeedsRelaunchUsesWarningChipAndRelaunchCaption() {
        val state = quickState(
            status = status(
                syncStatus = PolarisSessionStatus.SyncStatus(
                    available = true,
                    state = "needs_relaunch",
                    message = "Saved settings apply on next launch"
                )
            )
        )

        val syncChip = state.sync.chip!!
        assertEquals("Relaunch", syncChip.label)
        assertEquals(NovaQuickMenuTone.WARNING, syncChip.tone)
        assertEquals("Saved settings apply on next launch", state.sync.caption)
    }

    @Test
    fun hostRenderLimitedSessionWarnsWithObservationalPacingCopy() {
        val state = quickState(
            status = status(
                aiOptimizerEnabled = true,
                tuning = PolarisSessionStatus.TuningStatus(aiOptimizerEnabled = true),
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    summary = "Host render path is missing the target frame rate",
                    primaryIssue = "host_render_limited",
                    hostRenderLimited = true,
                    relaunchRecommended = true
                ),
                autoQuality = PolarisSessionStatus.AutoQualityPolicy(
                    enabled = true,
                    state = "recovery_queued",
                    relaunchRequired = true
                )
            ),
            aiEnabled = true
        )

        assertEquals("Frame-pacing evidence needs a read-only recheck; launch settings are unchanged.", state.healthSummary)
        assertEquals(NovaQuickMenuTone.WARNING, state.healthTone)
        assertEquals("Quality", state.stability.chip.label)
        assertEquals("Live Tuning", state.liveTuningAction.label)
        assertEquals("Off", state.liveTuningAction.chip?.label)
        assertEquals(NovaQuickMenuTone.INFO, state.stability.chip.tone)
    }

    @Test
    fun hostRenderLimitedWithoutRecoveryUsesMonitoringCopyOnly() {
        val state = quickState(
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    summary = "Host render path is missing the target frame rate",
                    primaryIssue = "host_render_limited",
                    hostRenderLimited = true,
                    relaunchRecommended = false
                ),
                autoQuality = PolarisSessionStatus.AutoQualityPolicy(
                    enabled = true,
                    state = "blocked",
                    blockedReason = "host_render_limited",
                    relaunchRequired = false
                )
            )
        )

        assertEquals("Host is rendering below the stream FPS target.", state.healthSummary)
        assertEquals(NovaQuickMenuTone.WARNING, state.healthTone)
    }

    @Test
    fun headlessHdrDowngradeShowsPlayerReadableCommandCenterCopy() {
        val state = quickState(
            status = status(
                displayMode = PolarisSessionStatus.DisplayModeStatus(
                    requested = "headless",
                    effectiveHeadless = true
                ),
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    primaryIssue = "hdr_downgraded",
                    issues = listOf("hdr_downgraded")
                )
            )
        )

        assertEquals("HDR requested, but Private Stream is 10-bit SDR.", state.healthSummary)
        assertEquals("Private Stream does not report HDR metadata. Polaris is sending 10-bit SDR; use an HDR-capable display path for true HDR.", state.healthDetail)
        assertEquals(NovaQuickMenuTone.WARNING, state.healthTone)
    }

    @Test
    fun framePacingWarningOverridesStaleStableSummary() {
        val state = quickState(
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    summary = "Stable",
                    primaryIssue = "frame_pacing",
                    issues = listOf("frame_pacing")
                )
            )
        )

        assertEquals("Frame pacing", state.healthSummary)
        assertEquals(NovaQuickMenuTone.WARNING, state.healthTone)
        assertFalse(state.healthSummary.contains("Stable", ignoreCase = true))
    }

    @Test
    fun failedPollKeepsTheLastHdrDetailMarkedAsOld() {
        val last = status(health = PolarisSessionStatus.HealthStatus(
            grade = "watch", primaryIssue = "hdr_downgraded", issues = listOf("hdr_downgraded")
        ))
        val fresh = quickState(last)
        val failed = quickState(null, lastStatus = last, hostStateUnavailable = true)
        assertTrue(fresh.healthDetail.isNotBlank())
        assertTrue(failed.healthDetail.contains(fresh.healthDetail))
        assertTrue(failed.healthDetail.startsWith("Last confirmed:"))
        assertEquals("", quickState(status(), lastStatus = last).healthDetail)
    }

    @Test
    fun failedPollKeepsTheRecoveryReceiptVisibleButCannotUndoIt() {
        val receipt = DoctorActionReceipt(
            scopeId = "scope", runId = "recovery-run-a", state = "applied", message = "Bitrate lowered.",
            undoAvailable = true, undoActionId = "undo_recovery_profile_next_launch"
        )
        val failed = quickState(null, lastStatus = status(), hostStateUnavailable = true, doctorReceipt = receipt)
        assertTrue(failed.doctorReceiptAction.visible)
        assertFalse(failed.doctorReceiptAction.enabled)
        assertTrue(failed.doctorReceiptAction.caption.startsWith("Last confirmed:"))
    }

    @Test
    fun authoritativeDoctorNoneDoesNotShowStaleHealthSummaryBesideSteadyState() {
        val state = quickState(
            status = status(
                health = PolarisSessionStatus.HealthStatus(
                    grade = "watch",
                    summary = "Network jitter was previously observed.",
                    primaryIssue = "network_jitter",
                    issues = listOf("network_jitter")
                ),
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-current-none",
                    primaryIssue = "none",
                    likelyCause = "No confirmed issue"
                )
            )
        )

        assertEquals("Session looks steady.", state.healthSummary)
        assertFalse(state.healthSummary.contains("Network", ignoreCase = true))
        assertFalse(state.diagnosis.likelyCause.contains("Network", ignoreCase = true))
    }

    @Test
    fun doctorTryFirstDoesNotRepeatTheTitleNowThatTheCardLeadsThePage() {
        fun diagnosis(likelyCause: String, tryFirst: String) = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-$likelyCause",
                    primaryIssue = "none",
                    likelyCause = likelyCause,
                    tryFirst = listOf(tryFirst)
                )
            )
        ).diagnosis

        val ready = diagnosis(
            "Streaming telemetry looks ready.",
            "Streaming telemetry looks ready. Keep this page open if you are trying to catch an intermittent problem."
        )
        assertEquals("Streaming telemetry looks ready.", ready.likelyCause)
        assertEquals(
            "the finding is already the card's title, so the try-first line keeps only the advice that follows it",
            "Keep this page open if you are trying to catch an intermittent problem.",
            ready.tryFirst
        )

        assertEquals(
            "a try-first that only restates the title disappears instead of saying it twice",
            "",
            diagnosis("No confirmed issue", "No confirmed issue.").tryFirst
        )
        assertEquals(
            "advice that does not open with the title is untouched",
            "Move closer to the access point.",
            diagnosis("Network jitter on the link", "Move closer to the access point.").tryFirst
        )
        assertEquals(
            "a title that is only a prefix of a longer word is not a repeated sentence",
            "Streaming telemetry looks readyish today.",
            diagnosis("Streaming telemetry looks ready", "Streaming telemetry looks readyish today.").tryFirst
        )
    }

    @Test
    fun controllerToggleCopyClarifiesTouchOverlayInsteadOfPhysicalGamepad() {
        val state = quickState(status = status(), currentGameName = "Portal")
        val touchControls = state.controlRows.first { it.id == NovaQuickMenuActionId.CONTROLLER }

        assertEquals("Touch Controls", touchControls.label)
        assertEquals("On-screen overlay; physical gamepad stays active.", touchControls.caption)
        assertEquals("Off", touchControls.chip!!.label)
    }

    @Test
    fun nonPolarisSessionDisablesHostTuningRows() {
        val state = quickState(status = null, apiAvailable = false)

        assertEquals("Checking stream mode", state.sessionMode.label)
        assertEquals("N/A", state.advancedRows.first { it.id == NovaQuickMenuActionId.MANGOHUD }.chip!!.label)
        assertEquals(
            listOf(NovaQuickMenuActionId.CLEAR_GAME_PROFILE, NovaQuickMenuActionId.MANGOHUD),
            state.advancedRows.map { it.id }
        )
        assertFalse(state.advancedRows.first { it.id == NovaQuickMenuActionId.CLEAR_GAME_PROFILE }.enabled)
    }

    @Test
    fun pinnedQuickKeysAreTheThreeAHandheldCannotPressAnyOtherWay() {
        val state = NovaQuickMenuUiState.preview(context)

        assertEquals(
            "Esc for the game's own menu, Meta for the desktop, Alt + Enter for fullscreen: the keys worth one reach from the top, in that order",
            listOf(
                NovaQuickMenuActionId.QUICK_ESC,
                NovaQuickMenuActionId.QUICK_META,
                NovaQuickMenuActionId.QUICK_ALT_ENTER
            ),
            state.pinnedQuickKeys.map { it.id }
        )
        assertTrue(
            "the pinned row reuses the grid's own actions, so the grid stays the whole keyboard and both rows fire the same key",
            state.pinnedQuickKeys.all { pinned -> state.quickKeys.any { it === pinned } }
        )
    }

    @Test
    fun previewStateExposesCoreActionsForComposeContent() {
        val state = NovaQuickMenuUiState.preview(context).copy(advancedExpanded = true)

        assertEquals("Command Center", state.title)
        assertEquals("Session controls for Private Stream", state.subtitle)
        assertEquals("Disconnect", state.disconnectAction.label)
        assertEquals("End Session", state.endAction.label)
        assertTrue(state.quickKeys.any { it.id == NovaQuickMenuActionId.QUICK_ESC && it.label == "ESC" })
        assertTrue(state.quickKeys.any { it.id == NovaQuickMenuActionId.QUICK_CTRL_V && it.label == "Ctrl + V" })
        assertTrue(state.quickKeys.any { it.id == NovaQuickMenuActionId.QUICK_INSERT && it.label == "Insert" })
        assertTrue(state.quickKeys.any { it.id == NovaQuickMenuActionId.QUICK_CTRL_1 && it.label == "Ctrl + 1" })
        assertTrue(state.quickKeys.any { it.id == NovaQuickMenuActionId.QUICK_CTRL_2 && it.label == "Ctrl + 2" })
        assertTrue(state.overlayRows.any { it.id == NovaQuickMenuActionId.PERF_STATS && it.label == "Stats Overlay" })
        assertTrue(state.advancedRows.any { it.id == NovaQuickMenuActionId.MANGOHUD && it.label == "MangoHud" })
        assertTrue(state.sessionRows.any { it.id == NovaQuickMenuActionId.MORE_KEYS && it.label == "More Keys" })
    }

    @Test
    fun endSessionIsTheHeadersSplitAndInASpaceItReadsLeaveSpace() {
        val preview = NovaQuickMenuUiState.preview(context)
        assertTrue("an owner's End Session confirms in place, so it is destructive", preview.endAction.destructive)
        assertEquals(
            "the armed split says what ending does",
            context.getString(com.papi.nova.R.string.nova_cc_end_session_consequence),
            preview.endAction.caption,
        )
        assertTrue(
            "the legacy Quick Menu's extras have a row of their own in Session",
            preview.sessionRows.any { it.id == NovaQuickMenuActionId.MORE_CONTROLS && it.label == "More Controls" }
        )

        val space = NovaQuickMenuUiState.from(
            context = context,
            status = status(),
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
            currentGameUuid = "game-1",
            profilePreference = "auto",
            hudShowing = false,
            perfOverlayEnabled = false,
            onscreenControllerEnabled = false,
            keyboardVisible = false,
            mouseModeLabel = "Direct",
            allowChangeMouseMode = true,
            isOnExternalDisplay = false,
            fallbackBitrateKbps = 20000,
            fallbackTargetFps = 60.0,
            spaceSession = true,
        )
        assertEquals("Leave Space", space.endAction.label)
        assertTrue(space.endAction.visible && space.endAction.destructive && space.endAction.enabled)
        assertFalse("in a Space, Disconnect would leave the Space anyway, so it gives way to the split", space.disconnectAction.visible)

        val viewer = quickState(
            status = status(
                clientRole = "viewer",
                controls = PolarisSessionStatus.ControlsStatus(hostTuningAllowed = false, quitAllowed = false)
            )
        )
        assertFalse("a viewer's Leave ends nothing on the host, so it needs no split", viewer.endAction.destructive)
    }

    @Test
    fun commandCenterLabelsPrivateGpuNativeCaptureInsteadOfRawHeadless() {
        val state = quickState(
            status = status(
                encoder = PolarisSessionStatus.EncoderStatus(
                    targetDevice = "vulkan",
                    targetResidency = "gpu",
                    activeBackend = "vulkan",
                    selection = PolarisSessionStatus.EncoderSelectionStatus(
                        mode = "auto",
                        gpuDriver = "amdgpu",
                        policy = "amd_private_vulkan_live_probe",
                        preferredEncoder = "vulkan",
                        fallbackEncoder = "vaapi",
                        selectedEncoder = "vulkan",
                        exactLiveProbeRequired = true,
                    ),
                ),
                capture = PolarisSessionStatus.CaptureStatus(
                    transport = "dmabuf",
                    residency = "gpu"
                ),
                displayMode = PolarisSessionStatus.DisplayModeStatus(
                    requested = "headless",
                    effectiveHeadless = true
                )
            )
        )

        assertTrue(state.sessionMode.label.contains("Private Stream"))
        assertTrue(state.sessionMode.label.contains("Auto → Vulkan"))
        assertFalse(state.sessionMode.label.contains("Headless"))
        // The capture path belongs to the detail line, not the pill, so the health summary
        // beside the pill keeps its room.
        assertFalse(state.sessionMode.label.contains("GPU capture"))
        assertFalse(state.sessionMode.label.contains("owner"))
        // N26 and review finding 10: plain words. It read "GPU-native DMA-BUF · Explicit choice ·
        // Owner", then "GPU capture (DMA-BUF) · ...".
        assertEquals("GPU capture · Mode you picked · Your session", state.sessionDetail)
    }

    /**
     * The host's own name for a mode is its name. Polaris names windowed_stream "Private Stream
     * (GPU-native)" and headless_stream "Private Stream", and the library's picker, the game page
     * and Settings show those names, so the pill does too. Round 3 made the pill say Private Stream
     * for both, and round 4 then renamed windowed_stream everywhere, so two modes shared a name.
     * A host that sends no name gets Nova's, which for windowed_stream is the same one.
     */
    @Test
    fun theSessionPillNamesTheModeAsItsHostDoes() {
        val gpuNative = "Private Stream (GPU-native)"
        assertEquals("the host's name", gpuNative, pill("windowed_stream", label = gpuNative))
        assertEquals("the host's name, whatever Nova calls the mode", "Mirror Desktop", pill("desktop_display", label = "Mirror Desktop"))
        assertEquals("Nova's name when the host sends none", gpuNative, pill("windowed_stream"))
        assertEquals("Private Stream", pill("headless", headless = true))
        assertEquals("Private Stream", pill("headless_stream", label = "Private Stream", headless = true))
    }

    /**
     * No locale translates the mode names yet, so English resources and English literals read the
     * same. For a host that sends no name, the pill runs here against resources that name each
     * mode in other words, which only a pill that reads its names from resources can say. A name
     * the host sends, such as a Space's, is the host's own.
     */
    @Test
    fun theSessionPillNamesEveryModeFromResources() {
        val named = contextNaming(
            com.papi.nova.R.string.nova_session_mode_headless to "Privater Stream",
            com.papi.nova.R.string.nova_library_launch_gpu_native_test to "Privater Stream (GPU-nativ)",
            com.papi.nova.R.string.nova_session_mode_host_display to "Desktop spiegeln",
            com.papi.nova.R.string.nova_session_mode_desktop_takeover to "Desktop übernehmen",
            com.papi.nova.R.string.nova_session_mode_virtual_display to "Virtuelle Anzeige",
        )
        assertEquals(
            listOf("Privater Stream (GPU-nativ)", "Privater Stream", "Desktop spiegeln", "Desktop übernehmen", "Virtuelle Anzeige", "Living Room Space"),
            listOf(
                pill("windowed_stream", context = named),
                pill("headless", headless = true, context = named),
                pill("desktop_display", context = named),
                pill("desktop_takeover", context = named),
                pill("virtual_display", virtual = true, context = named),
                pill("", label = "Living Room Space", context = named),
            ),
        )
    }

    /**
     * Review finding 8's follow up, settled the other way: round 4 named windowed_stream Private
     * Stream wherever Nova names it, which is headless_stream's name. Nova's own names for it, for
     * a host that sends none, are the host's: Private Stream (GPU-native), in the picker, the Play
     * badge, the launch snackbar, the settings and the pill alike, and never headless_stream's.
     */
    @Test
    fun windowedStreamHasItsHostsNameWhereverNovaNamesIt() {
        val gpuNative = "Private Stream (GPU-native)"
        val library = context.getString(com.papi.nova.R.string.nova_library_launch_gpu_native_test)
        assertEquals("the picker, the Play badge and the launch snackbar", gpuNative, library)
        val settings = com.papi.nova.api.PolarisClientSettings
        assertEquals("the settings", gpuNative, settings.labelForMode(settings.MODE_GPU_NATIVE_TEST))
        assertEquals("the pill", gpuNative, pill("windowed_stream"))
        assertFalse(
            "headless_stream's name",
            settings.labelForMode(settings.MODE_HEADLESS_STREAM) == settings.labelForMode(settings.MODE_GPU_NATIVE_TEST) ||
                context.getString(com.papi.nova.R.string.nova_library_launch_headless) == library ||
                pill("headless_stream", headless = true) == pill("windowed_stream"),
        )
    }

    /**
     * gamescope_stream and headless_dongle, which the library names in resources, had no session
     * mode: a host that sent no name for them read as Private Stream by its flags. They are named
     * from the library's resources then, and a name the host sends, a Space's included, is the
     * host's own.
     */
    @Test
    fun theSessionPillNamesGamescopeAndTheDongleAsTheLibraryDoes() {
        val named = contextNaming(
            com.papi.nova.R.string.nova_library_launch_gamescope to "Gamescope Übertragung",
            com.papi.nova.R.string.nova_library_launch_dongle to "Kopfloser Dongle",
        )
        assertEquals("Gamescope Übertragung", pill("gamescope_stream", headless = true, context = named))
        assertEquals("Kopfloser Dongle", pill("headless_dongle", headless = true, context = named))
        assertEquals("the host's own name for it", "Gamescope Stream", pill("", label = "Gamescope Stream", context = named))
        assertEquals("a Space's own label still wins", "Living Room Space", pill("gamescope_stream", label = "Living Room Space", context = named))
    }

    private fun pill(
        requested: String,
        label: String = "",
        headless: Boolean = false,
        virtual: Boolean = false,
        context: Context = this.context,
    ) = quickState(
        status = status(
            displayMode = PolarisSessionStatus.DisplayModeStatus(
                requested = requested,
                label = label,
                effectiveHeadless = headless,
                virtualDisplay = virtual,
            ),
        ),
        context = context,
    ).sessionMode.label

    /** The app's context, with the given string resources saying other words. */
    private fun contextNaming(vararg names: Pair<Int, String>): Context {
        val base = context
        val words = names.toMap()
        @Suppress("DEPRECATION")
        val resources = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getString(id: Int): String = words[id] ?: super.getString(id)
            override fun getText(id: Int): CharSequence = words[id] ?: super.getText(id)
        }
        return object : ContextWrapper(base) {
            override fun getResources(): Resources = resources
        }
    }

    @Test
    fun commandCenterNamesEncoderFallback() {
        val state = quickState(
            status = status(
                encoder = PolarisSessionStatus.EncoderStatus(
                    activeBackend = "vaapi",
                    selection = PolarisSessionStatus.EncoderSelectionStatus(
                        mode = "auto",
                        gpuDriver = "amdgpu",
                        policy = "amd_private_vulkan_live_probe",
                        preferredEncoder = "vulkan",
                        fallbackEncoder = "vaapi",
                        selectedEncoder = "vaapi",
                        fallbackUsed = true,
                        reason = "live_probe_failed",
                    ),
                ),
            ),
        )

        assertTrue(state.sessionMode.label.contains("Vulkan → VAAPI fallback"))
    }

    @Test
    fun commandCenterTargetSummaryIncludesAmdVaapiHostCaptureTruth() {
        val state = quickState(
            status = status(
                encoder = PolarisSessionStatus.EncoderStatus(
                    codec = "hevc",
                    bitrateKbps = 22000,
                    requestedClientFps = 120.0,
                    sessionTargetFps = 120.0,
                    encodeTargetFps = 120.0,
                    targetDevice = "vaapi",
                    targetResidency = "gpu"
                ),
                capture = PolarisSessionStatus.CaptureStatus(
                    resolution = "1920x1080",
                    transport = "shm",
                    residency = "cpu"
                ),
                linuxGpuProfile = PolarisSessionStatus.LinuxGpuProfile(
                    encoderApi = "vaapi",
                    encoderAdapter = "/dev/dri/renderD128",
                    captureDevice = "/dev/dri/renderD128",
                    adapterMatchesCaptureDevice = true,
                    gpuNativeRequested = true,
                    gpuNativeAttempted = true,
                    gpuNativeSucceeded = false
                )
            ),
            fallbackTargetFps = 120.0
        )

        assertTrue(state.stability.targetSummary.contains("HEVC"))
        assertTrue(state.stability.targetSummary.contains("VAAPI + SHM fallback"))
    }

    @Test
    fun commandCenterExposesDiagnoseThisStreamAsTheDoctorCardAction() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-v2-needs_action-network_jitter-gpu_native",
                    classification = "NET",
                    likelyCause = "Wi-Fi jitter is the likely bottleneck.",
                    evidence = listOf("3.4% packet loss"),
                    tryFirst = listOf("Lower bitrate"),
                    confidence = "high",
                    primaryIssue = "network_jitter",
                    actionId = "lower_bitrate",
                    actionLabel = "Auto Fix",
                    actionCapability = "auto_fix",
                    actionKind = "live_tuning",
                    actionEndpoint = "/api/doctor/action",
                    actionMethod = "POST",
                    actionPayloadId = "lower_bitrate",
                    actionSourceResultId = "doctor-v2-needs_action-network_jitter-gpu_native",
                    actionContractTyped = true,
                    targetBitrateKbps = 16000,
                    targetBitratePresent = true,
                    targetBitrateTyped = true,
                    verificationDelaySeconds = 8,
                    verificationMode = "live_telemetry",
                    verificationEndpoint = "/api/doctor/action",
                    undoSupported = true,
                    undoEndpoint = "/api/doctor/action",
                    requiresOwner = true,
                    evidenceItems = listOf(
                        PolarisSessionStatus.DoctorStatus.EvidenceItem(
                            id = "packet_loss",
                            status = "fail",
                            source = "media_transport",
                            value = 3.4
                        )
                    ),
                    packetLossPct = 3.4,
                    latencyMs = 12.0
                )
            )
        )

        val diagnose = state.diagnosisAction
        assertEquals(NovaQuickMenuActionId.DIAGNOSE_STREAM, diagnose.id)
        // Doctor's verdict is its own card; the Overlays panel holds overlays only.
        assertFalse(state.overlayRows.any { it.id == NovaQuickMenuActionId.DIAGNOSE_STREAM })
        assertEquals("Auto Fix", diagnose.label)
        assertEquals("Wi-Fi jitter is the likely bottleneck.", diagnose.caption)
        assertEquals("NET", diagnose.chip!!.label)
        assertEquals(NovaQuickMenuTone.WARNING, diagnose.chip.tone)
        assertEquals("Lower bitrate", state.diagnosis.tryFirst)
        assertEquals("3.4% packet loss", state.diagnosis.evidence.first())
        assertEquals("3.4% packet loss", state.diagnosis.evidenceHighlight)
        assertEquals("high", state.diagnosis.confidence)
        assertTrue(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.AUTO_FIX, state.diagnosis.capability)
        assertEquals(16000, state.diagnosis.targetBitrateKbps)
        assertFalse("a reading Nova can act on is not the quiet kind", state.diagnosis.informational)
    }

    /**
     * Review finding 1: a reading kept through a failed status read is a few seconds old, and A
     * runs nothing on it; it copies the details. It never names the reading's own action as what
     * A does, though the status that carried the reading let this device run it.
     */
    @Test
    fun aKeptReadingNeverNamesItsRunnableActionAsWhatADoes() {
        val runnable = status(
            doctor = PolarisSessionStatus.DoctorStatus(
                available = true,
                version = 2,
                resultId = "doctor-v2-needs_action-network_jitter",
                classification = "NET",
                likelyCause = "Wi-Fi jitter is the likely bottleneck.",
                evidence = listOf("3.4% packet loss"),
                confidence = "high",
                primaryIssue = "network_jitter",
                actionId = "lower_bitrate",
                actionLabel = "Auto Fix",
                actionCapability = "auto_fix",
                actionKind = "live_tuning",
                actionEndpoint = "/api/doctor/action",
                actionMethod = "POST",
                actionPayloadId = "lower_bitrate",
                actionSourceResultId = "doctor-v2-needs_action-network_jitter",
                actionContractTyped = true,
                targetBitrateKbps = 16000,
                targetBitratePresent = true,
                targetBitrateTyped = true,
                verificationDelaySeconds = 8,
                verificationMode = "live_telemetry",
                verificationEndpoint = "/api/doctor/action",
                undoSupported = true,
                undoEndpoint = "/api/doctor/action",
                requiresOwner = true,
                evidenceItems = listOf(
                    PolarisSessionStatus.DoctorStatus.EvidenceItem(
                        id = "packet_loss",
                        status = "fail",
                        source = "media_transport",
                        value = 3.4
                    )
                ),
                packetLossPct = 3.4,
                latencyMs = 12.0
            )
        )
        val fresh = quickState(status = runnable)
        assertTrue("the fresh reading runs its action", fresh.diagnosis.actionExecutable)
        assertEquals("Auto Fix", fresh.diagnosis.actionLabel)

        val kept = quickState(status = null, lastStatus = runnable, hostStateUnavailable = true)
        assertTrue(kept.diagnosis.stale)
        assertEquals("the same reading", "Wi-Fi jitter is the likely bottleneck.", kept.diagnosis.likelyCause)
        assertFalse("A runs nothing on a kept reading", kept.diagnosis.actionExecutable)
        assertEquals("A copies its details", NovaQuickMenuDoctorCapability.MANUAL, kept.diagnosis.capability)
    }

    @Test
    fun deterministicFallbackIsDisplayedAsAnInformationalSource() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    classification = "HOST",
                    likelyCause = "Frame pacing is uneven.",
                    confidence = "deterministic-fallback",
                    primaryIssue = "frame_pacing",
                    explanationSourceKind = "deterministic-fallback",
                    explanationSourceMode = "openai-subscription",
                    explanationInformational = true
                )
            )
        )

        assertEquals("Deterministic fallback · openai-subscription", state.diagnosis.informationalSource)
        assertFalse(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.MANUAL, state.diagnosis.capability)
    }

    @Test
    fun aiExplanationStaysSecondaryToTheDeterministicAction() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-v2-needs_action-network_jitter",
                    classification = "NET",
                    likelyCause = "Confirmed media loss is limiting the stream.",
                    primaryIssue = "network_jitter",
                    actionId = "lower_bitrate",
                    actionLabel = "Auto Fix",
                    actionCapability = "auto_fix",
                    actionKind = "live_tuning",
                    actionEndpoint = "/api/doctor/action",
                    actionMethod = "POST",
                    actionPayloadId = "lower_bitrate",
                    actionSourceResultId = "doctor-v2-needs_action-network_jitter",
                    actionContractTyped = true,
                    targetBitrateKbps = 16000,
                    targetBitratePresent = true,
                    targetBitrateTyped = true,
                    verificationDelaySeconds = 8,
                    verificationMode = "live_telemetry",
                    verificationEndpoint = "/api/doctor/action",
                    undoSupported = true,
                    undoEndpoint = "/api/doctor/action",
                    requiresOwner = true,
                    evidenceItems = listOf(
                        PolarisSessionStatus.DoctorStatus.EvidenceItem(
                            id = "packet_loss",
                            status = "fail",
                            source = "media_transport",
                            value = 3.4
                        )
                    ),
                    aiExplanation = PolarisSessionStatus.DoctorStatus.AiExplanation(
                        available = true,
                        likelyCause = "Wi-Fi interference is the likely reason.",
                        tryFirst = listOf("Move closer to the access point"),
                        sourceMode = "openai-subscription",
                        informational = true
                    )
                )
            )
        )

        assertEquals("Confirmed media loss is limiting the stream.", state.diagnosis.likelyCause)
        assertEquals("Auto Fix", state.diagnosisAction.label)
        assertEquals(NovaQuickMenuDoctorCapability.AUTO_FIX, state.diagnosis.capability)
        assertTrue(state.diagnosis.aiExplanation.contains("Wi-Fi interference"))
        assertTrue(state.diagnosis.aiExplanation.contains("Move closer"))
        assertEquals("AI explanation only · openai-subscription", state.diagnosis.informationalSource)
    }

    @Test
    fun commandCenterDisablesDiagnoseThisStreamForMoonlightFallbackSession() {
        val state = quickState(status = null, apiAvailable = false)
        val diagnose = state.diagnosisAction

        assertFalse(diagnose.enabled)
        assertEquals("N/A", diagnose.chip!!.label)
        assertEquals("before a reading it is checking, as the strip is", "Checking session health", diagnose.caption)
        assertEquals(NovaQuickMenuDoctorCapability.MANUAL, state.diagnosis.capability)
    }

    @Test
    fun networkObservationOnlyOffersAReadOnlyRecheck() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-v2-network-observation",
                    classification = "NET",
                    likelyCause = "A network warning needs more live evidence before Doctor changes quality.",
                    primaryIssue = "network_observation",
                    actionId = "recheck_network",
                    actionLabel = "Recheck network",
                    actionCapability = "recheck",
                    actionKind = "verification",
                    actionEndpoint = "/api/doctor/action",
                    actionMethod = "POST",
                    actionPayloadId = "recheck_network",
                    actionSourceResultId = "doctor-v2-network-observation",
                    actionContractTyped = true,
                    verificationDelaySeconds = 3,
                    verificationMode = "live_telemetry",
                    verificationEndpoint = "/api/doctor/action",
                    requiresOwner = true,
                    packetLossPct = 0.4,
                    latencyMs = 20.0
                )
            )
        )

        assertEquals("Recheck network", state.diagnosisAction.label)
        assertTrue(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.RECHECK, state.diagnosis.capability)
        assertEquals(0, state.diagnosis.targetBitrateKbps)
    }

    @Test
    fun staleNetworkLabelCannotExecuteBitrateReductionWithoutLiveEvidence() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    classification = "NET",
                    likelyCause = "Old network warning",
                    primaryIssue = "network_jitter",
                    actionId = "lower_bitrate",
                    actionLabel = "Auto Fix",
                    actionCapability = "auto_fix",
                    actionKind = "live_tuning",
                    targetBitrateKbps = 7580,
                    packetLossPct = 0.0,
                    latencyMs = 3.8
                )
            )
        )

        assertFalse(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.MANUAL, state.diagnosis.capability)
        assertEquals("Diagnose This Stream", state.diagnosisAction.label)
    }

    @Test
    fun dormantRunTrialCapabilityIsPublishedAsManualUntilAnExecutableContractExists() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-v2-trial-dormant",
                    classification = "HOST",
                    likelyCause = "A one-shot cadence trial might distinguish the cause.",
                    primaryIssue = "frame_pacing",
                    actionId = "run_trial",
                    actionLabel = "Run a trial",
                    actionCapability = "run_trial",
                    actionKind = "fresh_launch_trial"
                )
            )
        )

        assertFalse(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.MANUAL, state.diagnosis.capability)
        assertEquals("Diagnose This Stream", state.diagnosisAction.label)
    }

    @Test
    fun cleanLiveReductionOffersOneClickQualityRestoreWithinLaunchCeiling() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-v2-needs_action-quality_reduced_live-gpu_native",
                    classification = "HOST",
                    likelyCause = "The reversible live target is below the capability-validated launch ceiling.",
                    primaryIssue = "quality_reduced_live",
                    actionId = "restore_quality",
                    actionLabel = "Auto Fix",
                    actionCapability = "auto_fix",
                    actionKind = "live_tuning",
                    actionEndpoint = "/api/doctor/action",
                    actionMethod = "POST",
                    actionPayloadId = "restore_quality",
                    actionSourceResultId = "doctor-v2-needs_action-quality_reduced_live-gpu_native",
                    actionContractTyped = true,
                    targetBitrateKbps = 15000,
                    targetBitratePresent = true,
                    targetBitrateTyped = true,
                    verificationDelaySeconds = 8,
                    verificationMode = "graduated_live_telemetry",
                    verificationEndpoint = "/api/doctor/action",
                    undoSupported = true,
                    undoEndpoint = "/api/doctor/action",
                    requiresOwner = true,
                    evidenceItems = listOf(
                        PolarisSessionStatus.DoctorStatus.EvidenceItem(
                            id = "effective_quality_ceiling",
                            status = "watch",
                            source = "launch_policy",
                            value = 15000.0
                        ),
                        PolarisSessionStatus.DoctorStatus.EvidenceItem(
                            id = "packet_loss",
                            status = "pass",
                            source = "media_transport",
                            value = 0.0
                        ),
                        PolarisSessionStatus.DoctorStatus.EvidenceItem(
                            id = "latency",
                            status = "pass",
                            source = "stream_stats",
                            value = 3.8
                        )
                    ),
                    packetLossPct = 0.0,
                    latencyMs = 3.8
                )
            )
        )

        assertEquals("Auto Fix", state.diagnosisAction.label)
        assertTrue(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.AUTO_FIX, state.diagnosis.capability)
        assertEquals(15000, state.diagnosis.targetBitrateKbps)
        assertTrue(state.diagnosis.undoSupported)
    }

    @Test
    fun exactLegacyNextLaunchRecoveryIsPresentedAsNonExecutableManualGuidance() {
        val state = quickState(
            status = status(
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-v2-watch-frame_pacing-safe-profile",
                    classification = "HOST",
                    likelyCause = "Frame pacing is uneven.",
                    primaryIssue = "frame_pacing",
                    actionId = "apply_recovery_profile_next_launch",
                    actionLabel = "Use safer profile next launch",
                    actionKind = "next_launch_profile",
                    actionAppUuid = "game-1",
                    undoSupported = true,
                    requiresConfirmation = true,
                    ownerTuningAllowed = true,
                    pairedEndpoint = "/polaris/v1/doctor/action",
                    undoPairedEndpoint = "/polaris/v1/doctor/action"
                )
            )
        )

        assertFalse(state.diagnosis.actionExecutable)
        assertEquals(NovaQuickMenuDoctorCapability.MANUAL, state.diagnosis.capability)
        assertEquals("Diagnose This Stream", state.diagnosisAction.label)
    }

    @Test
    fun aDisplayModeOverrideReachesTheDoctorCardUnderAHealthyVerdict() {
        val override = "Last launch: This client's pairing has a Display Mode Override of 2560x1440x120, so Polaris used " +
            "that instead of the 1920x1080x60 the client asked for. Clear Display Mode Override for this client on the " +
            "Devices page to let the client choose."
        fun healthyWith(vararg items: PolarisSessionStatus.DoctorStatus.EvidenceItem) = status(
            doctor = PolarisSessionStatus.DoctorStatus(
                available = true,
                version = 2,
                resultId = "doctor-green-display-mode",
                status = "ok",
                severity = "info",
                trafficLight = "green",
                evidenceItems = listOf(
                    PolarisSessionStatus.DoctorStatus.EvidenceItem(id = "streaming", status = "pass", source = "stream_stats"),
                ) + items
            )
        )

        // Polaris grades the decision watch only when the override replaced what the
        // client asked for: that is why the player cannot choose their mode, and the
        // detail says where to clear it, so a green verdict must not hide it.
        val replaced = healthyWith(
            PolarisSessionStatus.DoctorStatus.EvidenceItem(
                id = "display_mode_decision",
                status = "watch",
                source = "launch",
                detail = override
            )
        )
        assertTrue(replaced.authoritativeDoctorVerdictIsHealthy)
        assertTrue(replaced.hasActionableDoctorEvidence)
        assertEquals(override, quickState(status = replaced).diagnosis.evidenceHighlight)

        // An override that matches the request, or none at all, is graded info: nothing to say.
        val matched = healthyWith(
            PolarisSessionStatus.DoctorStatus.EvidenceItem(
                id = "display_mode_decision",
                status = "info",
                source = "launch",
                detail = "Polaris used the display mode the client asked for."
            )
        )
        assertFalse(matched.hasActionableDoctorEvidence)
        assertEquals("", quickState(status = matched).diagnosis.evidenceHighlight)

        // Other informational watches under a green verdict stay informational.
        val capabilityWatch = healthyWith(
            PolarisSessionStatus.DoctorStatus.EvidenceItem(id = "live_bitrate_retune", status = "watch", source = "encoder")
        )
        assertFalse(capabilityWatch.hasActionableDoctorEvidence)
    }

    @Test
    fun doctorCardHidesThePassingStreamCheckAndTheStreamCardDoesNotRepeatTheVerdict() {
        val state = quickState(
            status = status(
                aiOptimizerEnabled = true,
                tuning = PolarisSessionStatus.TuningStatus(aiOptimizerEnabled = true),
                doctor = PolarisSessionStatus.DoctorStatus(
                    available = true,
                    version = 2,
                    resultId = "doctor-current-frame-pacing",
                    classification = "HOST",
                    likelyCause = "Frame pacing telemetry needs attention.",
                    evidence = listOf("A stream is active."),
                    confidence = "high",
                    primaryIssue = "frame_pacing",
                    evidenceItems = listOf(
                        PolarisSessionStatus.DoctorStatus.EvidenceItem(
                            id = "streaming",
                            status = "pass",
                            source = "stream_stats"
                        )
                    )
                )
            )
        )

        // The host lists its passing "a stream is active" check first; that is not evidence
        // of anything, so the card shows no evidence line rather than a vacuous one.
        assertEquals("", state.diagnosis.evidenceHighlight)
        assertEquals("Frame pacing telemetry needs attention.", state.diagnosis.likelyCause)
        assertEquals("Frame pacing", state.healthSummary)
        // The strip and the Doctor card already carry the sentence; the Stream card has its
        // chip and target line and no caption of its own.
        assertEquals("Stream", state.stability.title)
        assertEquals("Launch Preset", state.stability.profileTitle)
    }

    @Test
    fun overlaysPickTheHudModeDirectlyAndOnlyWhileTheHudIsShowing() {
        val showing = quickState(status = status(), hudShowing = true, hudMode = NovaHudMode.DEBUG)
        val hidden = quickState(status = status(), hudShowing = false, hudMode = NovaHudMode.PERFORMANCE)

        // Every layout is one tap away, smallest first. This was a row that cycled blind.
        assertEquals(listOf("Slim", "Minimal", "Performance", "Debug"), showing.hudMode.options.map { it.label })
        assertEquals(listOf("slim", "minimal", "performance", "debug"), showing.hudMode.options.map { it.value })
        assertEquals(NovaHudMode.DEBUG, showing.hudMode.selected)
        assertEquals(listOf(false, false, false, true), showing.hudMode.options.map { it.selected })
        assertTrue(showing.hudMode.enabled)
        assertTrue(showing.hudMode.options.all { it.enabled })
        assertTrue(showing.overlayRows.none { it.label == "HUD Mode" })

        assertEquals(NovaHudMode.PERFORMANCE, hidden.hudMode.selected)
        assertFalse(hidden.hudMode.enabled)
        assertTrue(hidden.hudMode.options.none { it.enabled })

        val hudRow = showing.overlayRows.first { it.id == NovaQuickMenuActionId.NOVA_HUD }
        assertEquals("Long press the HUD to open Command Center.", hudRow.caption)
        val statsRow = showing.overlayRows.first { it.id == NovaQuickMenuActionId.PERF_STATS }
        assertTrue(statsRow.caption.contains("Legacy Moonlight"))
    }

    @Test
    fun overlayRowsExposePrivacySafeHudDiagnosticCopy() {
        val state = quickState(status = status(), currentGameName = "Portal")
        val diagnostics = state.overlayRows.first { it.id == NovaQuickMenuActionId.COPY_HUD_DIAGNOSTICS }

        assertEquals("Copy HUD Diagnostics", diagnostics.label)
        assertEquals("Privacy-safe stream summary for bug reports.", diagnostics.caption)
        // Privacy-safe is said in the caption; a chip says only a state, and Safe was a category.
        assertEquals(null, diagnostics.chip)
    }

    /**
     * N26: the trailing chips meant a state, a category or an action from row to row. Each says a
     * state now, and Paste and Rotate say what they do.
     */
    @Test
    fun commandCenterChipsSayAStateAndEveryActionSaysWhatItDoes() {
        val collapsed = quickState(status = status(), advancedExpanded = false)
        val expanded = quickState(status = status(), advancedExpanded = true)

        assertEquals(null, collapsed.overlayRows.first { it.id == NovaQuickMenuActionId.COPY_HUD_DIAGNOSTICS }.chip)
        assertEquals("Reassign is what the row does, not a state", null, collapsed.controlRows.first { it.id == NovaQuickMenuActionId.PLAYERS }.chip)
        assertEquals("Hidden", collapsed.controlRows.first { it.id == NovaQuickMenuActionId.KEYBOARD }.chip?.label)
        assertEquals("Hidden", collapsed.advancedToggle.chip?.label)
        assertEquals("Shown", expanded.advancedToggle.chip?.label)
        assertEquals(
            "Copies this device's clipboard to the host.",
            collapsed.sessionRows.first { it.id == NovaQuickMenuActionId.PASTE_CLIPBOARD }.caption,
        )
        assertEquals(
            "Closes Command Center and turns the stream between landscape and portrait.",
            collapsed.sessionRows.first { it.id == NovaQuickMenuActionId.ROTATE_SCREEN }.caption,
        )
    }

    @Test
    fun commandCenterStateExposesHudOpacityPresets() {
        val state = quickState(
            status = status(),
            hudShowing = true,
            hudOpacityPercent = 90
        )

        assertEquals(90, state.hudOpacity.percent)
        assertEquals(listOf(0, 25, 64, 90, 100), state.hudOpacity.presets)
        assertTrue(state.hudOpacity.enabled)
    }

    @Test
    fun commandCenterStateDisablesHudOpacityWhenHudIsOff() {
        val state = quickState(
            status = status(),
            hudShowing = false,
            hudOpacityPercent = 150
        )

        assertFalse(state.hudOpacity.enabled)
        assertEquals(100, state.hudOpacity.percent)
    }

    @Test
    fun commandCenterStateKeepsNonPresetHudOpacityValues() {
        val state = quickState(
            status = status(),
            hudShowing = true,
            hudOpacityPercent = 87
        )

        assertTrue(state.hudOpacity.enabled)
        assertEquals(87, state.hudOpacity.percent)
        assertEquals(NovaHudPreferences.OPACITY_PRESETS, state.hudOpacity.presets)
    }

    @Test
    fun commandCenterStateExposesMenuOpacityIndependentlyFromHud() {
        val state = quickState(
            status = status(),
            hudShowing = false,
            hudOpacityPercent = 25,
            menuOpacityPercent = 64
        )

        assertFalse(state.hudOpacity.enabled)
        assertEquals(64, state.menuOpacity.percent)
        assertEquals(listOf(0, 25, 64, 90, 100), state.menuOpacity.presets)
    }

    @Test
    fun durableDoctorReceiptStaysVisibleWithUndoAfterCommandCenterReopen() {
        val receipt = DoctorActionReceipt(
            scopeId = "scope-a",
            runId = "doctor-run-1",
            state = "resolved",
            message = "Doctor verified that network pressure cleared.",
            undoAvailable = true,
            undoActionId = "undo"
        )

        val state = quickState(status = status(), doctorReceipt = receipt)

        assertTrue(state.doctorReceiptAction.visible)
        assertTrue(state.doctorReceiptAction.enabled)
        assertEquals(NovaQuickMenuActionId.DOCTOR_UNDO, state.doctorReceiptAction.id)
        assertEquals("Verified", state.doctorReceiptAction.chip?.label)
        assertTrue(state.doctorReceiptAction.caption.contains("restore", ignoreCase = true))
    }

    @Test
    fun unconfirmedDoctorRollbackStaysVisibleAsNeedsAttention() {
        val receipt = DoctorActionReceipt(
            scopeId = "scope-a",
            runId = "doctor-run-1",
            state = "rollback_unconfirmed",
            message = "The encoder did not confirm that the prior bitrate was restored.",
            undoAvailable = false,
            undoActionId = ""
        )

        val state = quickState(status = status(), doctorReceipt = receipt)

        assertTrue(state.doctorReceiptAction.visible)
        assertFalse(state.doctorReceiptAction.enabled)
        assertEquals("Needs attention", state.doctorReceiptAction.chip?.label)
        assertEquals(NovaQuickMenuTone.WARNING, state.doctorReceiptAction.chip?.tone)
        assertTrue(state.doctorReceiptAction.caption.contains("did not confirm"))
    }

    @Test
    fun recoveryUndoCopyPromisesOnlyQueuedProfileRemoval() {
        val receipt = DoctorActionReceipt(
            scopeId = "scope-a",
            runId = "recovery-run-1",
            state = "queued",
            message = "Safer profile queued.",
            undoAvailable = true,
            undoActionId = "undo_recovery_profile_next_launch",
            appUuid = "game-1"
        )

        val state = quickState(status = status(), doctorReceipt = receipt)

        assertTrue(state.doctorReceiptAction.caption.contains("removes only this deprecated record"))
        assertTrue(state.doctorReceiptAction.caption.contains("stream and launch settings remain unchanged"))
        assertFalse(state.doctorReceiptAction.caption.contains("restore the previous bitrate", ignoreCase = true))
    }

    @Test
    fun pairedOwnerCanCancelLegacyRecoveryWithoutOwningTheActiveStream() {
        val receipt = DoctorActionReceipt(
            scopeId = "scope-a",
            runId = "recovery-run-1",
            state = "queued",
            message = "Deprecated profile queued.",
            undoAvailable = true,
            undoActionId = "undo",
            appUuid = "game-1"
        )
        val viewerStatus = status(
            clientRole = "viewer",
            ownedByClient = false,
            controls = PolarisSessionStatus.ControlsStatus(hostTuningAllowed = false)
        )

        val state = quickState(status = viewerStatus, doctorReceipt = receipt)

        assertTrue(state.doctorReceiptAction.visible)
        assertTrue(state.doctorReceiptAction.enabled)
    }

    @Test
    fun durableDoctorUndoRequiresHostActionIdAndCurrentTuningPermission() {
        val receipt = DoctorActionReceipt(
            scopeId = "scope-a",
            runId = "doctor-run-1",
            state = "resolved",
            message = "Verified",
            undoAvailable = true,
            undoActionId = ""
        )

        val missingAction = quickState(status = status(), doctorReceipt = receipt)
        val viewer = quickState(
            status = status(
                clientRole = "viewer",
                ownedByClient = false,
                controls = PolarisSessionStatus.ControlsStatus(hostTuningAllowed = false)
            ),
            doctorReceipt = receipt.copy(undoActionId = "restore_quality")
        )

        assertTrue(missingAction.doctorReceiptAction.visible)
        assertFalse(missingAction.doctorReceiptAction.enabled)
        assertTrue(viewer.doctorReceiptAction.visible)
        assertFalse(viewer.doctorReceiptAction.enabled)
    }

    @Test
    fun commandCenterStateClampsNonPresetMenuOpacityValues() {
        val state = quickState(status = status(), menuOpacityPercent = 150)

        assertEquals(100, state.menuOpacity.percent)
        assertEquals(NovaMenuPreferences.OPACITY_PRESETS, state.menuOpacity.presets)
    }

    /**
     * The Command Center's Live Tuning caption says what it is doing and the bitrate it applied,
     * in the player's words. It read "Live Tuning On, stable. Host setting. 20 Mbps applied /
     * 20 Mbps limit": the title twice, and a slash. What it changes goes under its split now.
     */
    @Test
    fun liveTuningSaysWhatItIsDoingInPlainWords() {
        val fixtures = org.json.JSONArray(javaClass.getResource("/live-tuning-v1.json")!!.readText())
        fun live(name: String) = (0 until fixtures.length()).map { fixtures.getJSONObject(it) }
            .first { it.getString("name") == name }
            .let { com.papi.nova.api.LiveTuningStatus.parse(it.getJSONObject("live_tuning"))!! }

        val stable = quickState(status = status().copy(liveTuning = live("stable"), liveTuningPresent = true))
        assertEquals("Live Tuning", stable.liveTuningAction.label)
        assertEquals("On", stable.liveTuningAction.chip?.label)
        assertEquals("Steady. 20 Mbps applied, 20 Mbps limit.", stable.liveTuningAction.caption)

        val off = quickState(status = status().copy(liveTuning = live("off"), liveTuningPresent = true))
        assertEquals("Off", off.liveTuningAction.chip?.label)
        assertEquals("The bitrate stays where the stream started.", off.liveTuningAction.caption)
    }

    /**
     * N28 (rest) and review finding 1: a reading with nothing to run informs, and reads quieter in
     * the card's one place under the strip, only while the strip does not warn. "Control-channel
     * retries, but no confirmed loss" is one the strip warns about, and the card said "Nothing to
     * fix" under "Needs attention"; it now says what the strip says (NovaCommandCenterDoctorCard
     * ComposeTest reads the words).
     */
    @Test
    fun aReadingInformsOnlyWhileTheStripDoesNotWarn() {
        fun verdict(primaryIssue: String, severity: String, light: String, cause: String) = PolarisSessionStatus.DoctorStatus(
            available = true,
            version = 2,
            resultId = "doctor-$primaryIssue",
            status = if (severity == "info") "ok" else "watch",
            severity = severity,
            trafficLight = light,
            likelyCause = cause,
            primaryIssue = primaryIssue,
        )
        val healthy = quickState(status = status(doctor = verdict("none", "info", "green", "Streaming telemetry looks ready")))
        val observation = quickState(
            status = status(
                doctor = verdict(
                    "control_channel_observation",
                    "warning",
                    "amber",
                    "Control-channel retries were observed, but video packet loss is not confirmed",
                ),
            ),
        )
        val hostRender = quickState(
            status = status(doctor = verdict("host_render_limited", "warning", "amber", "Host is rendering below the stream target")),
        )

        assertTrue("a healthy reading with nothing to run informs", healthy.diagnosis.informational)
        assertEquals("the strip warns about this observation", NovaQuickMenuTone.WARNING, observation.healthTone)
        assertFalse("so the card does not say it only informs", observation.diagnosis.informational)
        assertFalse("a reading the strip warns about explains it at full strength", hostRender.diagnosis.informational)
    }

    @Test
    fun pendingLiveTuningSaveKeepsTheRowUnderTheCursor() {
        // Disabling the row while its save was pending dropped controller focus mid-press,
        // and the Command Center washed white until it closed (#296). The caption says
        // Saving and onLiveTuning ignores a second press; the row itself stays enabled.
        val state = quickState(status = status(), liveTuningPending = true)

        assertEquals("Saving…", state.liveTuningAction.caption)
        assertTrue(state.liveTuningAction.enabled)
    }

    /**
     * Review finding 5: a Live Tuning save the host did not confirm floated an error snackbar, and
     * a Launch Preset pick floated "Launch preset saved for next launch". Each is its row's caption.
     * Live Tuning's says the state the host reports now, as its chip does, and never "Try again",
     * which from that state would undo the change; it is announced, and a failed refresh of the
     * host's state does not hide it behind Reconnecting.
     */
    @Test
    fun liveTuningAndLaunchPresetSayTheirResultsOnTheirRows() {
        val fixtures = org.json.JSONArray(javaClass.getResource("/live-tuning-v1.json")!!.readText())
        fun live(name: String) = (0 until fixtures.length()).map { fixtures.getJSONObject(it) }
            .first { it.getString("name") == name }
            .let { com.papi.nova.api.LiveTuningStatus.parse(it.getJSONObject("live_tuning"))!! }
        val on = status().copy(liveTuning = live("stable"), liveTuningPresent = true)
        val off = status().copy(liveTuning = live("off"), liveTuningPresent = true)

        val kept = quickState(status = on, liveTuningUnconfirmed = false)
        assertEquals("asked for Off, the host kept On", "The host kept Live Tuning On.", kept.liveTuningAction.caption)
        assertEquals("as its chip says", "On", kept.liveTuningAction.chip?.label)
        assertTrue("said to TalkBack", kept.liveTuningAction.announce)
        assertEquals("The host kept Live Tuning Off.", quickState(status = off, liveTuningUnconfirmed = true).liveTuningAction.caption)

        val applied = quickState(status = off, liveTuningUnconfirmed = false)
        assertEquals("applied with its answer lost: nothing went wrong", "The bitrate stays where the stream started.", applied.liveTuningAction.caption)
        assertFalse(applied.liveTuningAction.announce)

        val unanswered = quickState(status = on, hostStateUnavailable = true, liveTuningUnconfirmed = false)
        assertEquals("a failed refresh still says the save failed", "The host did not answer, so the change is not confirmed.", unanswered.liveTuningAction.caption)
        assertEquals("Unknown", unanswered.liveTuningAction.chip?.label)
        assertTrue("and its row stays enabled, so focus stays on it", unanswered.liveTuningAction.enabled)
        assertFalse(
            "a host it cannot read, with no result to show, leaves the row disabled",
            quickState(status = on, hostStateUnavailable = true).liveTuningAction.enabled,
        )

        val again = quickState(status = on, liveTuningPending = true, liveTuningUnconfirmed = false)
        assertEquals("a new save says Saving", "Saving…", again.liveTuningAction.caption)
        assertFalse(again.liveTuningAction.announce)

        assertEquals("Applies next launch for Portal", quickState(status = status()).stability.profileCaption)
        assertEquals("Saved. Applies next launch for Portal", quickState(status = status(), launchPresetSaved = true).stability.profileCaption)
    }

    @Test
    fun aSpaceShowsItsVerdictInTheCardAndCallsItsBitrateFixed() {
        // What Polaris sends for a Space (nvhttp.cpp profile_session_status): a health summary,
        // no Doctor object, and live_tuning null.
        val space = com.papi.nova.api.PolarisApiClient.parseSessionStatusResponse(
            org.json.JSONObject(
                """{"source":"worker_profile_v1","state":"streaming","streaming_active":true,
                "owned_by_client":true,"client_role":"owner","viewer_count":0,"game":"papi - heroic",
                "controls":{"host_tuning_allowed":false,"quit_allowed":true,"stop_allowed":true},
                "display_mode":{"selection":"gamescope_stream","label":"papi - heroic"},
                "encoder":{"codec":"h264","bitrate_kbps":0,"bitrate_ceiling_kbps":8000,"session_target_fps":120},
                "health":{"grade":"unknown","summary":"Profile performance diagnostics are not available yet."},
                "live_tuning":null}"""
            )
        )
        val state = quickState(status = space, currentGameName = "papi - heroic")

        assertEquals("Profile performance diagnostics are not available yet.", state.healthSummary)
        // Review finding 1: hiding the card for a Space moved every row under it as a Space's
        // first answer arrived. The card keeps its place and shows the Space's own verdict.
        assertTrue("the Doctor card shows the Space's verdict", state.diagnosis.visible)
        assertEquals("Profile performance diagnostics are not available yet.", state.diagnosis.likelyCause)
        assertEquals("Fixed", state.liveTuningAction.chip?.label)
        assertEquals("This Space uses the bitrate selected when the stream starts.", state.liveTuningAction.caption)
    }

    @Test
    fun aDoctorReadingKeepsItsCard() {
        val state = quickState(
            status = status(doctor = PolarisSessionStatus.DoctorStatus(likelyCause = "Network jitter is dropping frames"))
        )

        assertTrue(state.diagnosis.visible)
        assertEquals("Network jitter is dropping frames", state.diagnosis.likelyCause)
    }

    @Test
    fun aDoctorReadingThatMatchesTheStripStillShowsItsEvidence() {
        // Only a bare summary is a repeat. A reading that names the same cause and brings
        // evidence has something the strip does not say.
        val state = quickState(
            status = status(
                health = PolarisSessionStatus.HealthStatus(grade = "good", summary = "Network jitter is dropping frames."),
                doctor = PolarisSessionStatus.DoctorStatus(
                    likelyCause = "Network jitter is dropping frames",
                    evidence = listOf("Loss 4% over the last 10 s")
                )
            )
        )

        assertEquals("Network jitter is dropping frames.", state.healthSummary)
        assertTrue(state.diagnosis.visible)
    }

    private fun quickState(
        status: PolarisSessionStatus?,
        apiAvailable: Boolean = true,
        liveTuningPending: Boolean = false,
        liveTuningUnconfirmed: Boolean? = null,
        hostStateUnavailable: Boolean = false,
        launchPresetSaved: Boolean = false,
        adaptiveSupported: Boolean = true,
        aiSupported: Boolean = true,
        adaptiveEnabled: Boolean = false,
        aiEnabled: Boolean = false,
        mangoHudEnabled: Boolean = false,
        stabilityApplied: Boolean = false,
        advancedExpanded: Boolean = true,
        profileClearInProgress: Boolean = false,
        currentGameName: String? = "Portal",
        currentGameUuid: String? = "game-1",
        hudShowing: Boolean = false,
        hudMode: NovaHudMode = NovaHudMode.MINIMAL,
        hudOpacityPercent: Int = 90,
        menuOpacityPercent: Int = NovaMenuPreferences.DEFAULT_OPACITY_PERCENT,
        fallbackTargetFps: Double = 60.0,
        doctorReceipt: DoctorActionReceipt? = null,
        lastStatus: PolarisSessionStatus? = null,
        context: Context = this.context,
    ) = NovaQuickMenuUiState.from(
        context = context,
        status = status,
        lastStatus = lastStatus,
        apiAvailable = apiAvailable,
        liveTuningPending = liveTuningPending,
        liveTuningUnconfirmed = liveTuningUnconfirmed,
        hostStateUnavailable = hostStateUnavailable,
        launchPresetSaved = launchPresetSaved,
        adaptiveSupported = adaptiveSupported,
        aiSupported = aiSupported,
        adaptiveEnabled = adaptiveEnabled,
        aiEnabled = aiEnabled,
        mangoHudEnabled = mangoHudEnabled,
        stabilityApplied = stabilityApplied,
        advancedExpanded = advancedExpanded,
        profileClearInProgress = profileClearInProgress,
        currentGameName = currentGameName,
        currentGameUuid = currentGameUuid,
        profilePreference = "quality",
        hudShowing = hudShowing,
        hudMode = hudMode,
        hudOpacityPercent = hudOpacityPercent,
        menuOpacityPercent = menuOpacityPercent,
        perfOverlayEnabled = false,
        onscreenControllerEnabled = false,
        keyboardVisible = false,
        mouseModeLabel = "Direct",
        allowChangeMouseMode = true,
        isOnExternalDisplay = false,
        fallbackBitrateKbps = 50000,
        fallbackTargetFps = fallbackTargetFps,
        doctorReceipt = doctorReceipt
    )

    private fun status(
        state: String = "streaming",
        clientRole: String = "owner",
        ownedByClient: Boolean = true,
        aiOptimizerEnabled: Boolean = false,
        controls: PolarisSessionStatus.ControlsStatus = PolarisSessionStatus.ControlsStatus(
            hostTuningAllowed = true,
            quitAllowed = true
        ),
        tuning: PolarisSessionStatus.TuningStatus = PolarisSessionStatus.TuningStatus(),
        displayMode: PolarisSessionStatus.DisplayModeStatus = PolarisSessionStatus.DisplayModeStatus(
            effectiveHeadless = true,
            requested = "headless"
        ),
        clientPresentation: PolarisSessionStatus.ClientPresentationStatus = PolarisSessionStatus.ClientPresentationStatus(),
        syncStatus: PolarisSessionStatus.SyncStatus = PolarisSessionStatus.SyncStatus(
            available = true,
            state = "synced"
        ),
        autoQuality: PolarisSessionStatus.AutoQualityPolicy = PolarisSessionStatus.AutoQualityPolicy(),
        health: PolarisSessionStatus.HealthStatus = PolarisSessionStatus.HealthStatus(grade = "good"),
        doctor: PolarisSessionStatus.DoctorStatus = PolarisSessionStatus.DoctorStatus(),
        encoder: PolarisSessionStatus.EncoderStatus = PolarisSessionStatus.EncoderStatus(),
        capture: PolarisSessionStatus.CaptureStatus = PolarisSessionStatus.CaptureStatus(),
        linuxGpuProfile: PolarisSessionStatus.LinuxGpuProfile? = null
    ): PolarisSessionStatus {
        val scopedActionIds = setOf(
            "lower_bitrate",
            "restore_quality",
            "recheck_network",
            "recheck_pacing"
        )
        val scopedDoctor = if (doctor.actionId in scopedActionIds) {
            doctor.copy(
                actionAppSessionId = doctor.actionAppSessionId.ifBlank { "app-session-1" },
                actionSessionGeneration = doctor.actionSessionGeneration.takeIf { it > 0L } ?: 41L,
                actionControllerRevision = if (doctor.actionCapability == "auto_fix") {
                    doctor.actionControllerRevision.takeIf { it > 0L } ?: 51L
                } else {
                    doctor.actionControllerRevision
                },
                actionEvidenceRevision = if (doctor.actionCapability == "auto_fix") {
                    doctor.actionEvidenceRevision.takeIf { it > 0L } ?: 61L
                } else {
                    doctor.actionEvidenceRevision
                }
            )
        } else {
            doctor
        }
        return PolarisSessionStatus(
            state = state,
            streamingActive = true,
            game = "Portal",
            gameUuid = "game-1",
            appSessionId = "app-session-1",
            appSessionIdPresent = true,
            sessionGeneration = 41L,
            clientRole = clientRole,
            ownedByClient = ownedByClient,
            controls = controls,
            tuning = tuning,
            displayMode = displayMode,
            clientPresentation = clientPresentation,
            syncStatus = syncStatus,
            autoQuality = autoQuality,
            health = health,
            doctor = scopedDoctor,
            encoder = encoder,
            capture = capture,
            linuxGpuProfile = linuxGpuProfile,
            aiOptimizerEnabled = aiOptimizerEnabled
        )
    }
}
