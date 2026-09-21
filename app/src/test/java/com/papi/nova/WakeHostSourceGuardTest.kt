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
            "a host that accepted the request but stayed awake must not leave 'going to sleep' on screen; the host is asked why",
            pcView.contains("val wentDown =") && pcView.contains("lastSleepMessage") &&
                strings.contains("name=\"pcview_sleep_did_not_sleep\"")
        )
        assertTrue(
            "a tap on a hold-only control says what to do instead of doing nothing",
            strings.contains("name=\"pcview_sleep_hold_hint\"")
        )
        assertTrue(
            "the icon follows the label as an open and closed pair; a play arrow next to Sleep Host read as though the button started something",
            pcView.contains("R.drawable.ic_eye_closed") && pcView.contains("R.drawable.ic_eye_open") &&
                File("src/main/res/drawable/ic_eye_closed.xml").exists() &&
                File("src/main/res/drawable/ic_eye_open.xml").exists()
        )
    }

    @Test
    fun sleepHostIsOneRequestAtATimeAndReachableWithoutATouchscreenHold() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val onPause = pcView.substringAfter("override fun onPause() {").substringBefore("override fun onStop()")

        assertTrue(
            "the Cancel for a pending sleep is on this screen, so leaving the screen must call the sleep off rather than let it fire unseen",
            onPause.contains("cancelPendingHostSleep()") && strings.contains("name=\"pcview_sleep_cancelled_on_leave\"")
        )
        assertTrue(
            "a second hold during the countdown or the check must not restart it or send a second request",
            pcView.contains("if (!hostSleepSequence.startCountdown()) {") &&
                pcView.contains("if (!hostSleepSequence.countdownElapsed()) {") &&
                pcView.contains("hostSleepSequence.finish()")
        )
        assertTrue(
            "the button stays on Sleeping... until the host answers, not Wake Host the moment the host drops off",
            pcView.contains("busy -> R.string.pcview_sleep_in_progress") && strings.contains("name=\"pcview_sleep_in_progress\"")
        )
        assertTrue(
            "a controller's A holds the button like the D-pad center does, counted once even when Android adds a fallback press",
            pcView.contains("keyCode != KeyEvent.KEYCODE_BUTTON_A") && pcView.contains("KeyEvent.FLAG_FALLBACK")
        )
        assertTrue(
            "TalkBack cannot perform a timed hold, so Sleep Host is also a named accessibility action",
            pcView.contains("ViewCompat.addAccessibilityAction(") && strings.contains("name=\"pcview_sleep_hold_hint_accessibility\"")
        )
        assertTrue(
            "a held control answers to at least 48dp even where the rail draws it smaller",
            pcView.contains("R.dimen.nova_min_touch_target") &&
                File("src/main/res/values/dimens.xml").readText().contains("<dimen name=\"nova_min_touch_target\">48dp</dimen>")
        )
    }

    @Test
    fun anAwakeHostIsNeverOfferedWaking() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(
            "the control is named for what the machine is doing: every host that did not offer sleep read Wake " +
                "Host while plainly awake, and that was every handheld that plays in a Space",
            pcView.contains("val named = HostPowerPolicy.label(preferredHostIsReachable())") &&
                pcView.contains("named == HostPowerAction.SLEEP -> R.string.pcview_quick_sleep_host")
        )
        assertTrue(
            "a press on an awake host that will not sleep says why, instead of waking what is awake",
            pcView.contains("} else if (preferredHostIsReachable()) {") &&
                pcView.contains("NovaSnackbar.showQuiet(this, hostSleepRefusal())") &&
                strings.contains("name=\"pcview_sleep_unavailable_not_offered\"")
        )
        assertTrue(
            "what a hold may do is still the host's answer: only its yes starts the countdown",
            pcView.contains("if (currentHostPowerAction() == HostPowerAction.SLEEP) {")
        )
    }

    @Test
    fun wakeHostSaysWhySleepIsNotOffered() {
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(
            "holding Wake Host, as Sleep Host is held, explains a host that could sleep but is not offering to",
            pcView.contains("button.setOnLongClickListener {") && pcView.contains("HostPowerPolicy.unavailableReason(")
        )
        assertTrue(
            "the host's own sentence is shown when it gave one; the owner's switch and a watch-only pairing get Nova's",
            strings.contains("name=\"pcview_sleep_unavailable_host_says\"") &&
                strings.contains("name=\"pcview_sleep_unavailable_off\"") &&
                strings.contains("name=\"pcview_sleep_unavailable_watch_only\"")
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
