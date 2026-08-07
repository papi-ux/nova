package com.papi.nova.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultDisplayCompanionActivityGuardTest {
    @Test
    fun activityReattachesAfterLifecycleRestart() {
        val activity = File("src/main/java/com/papi/nova/utils/ExternalDisplayControlActivity.kt").readText()
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val onStart = activity.substringAfter("override fun onStart()").substringBefore("override fun onStop()")
        val attachHelper = activity.substringAfter("private fun attachToOwningGameIfNeeded()")
        val gameAttach =
            game.substringAfter("fun attachExternalDisplayControlActivity")
                .substringBefore("fun detachExternalDisplayControlActivity")

        assertTrue(onStart.contains("attachToOwningGameIfNeeded()"))
        assertTrue(attachHelper.contains("attachExternalDisplayControlActivity(this)"))
        assertTrue(attachHelper.contains("registeredWithGame = true"))
        assertTrue("Game must re-resolve the current companion route after transient detach", gameAttach.contains("getCompanionControlDisplay()"))
        assertTrue("Successful reattachment must restore the resolved companion display ID", gameAttach.contains("companionControlDisplayId = companionDisplayId"))
    }

    @Test
    fun activityKeepsARealFocusedWindowAcrossImeTransitions() {
        val activity = File("src/main/java/com/papi/nova/utils/ExternalDisplayControlActivity.kt").readText()
        val controller = File("src/main/java/com/papi/nova/utils/ExternalDisplayControlController.kt").readText()
        val prepare = activity.substringAfter("override fun prepareForSoftKeyboard()").substringBefore("override fun onSoftKeyboardVisibilityChanged")
        val release = activity.substringAfter("override fun onSoftKeyboardVisibilityChanged").substringBefore("override fun closeFromGame")
        val compactKeyboard = controller.substringAfter("private fun _toggleKeyboard()").substringBefore("private fun initFullKeyboard")

        assertTrue(prepare.contains("window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)"))
        assertFalse(
            "Closing the IME must not leave the default-display companion without a focused window",
            release.contains("window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)"),
        )
        assertFalse(
            "Closing the IME must not move focus through the cross-display task bridge",
            release.contains("requestFocusToGameActivity(false)"),
        )
        assertTrue(compactKeyboard.contains("host.prepareForSoftKeyboard()"))
        assertTrue("The companion input root must accept focus after a touchscreen keyboard tap", controller.contains("rootLayout.isFocusableInTouchMode = true"))
        assertTrue(controller.contains("host.onSoftKeyboardVisibilityChanged("))
        val insetsHandling =
            controller.substringAfter("ViewCompat.setOnApplyWindowInsetsListener")
                .substringBefore("initializeComponents()")
        assertTrue("IME inset callbacks must not release an unaccepted show request", insetsHandling.contains("if (softKeyboardShowAccepted)"))
        assertTrue(controller.contains("WindowInsetsCompat.Type.ime()"))
        assertTrue("IME show must wait for the Activity window-focus callback", controller.contains("softKeyboardShowPending"))
        val focusChanged =
            controller.substringAfter("fun onWindowFocusChanged(hasFocus: Boolean)")
                .substringBefore("fun handleCompanionBack()")
        assertTrue(focusChanged.contains("showPendingSoftKeyboardIfReady()"))
        val focusWait =
            controller.substringAfter("private fun showPendingSoftKeyboardIfReady()")
                .substringBefore("private fun tryShowPendingSoftKeyboard()")
        assertTrue("No-focus waits must consume the bounded retry budget", focusWait.contains("softKeyboardShowAttempts += 1"))
        assertTrue("No-focus waits must retry instead of returning forever", focusWait.contains("handler.postDelayed"))
        assertTrue("Exhausted no-focus waits must terminate the soft-keyboard lease", focusWait.contains("host.releaseSoftKeyboardFocus()"))
        val postedShow =
            controller.substringAfter("private fun tryShowPendingSoftKeyboard()")
                .substringBefore("private fun initFullKeyboard")
        assertTrue("Focus lost after posting must re-enter the bounded wait path", postedShow.substringBefore("rootLayout.requestFocus()").contains("showPendingSoftKeyboardIfReady()"))
        assertFalse(controller.contains("toggleSoftInput("))
    }

    @Test
    fun physicalInputYieldsOnlyViewFocusAfterAHandledCallback() {
        val activity = File("src/main/java/com/papi/nova/utils/ExternalDisplayControlActivity.kt").readText()
        val controller = File("src/main/java/com/papi/nova/utils/ExternalDisplayControlController.kt").readText()
        val host = File("src/main/java/com/papi/nova/utils/ExternalDisplayControlHost.kt").readText()

        assertTrue(
            "Handled game input needs an explicit deferred view-focus handoff",
            controller.contains("private fun yieldCommandDeckFocusAfterHandledInput("),
        )
        val handoff =
            controller.substringAfter("private fun yieldCommandDeckFocusAfterHandledInput(")
                .substringBefore("private fun createProgrammaticUI()")
        assertTrue("The handoff must run after the active input callback", handoff.contains("handler.post"))
        assertTrue("The existing input root, not a focusless window, must become the safe target", handoff.contains("rootLayout.requestFocus()"))
        assertTrue("The handoff target must be explicitly focusable", controller.contains("rootLayout.isFocusable = true"))
        assertTrue("The handoff target must remain focusable in touch mode", controller.contains("rootLayout.isFocusableInTouchMode = true"))
        assertTrue("Unhandled input must preserve deck focus", handoff.contains("if (!handled"))
        assertFalse("View-focus handoff must never release the Activity window", handoff.contains("host.releaseCommandDeckFocus()"))

        val keyDown = controller.substringAfter("fun onKeyDown(event: KeyEvent)").substringBefore("fun onKeyUp(event: KeyEvent)")
        assertTrue("Game handling must complete before any focus handoff is scheduled", keyDown.indexOf("game.onKeyDown") < keyDown.indexOf("yieldCommandDeckFocusAfterHandledInput"))
        assertFalse("Physical key-down must never synchronously drop window focus", keyDown.contains("host.releaseCommandDeckFocus()"))

        assertFalse("Window-focus release is no longer part of the Activity contract", activity.contains("override fun releaseCommandDeckFocus()"))
        assertEquals(
            "FLAG_NOT_FOCUSABLE is allowed only before Activity creation, never during an input handoff",
            1,
            Regex("window\\.addFlags\\(WindowManager\\.LayoutParams\\.FLAG_NOT_FOCUSABLE\\)")
                .findAll(activity)
                .count(),
        )
        assertFalse("Window-focus release is no longer part of the host contract", host.contains("fun releaseCommandDeckFocus()"))
    }
}
