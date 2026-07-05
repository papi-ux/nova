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
}
