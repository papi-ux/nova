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
    fun theHostPowerButtonOnlyOffersSleepWhereItWillWork() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val policy = File("src/main/java/com/papi/nova/manager/HostPower.kt").readText()

        assertTrue(
            "the button asks the host what it can do instead of assuming; a control that is going to fail is worse than no control",
            pcView.contains("HostPowerPolicy.resolve(")
        )
        assertTrue(
            "three separate gates decide sleep: the host's own answer, its owner's opt in, and this client's permission",
            policy.contains("power.sleepSupported && power.sleepEnabled && power.sleepPermitted")
        )
        assertTrue(
            "a host that does not speak host power at all, Sunshine or an older Polaris, keeps the wake button",
            policy.contains("if (!caps.features.hostSleep)")
        )
    }

    @Test
    fun sleepIsHeldRatherThanTapped() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(
            "wake and sleep share one button in one spot, so a stray tap on the way into the library must not be able to complete sleep",
            pcView.contains("startHostSleepHold(") && pcView.contains("hostSleepHold.isComplete(")
        )
        assertTrue(
            "the undo sits in front of the request, because a host that is already down cannot be woken from the couch",
            pcView.contains("HoldToConfirm.SLEEP_GRACE_MILLIS") && pcView.contains("showPendingWithCancel(")
        )
        assertTrue(
            "a tap on a hold-only control says what to do instead of doing nothing",
            strings.contains("name=\"pcview_sleep_hold_hint\"")
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
