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
}
