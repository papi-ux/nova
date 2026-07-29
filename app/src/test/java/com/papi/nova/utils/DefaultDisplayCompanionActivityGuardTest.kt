package com.papi.nova.utils

import java.io.File
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
    fun activityLeasesFocusOnlyWhileImeIsVisible() {
        val activity = File("src/main/java/com/papi/nova/utils/ExternalDisplayControlActivity.kt").readText()
        val controller = File("src/main/java/com/papi/nova/utils/ExternalDisplayControlController.kt").readText()
        val prepare = activity.substringAfter("override fun prepareForSoftKeyboard()").substringBefore("override fun onSoftKeyboardVisibilityChanged")
        val release = activity.substringAfter("override fun onSoftKeyboardVisibilityChanged").substringBefore("override fun closeFromGame")
        val compactKeyboard = controller.substringAfter("private fun _toggleKeyboard()").substringBefore("private fun initFullKeyboard")

        assertTrue(prepare.contains("window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)"))
        assertTrue(release.contains("window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)"))
        assertTrue(release.contains("requestFocusToGameActivity(false)"))
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
        assertTrue("Exhausted no-focus waits must restore Game focus", focusWait.contains("host.releaseSoftKeyboardFocus()"))
        val postedShow =
            controller.substringAfter("private fun tryShowPendingSoftKeyboard()")
                .substringBefore("private fun initFullKeyboard")
        assertTrue("Focus lost after posting must re-enter the bounded wait path", postedShow.substringBefore("rootLayout.requestFocus()").contains("showPendingSoftKeyboardIfReady()"))
        assertFalse(controller.contains("toggleSoftInput("))
    }
}
