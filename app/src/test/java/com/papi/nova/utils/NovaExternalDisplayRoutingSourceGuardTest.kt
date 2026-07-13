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
    fun notificationReceiverReopensGameOwnedPresentationWithoutStartingActivity() {
        val source = File("src/main/java/com/papi/nova/StartExternalDisplayControlReceiver.kt").readText()

        assertFalse(source.contains("ActivityOptions"))
        assertFalse(Regex("""\bstartActivit(?:y|ies)\s*\(""").containsMatchIn(source))
        assertFalse(source.contains("ExternalDisplayControlActivity"))
        assertTrue(source.contains("Game.instance"))
        assertTrue(source.contains("game.showCompanionControls()"))
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
            "The companion Presentation must not double as the stream launch trampoline",
            serverHelper.contains("ExternalDisplayControlPresentation.EXTRA_LAUNCH_INTENT")
        )
    }

    @Test
    fun externalControlPresentationDoesNotBootstrapGameLaunches() {
        val source = File("src/main/java/com/papi/nova/utils/ExternalDisplayControlPresentation.kt").readText()

        assertFalse(
            "The companion Presentation should only host controls; stream bootstrapping belongs to the dedicated trampoline",
            source.contains("EXTRA_LAUNCH_INTENT")
        )
        assertFalse(
            "The companion Presentation must not launch Game itself from its target display",
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

    @Test
    fun companionDialogsAttachToTheLivePresentationWindowToken() {
        val presentation =
            File("src/main/java/com/papi/nova/utils/ExternalDisplayControlPresentation.kt").readText()
        val gameMenu = File("src/main/java/com/papi/nova/GameMenu.kt").readText()
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()

        assertTrue(presentation.contains("TYPE_APPLICATION_ATTACHED_DIALOG"))
        assertTrue(presentation.contains("createWindowContext("))
        assertTrue(presentation.contains("presentationWindow.decorView.windowToken"))
        assertFalse(
            "A second TYPE_PRESENTATION window reuses the wrong WindowContext token and crashes",
            presentation.contains("presentationWindow.attributes.type")
        )
        assertTrue(
            presentation.contains(
                "GameMenu(game, companionDialogContext, companionDialogWindowType, ::companionDialogWindowToken)"
            )
        )
        assertTrue(gameMenu.contains("private val dialogWindowTokenProvider: (() -> IBinder?)? = null"))
        assertTrue(gameMenu.contains("window.setType(windowType)"))
        assertTrue(gameMenu.contains("window.attributes.token = token"))

        val showMenuDialog =
            gameMenu.substringAfter("private fun showMenuDialog(").substringBefore("private fun showSpecialKeysMenu(")
        val menuWindowBinding = showMenuDialog.indexOf("applyDialogWindowType(sheet)")
        val menuShow = showMenuDialog.indexOf("sheet.show()")
        assertTrue("Menu BottomSheetDialog must be bound before show()", menuWindowBinding in 0 until menuShow)

        val serverCommandDialog =
            gameMenu.substringAfter("val serverCommandDialog =").substringBefore("} else {")
        val serverWindowBinding = serverCommandDialog.indexOf("applyDialogWindowType(serverCommandDialog)")
        val serverShow = serverCommandDialog.indexOf("serverCommandDialog.show()")
        assertTrue("Server-command AlertDialog must be bound before show()", serverWindowBinding in 0 until serverShow)

        assertTrue(
            gameMenu.contains(
                "selectMouseMode(dialogScreenContext, dialogWindowType, dialogWindowTokenProvider?.invoke())"
            )
        )
        val mouseMode =
            game.substringAfter("fun selectMouseMode(").substringBefore("private fun toggleMouseLocalCursor(")
        val mouseType = mouseMode.indexOf("mouseModeDialog.window?.setType(windowType)")
        val mouseToken = mouseMode.indexOf("mouseModeDialog.window?.attributes?.token = dialogWindowToken")
        val mouseShow = mouseMode.indexOf("mouseModeDialog.show()")
        assertTrue("Mouse-mode dialog type must be assigned before show()", mouseType in 0 until mouseShow)
        assertTrue("Mouse-mode dialog token must be assigned before show()", mouseToken in 0 until mouseShow)

        val quit = game.substringAfter("fun quit()").substringBefore("override fun showGameMenu(")
        val quitType = quit.indexOf("sheet.window?.setType(companionPresentation.companionDialogWindowType)")
        val quitToken = quit.indexOf("sheet.window?.attributes?.token = companionPresentation.companionDialogWindowToken()")
        val quitShow = quit.indexOf("sheet.show()")
        assertTrue("Quit sheet type must be assigned before show()", quitType in 0 until quitShow)
        assertTrue("Quit sheet token must be assigned before show()", quitToken in 0 until quitShow)
    }

    @Test
    fun companionControlsAreGameOwnedPresentationInsteadOfManifestActivity() {
        val presentationFile =
            File("src/main/java/com/papi/nova/utils/ExternalDisplayControlPresentation.kt")
        val activityFile =
            File("src/main/java/com/papi/nova/utils/ExternalDisplayControlActivity.kt")
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue("Companion controls should have a Presentation source", presentationFile.exists())
        val presentation = presentationFile.readText()
        assertTrue(presentation.contains("class ExternalDisplayControlPresentation"))
        assertTrue(presentation.contains("Presentation(game, display"))
        assertTrue(game.contains("private var externalDisplayControlPresentation"))
        assertTrue(game.contains("ExternalDisplayControlPresentation(this, companionDisplay"))
        assertFalse("The obsolete companion Activity source should be removed", activityFile.exists())
        assertFalse(
            "A Game-owned Presentation must not be registered as an Activity",
            manifest.contains("ExternalDisplayControlPresentation")
        )
    }

    @Test
    fun failedPresentationShowDisposesPartialStateBeforeClearingGameReferences() {
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val presentation =
            File("src/main/java/com/papi/nova/utils/ExternalDisplayControlPresentation.kt").readText()
        val showBlock =
            game.substringAfter("val presentation = ExternalDisplayControlPresentation")
                .substringBefore("listenForExternalDisplayRemoval()")
        val dispose = showBlock.indexOf("presentation.disposeAfterFailedShow()")
        val clearReference = showBlock.indexOf("externalDisplayControlPresentation = null", dispose)

        assertTrue("Failed show must explicitly dispose partial Presentation state", dispose >= 0)
        assertTrue("Disposal must happen before Game clears its owner reference", clearReference > dispose)
        val transientDisposal =
            presentation.substringAfter("private fun disposeTransientState()")
                .substringBefore("fun disposeAfterFailedShow()")
        val failedShowDisposal =
            presentation.substringAfter("fun disposeAfterFailedShow()")
                .substringBefore("override fun onStop()")
        assertTrue(transientDisposal.contains("handler.removeCallbacksAndMessages(null)"))
        assertTrue(
            "Failed-show disposal must invoke the real transient-state cleanup",
            failedShowDisposal.contains("disposeTransientState()")
        )
    }

    @Test
    fun companionNotificationPermissionContinuationIsOwnedByGame() {
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val presentation =
            File("src/main/java/com/papi/nova/utils/ExternalDisplayControlPresentation.kt").readText()
        val launchBlock =
            game.substringAfter("presentation.show()").substringBefore("catch (e:WindowManager.InvalidDisplayException)")
        val permissionBlock =
            game.substringAfter("override fun onRequestPermissionsResult(")
                .substringBefore("override fun onNewIntent(")
        val compactPermissionBlock = permissionBlock.replace(Regex("""\s+"""), " ")
        val initViews = presentation.substringAfter("private fun initViews()").substringBefore("private fun initTouchEventHandling()")

        assertTrue(
            launchBlock.contains(
                "ExternalDisplayControlPresentation.ensureCompanionControlsNotification(this)"
            )
        )
        assertFalse("Presentation onCreate must not request notification permission", initViews.contains("checkNotificationPermission()"))
        assertFalse(
            "Permission result must not depend on a still-showing Presentation instance",
            permissionBlock.contains("externalDisplayControlPresentation")
        )
        assertTrue(
            permissionBlock.contains(
                "ExternalDisplayControlPresentation.onCompanionNotificationPermissionResult("
            )
        )
        assertTrue(
            compactPermissionBlock.contains(
                "if (requestCode == ExternalDisplayControlPresentation.NOTIFICATION_PERMISSION_REQUEST_CODE)"
            )
        )
        assertTrue(
            compactPermissionBlock.contains(
                "val granted:Boolean = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED"
            )
        )
        assertTrue(
            "The actual permission result and live companion lifecycle predicate must be passed to the continuation",
            compactPermissionBlock.contains(
                "onCompanionNotificationPermissionResult( this, granted, !isFinishing() && isStreamActive && shouldLaunchCompanionControls() )"
            )
        )
    }

    @Test
    fun stoppedStreamDismissesCompanionControlsBeforeBackgroundCleanup() {
        val source = File("src/main/java/com/papi/nova/Game.kt").readText()
        val stopConnection =
            source.substringAfter("private fun stopConnection()")
                .substringBefore("override fun stageFailed(")
        val streamInactive = stopConnection.indexOf("isStreamActive = false")
        val controlsClosed = stopConnection.indexOf("closeCompanionControls()", streamInactive)
        val backgroundCleanup = stopConnection.indexOf("launchRuntimeIo(\"NovaSessionReport\")")

        assertTrue("stopConnection must mark the stream inactive", streamInactive >= 0)
        assertTrue("stopConnection must dismiss companion controls after marking the stream inactive", controlsClosed > streamInactive)
        assertTrue("companion controls must close before asynchronous teardown work", controlsClosed in 0 until backgroundCleanup)
    }

    @Test
    fun companionReopenChecksLiveGameLifecycleAtExecutionTime() {
        val source = File("src/main/java/com/papi/nova/Game.kt").readText()
        val showControls =
            source.substringAfter("fun showCompanionControls() {")
                .substringBefore("@SuppressLint(\"InlinedApi\")")
        val uiDispatch = showControls.indexOf("runOnUiThread {")
        val lifecycleDecision = showControls.indexOf("CompanionControlLifecyclePolicy.canShow(")
        val deniedClose = showControls.indexOf("closeCompanionControls()")
        val deniedReturn = showControls.indexOf("return@runOnUiThread")
        val presentationLaunch = showControls.indexOf("launchCompanionControlsIfAvailable()")

        assertTrue("showCompanionControls must dispatch before reading lifecycle state", uiDispatch >= 0)
        assertTrue("lifecycle state must be checked inside the UI-thread callback", lifecycleDecision > uiDispatch)
        assertTrue("denied reopen must close stale controls", deniedClose > lifecycleDecision)
        assertTrue("denied reopen must return before presentation launch", deniedReturn > deniedClose)
        assertTrue("presentation launch must follow the lifecycle gate", presentationLaunch > deniedReturn)
        val compactShowControls = showControls.replace(Regex("\\s+"), " ")
        assertTrue(
            "The execution-time lifecycle decision must receive the live Game state, not constants",
            compactShowControls.contains(
                "CompanionControlLifecyclePolicy.canShow(isStreamActive, isFinishing(), isDestroyed)"
            )
        )
    }

    @Test
    fun newlyAddedDisplayReopensCompanionControlsUsingTheNewLogicalDisplayId() {
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val listener =
            game.substringAfter("val listener:DisplayManager.DisplayListener")
                .substringBefore("externalDisplayListener = listener")
        val displayAdded =
            listener.substringAfter("override fun onDisplayAdded")
                .substringBefore("override fun onDisplayChanged")

        assertTrue(
            "A hot-added replacement display must re-derive and reopen companion controls",
            displayAdded.contains("showCompanionControls()")
        )
    }
}
