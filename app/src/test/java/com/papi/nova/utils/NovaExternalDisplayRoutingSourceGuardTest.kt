package com.papi.nova.utils

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaExternalDisplayRoutingSourceGuardTest {
    @Test
    fun serverHelperExposesCompanionDisplaySelectionAdapter() {
        val source = File("src/main/java/com/papi/nova/utils/ServerHelper.kt").readText()

        assertTrue(source.contains("AndroidDisplayCandidateMap"))
        assertTrue(source.contains("buildDisplayCandidateMap"))
        assertTrue(source.contains("getAndroidCompanionDisplay"))
        assertTrue(source.contains("selectCompanion"))
    }

    @Test
    fun serverHelperLogsStreamAndCompanionDisplayRoles() {
        val source = File("src/main/java/com/papi/nova/utils/ServerHelper.kt").readText()

        assertTrue(source.contains("Android display role stream"))
        assertTrue(source.contains("Android display role companion"))
    }

    @Test
    fun externalControlReceiverDoesNotHardcodeDefaultDisplay() {
        val source = File("src/main/java/com/papi/nova/StartExternalDisplayControlReceiver.kt").readText()

        assertFalse(
            "External controls must launch on the derived companion display, not always Display.DEFAULT_DISPLAY",
            source.contains("setLaunchDisplayId(Display.DEFAULT_DISPLAY)")
        )
        assertTrue(
            "External controls should resolve through ServerHelper.getAndroidCompanionDisplay",
            source.contains("getAndroidCompanionDisplay")
        )
    }

    @Test
    fun gameDoesNotRequireStreamToBeExternalBeforeStartingCompanionControls() {
        val source = File("src/main/java/com/papi/nova/Game.kt").readText()

        assertFalse(
            "Thor needs controls on the secondary display even when the stream is on the primary display",
            source.contains("prefConfig!!.enableFullExDisplay && isOnExternalDisplay")
        )
        assertTrue(source.contains("shouldLaunchCompanionControls"))
    }

    @Test
    fun streamLaunchUsesDedicatedTrampolineInsteadOfControlActivity() {
        val serverHelper = File("src/main/java/com/papi/nova/utils/ServerHelper.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(serverHelper.contains("GameDisplayLaunchTrampolineActivity"))
        assertTrue(manifest.contains(".utils.GameDisplayLaunchTrampolineActivity"))
        assertFalse(
            "The singleton control activity must not double as the stream launch trampoline",
            serverHelper.contains("ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT")
        )
    }

    @Test
    fun externalControlActivityDoesNotBootstrapGameLaunches() {
        val source = File("src/main/java/com/papi/nova/utils/ExternalDisplayControlActivity.kt").readText()

        assertFalse(
            "The companion control activity should only host controls; stream bootstrapping belongs to the dedicated trampoline",
            source.contains("EXTRA_LAUNCH_INTENT")
        )
        assertFalse(
            "The companion control activity must not launch Game itself from whichever display created it",
            source.contains("startActivity(gameIntent")
        )
    }

    @Test
    fun displayLaunchTrampolineLaunchesGameOnRequestedDisplayIncludingDefault() {
        val source = File("src/main/java/com/papi/nova/utils/GameDisplayLaunchTrampolineActivity.kt").readText()

        assertFalse(
            "The game bootstrap must not remap a requested primary/default stream display to the secondary display",
            source.contains("?.takeIf { it.displayId != Display.DEFAULT_DISPLAY }")
        )
        assertTrue(source.contains("val targetDisplay = displayManager.getDisplay(targetDisplayId)"))
        assertTrue(source.contains("options.setLaunchDisplayId(targetDisplay.displayId)"))
    }

    @Test
    fun gameRelaunchUsesDisplayAwareLauncher() {
        val source = File("src/main/java/com/papi/nova/Game.kt").readText()

        assertTrue(source.contains("GameDisplayLaunchTrampolineActivity.launchGameOnRequestedDisplay"))
        assertFalse(
            "Reconnect/relaunch must not use plain application-context startActivity because it can inherit the wrong display",
            source.contains("getApplicationContext().startActivity(relaunchIntent)")
        )
    }

    @Test
    fun gameDifferentiatesStreamAndCompanionDisplayRemoval() {
        val source = File("src/main/java/com/papi/nova/Game.kt").readText()

        assertTrue(source.contains("companionControlDisplayId"))
        assertTrue(source.contains("removedDisplayId == streamingDisplayId"))
        assertTrue(source.contains("removedDisplayId == companionControlDisplayId"))
        assertTrue(source.contains("unregisterDisplayListener"))
    }

    @Test
    fun gameUsesMinSdkSafeInvalidDisplaySentinel() {
        val source = File("src/main/java/com/papi/nova/Game.kt").readText()

        assertTrue(source.contains("INVALID_DISPLAY_ID"))
        assertFalse(source.contains("Display.INVALID_DISPLAY"))
    }

    @Test
    fun gameCreatesAudioRendererWithStreamDisplayContext() {
        val source = File("src/main/java/com/papi/nova/Game.kt").readText()

        assertTrue(
            "Game should derive a display-scoped audio context from the selected stream display",
            source.contains("private fun getStreamAudioContext(): Context")
        )
        assertTrue(
            "Audio context should be created from the selected stream display, not whichever activity last focused controls",
            source.contains("createDisplayContext(streamingDisplay)")
        )
        assertTrue(
            "Audio renderer must be constructed with the stream display context",
            source.contains("AndroidAudioRenderer(getStreamAudioContext(), prefConfig!!.playHostAudio)")
        )
        assertFalse(
            "Passing the Game activity context lets Android bind audio to the bottom/control display",
            source.contains("AndroidAudioRenderer(this@Game, prefConfig!!.playHostAudio)")
        )
    }

    @Test
    fun gameStartsCompanionControlsAfterStreamConnectionStart() {
        val source = File("src/main/java/com/papi/nova/Game.kt").readText()
        val inputSetupStart = source.indexOf("if (Objects.equals(appUUID, NvApp.REMOTE_INPUT_UUID))")
        val inputSetupEnd = source.indexOf("if (prefConfig!!.onscreenController)", inputSetupStart)
        val surfaceStartIndex = source.indexOf("streamContainer!!.setOnSurfaceAvailable")
        val surfaceEndIndex = source.indexOf("gameMenuCallbacks", surfaceStartIndex)
        val connectionStartedIndex = source.indexOf("override fun connectionStarted()")
        val connectionStartedEnd = source.indexOf("fun handleStreamStartedState()", connectionStartedIndex)
        assertTrue("Game should contain remote-input setup branch", inputSetupStart >= 0)
        assertTrue("Game should contain onscreen-controller setup after remote-input setup", inputSetupEnd > inputSetupStart)
        assertTrue("Game should contain surface-available startup callback", surfaceStartIndex >= 0)
        assertTrue("Game should initialize game menu after surface callback", surfaceEndIndex > surfaceStartIndex)
        assertTrue("Game should contain connectionStarted callback", connectionStartedIndex >= 0)
        assertTrue("Game should define handleStreamStartedState after connectionStarted", connectionStartedEnd > connectionStartedIndex)

        val inputSetup = source.substring(inputSetupStart, inputSetupEnd)
        val surfaceStart = source.substring(surfaceStartIndex, surfaceEndIndex)
        val connectionStarted = source.substring(connectionStartedIndex, connectionStartedEnd)

        assertFalse(
            "Companion controls should not take display focus before audio/stream startup",
            inputSetup.contains("launchCompanionControlsIfAvailable()")
        )
        assertFalse(
            "Surface callback starts NvConnection on a worker thread; companion controls should wait for connectionStarted",
            surfaceStart.contains("launchCompanionControlsIfAvailable()")
        )
        val handleStarted = connectionStarted.indexOf("handleStreamStartedState()")
        val controlsStart = connectionStarted.indexOf("launchCompanionControlsIfAvailable()")
        assertTrue("connectionStarted should mark the stream active", handleStarted >= 0)
        assertTrue("connectionStarted should start companion controls after stream/audio setup", controlsStart >= 0)
        assertTrue(
            "Companion controls should launch after connectionStarted marks the stream active so audio binds to the stream display first",
            controlsStart > handleStarted
        )
    }

    @Test
    fun androidAudioRendererSetsBuilderContextOnAndroid14AndNewer() {
        val source = File("src/main/java/com/papi/nova/binding/audio/AndroidAudioRenderer.kt").readText()

        assertTrue(source.contains("Build.VERSION_CODES.UPSIDE_DOWN_CAKE"))
        assertTrue(
            "AudioTrack.Builder should receive the stream-display Context on API 34+ for display-roleful audio routing",
            source.contains("trackBuilder.setContext(context)")
        )
        assertTrue(source.contains("Android display audio context"))
        assertTrue(source.contains("Android display audio route"))
        assertTrue(source.contains("display_id="))
    }
}
