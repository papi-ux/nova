package com.papi.nova

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameReconnectSourceGuardTest {
    private val game = File("src/main/java/com/papi/nova/Game.kt").readText()

    private fun section(start: String, end: String): String {
        val from = game.indexOf(start)
        assertTrue("Missing start marker: $start", from >= 0)
        val to = game.indexOf(end, from + start.length)
        assertTrue("Missing end marker: $end", to > from)
        return game.substring(from, to)
    }

    @Test
    fun anAbsorbedStreamErrorAlwaysLeadsSomewhere() {
        val wiring = section(
            "novaResilienceManager = com.papi.nova.manager.ConnectionResilienceManager(",
            "com.papi.nova.jni.PolarisNativeHook.register(",
        )

        assertFalse(
            "the resilience callback must not be a log line: an absorbed error never reaches " +
                "connectionTerminated, so a callback that only logs freezes the stream",
            game.contains("LimeLog.info(\"Nova: Attempting reconnect...\")"),
        )
        assertTrue(
            "every resilience outcome needs a handler in Game",
            wiring.contains("novaReconnectOverlay?.show(attempt, maxAttempts)") &&
                wiring.contains("reconnectAfterStreamError(errorCode, attempt, maxAttempts)") &&
                wiring.contains("handlePolarisHostSessionEnded()") &&
                wiring.contains("endStreamAfterUnreachableHost(errorCode)"),
        )
        assertTrue(
            "the attempts a previous Game spent must reach the manager, or a relaunch loop is unbounded",
            wiring.contains("reconnectAttemptsUsed") &&
                game.contains("getIntExtra(EXTRA_RECONNECT_ATTEMPT, 0)"),
        )
    }

    @Test
    fun theAutomaticReconnectTearsTheStreamDownBeforeRelaunching() {
        val reconnect = section(
            "private fun reconnectAfterStreamError(",
            "private fun endStreamAfterUnreachableHost(",
        )

        val stop = reconnect.indexOf("stopConnection()")
        val relaunch = reconnect.indexOf("launchReplacementStream(attempt)")
        assertTrue(
            "the native connection is already terminated, so Game has to stop it before launching another",
            stop >= 0 && relaunch > stop,
        )
        assertTrue(
            "the teardown must match connectionTerminated: controllers stopped, input released",
            reconnect.contains("controllerHandler?.stop()") && reconnect.contains("setInputGrabState(false)"),
        )
        assertTrue(
            "a stream that is already closing must not be relaunched behind the user's back",
            reconnect.contains("isFinishing || isDestroyed || displayedFailureDialog || hostSessionEnded"),
        )
    }

    @Test
    fun anUnreachableHostEndsTheStreamTheOrdinaryWay() {
        val unreachable = section("private fun endStreamAfterUnreachableHost(", " fun quit()")

        assertTrue(
            "no answer from the host is not a host-ended session; it gets the terminated dialog with Reconnect",
            unreachable.contains("connectionTerminated(errorCode)") &&
                !unreachable.contains("handlePolarisHostSessionEnded()"),
        )
        assertTrue(
            "a check cut short by onDestroy, onStop or the host-ended teardown also reports no answer; " +
                "that stream has already ended and must not get a second ending",
            unreachable.indexOf("isFinishing || isDestroyed || hostSessionEnded || displayedFailureDialog") in
                0 until unreachable.indexOf("connectionTerminated(errorCode)"),
        )
    }

    @Test
    fun onlyTheAutomaticReconnectCarriesAnAttemptCount() {
        val manual = section(" fun relaunchStream() {", "private fun launchReplacementStream(")
        val launch = section("private fun launchReplacementStream(", "private fun reconnectAfterStreamError(")

        assertTrue(
            "the Reconnect button and display relaunches start with a fresh budget",
            manual.contains("launchReplacementStream(0)"),
        )
        assertTrue(
            "a stale count from this Game's own intent must not leak into the next launch",
            launch.indexOf("removeExtra(EXTRA_RECONNECT_ATTEMPT)") in 0 until
                launch.indexOf("putExtra(EXTRA_RECONNECT_ATTEMPT, reconnectAttempt)"),
        )
    }

    @Test
    fun theAutomaticReconnectOnlyResumes() {
        val launch = section("val continueLaunch = fun() {", ".setBitrate(configuredStreamBitrateKbps)")

        assertTrue(
            "the replacement Game knows it is an automatic reconnect from the attempt count it was handed",
            launch.contains("val automaticReconnect = reconnectAttemptsUsed > 0"),
        )
        assertTrue(
            "a game the host has ended must not be launched again by a reconnect; NvConnection refuses " +
                "a fresh launch for a resume-only stream",
            launch.contains(".setResumeExistingOnly(resumeExistingRequested || automaticReconnect)"),
        )
        assertTrue(
            "Auto Safe's fresh launch quits and relaunches the game, which a reconnect must never do",
            launch.contains("forceFreshLaunch = !watchOnlyRequested && !resumeExistingRequested && !automaticReconnect &&"),
        )
    }

    @Test
    fun theLiveStatusPollGivesTheReconnectItsIdentity() {
        val refresh = section("private fun refreshPolarisLiveSessionStatus()", "catch (e:kotlinx.coroutines.CancellationException)")

        val read = refresh.indexOf("val status = statusClient.getSessionStatus()")
        val observed = refresh.indexOf("novaResilienceManager?.onSessionStatus(status)")
        assertTrue(
            "without the stream's identity every reconnect check ends the stream",
            read >= 0 && observed > read,
        )
    }

    @Test
    fun renderedFramesEndTheReconnectBudget() {
        val perfSample = section("override fun onPerfSample(", "override fun onUsbPermissionPromptStarting(")

        assertTrue(
            "the budget comes back only once ReconnectRecoveryTracker sees a stable stream; one frame is not enough",
            perfSample.contains("recoveryTracker.onSample(sample.framesRendered,") &&
                perfSample.contains("novaResilienceManager?.onReconnectSuccess()") &&
                perfSample.contains("removeExtra(EXTRA_RECONNECT_ATTEMPT)"),
        )
        assertFalse(
            "a single rendered frame must not reset the budget",
            perfSample.contains("sample.framesRendered > 0"),
        )
    }

    @Test
    fun aDestroyedGameOnlyRemovesItsOwnNativeHook() {
        val onDestroy = section("override fun onDestroy()", "override fun onRequestPermissionsResult(")

        assertTrue(
            "an automatic reconnect starts the next Game before this one is destroyed; unregistering " +
                "without the owner would strip the new Game's hook",
            onDestroy.contains("PolarisNativeHook.unregister(novaResilienceManager)"),
        )
    }
}
