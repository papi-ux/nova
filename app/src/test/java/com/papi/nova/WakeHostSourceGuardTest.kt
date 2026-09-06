package com.papi.nova

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeHostSourceGuardTest {
    @Test
    fun theLibraryButtonPromisesOnlyWhatAClientCanDo() {
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(
            "a client can send a wake packet and wait; it cannot start Polaris on an awake host, so the button says Wake Host",
            strings.contains("<string name=\"pcview_quick_start_polaris\">Wake Host</string>") &&
                strings.contains("<string name=\"pcview_menu_start_polaris\">Wake Host</string>")
        )
        assertFalse(
            "no surface still calls the action Start Polaris",
            strings.contains(">Start Polaris<")
        )
    }

    @Test
    fun wakeHostTellsASleepingHostFromOneWithPolarisDown() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(
            "the startup coordinator gets a real reachability probe, so an awake host with Polaris down is not woken and waited on for nothing",
            pcView.contains("reachabilityProbe = TcpHostReachabilityProbe()")
        )
        assertTrue(
            "the awake-but-down case gets its own message, naming what to do on the host",
            pcView.contains("PolarisStartupStatus.POLARIS_NOT_RUNNING ->") &&
                strings.contains("name=\"pcview_polaris_start_not_running\"") &&
                strings.contains("enable headless boot")
        )
    }
}
