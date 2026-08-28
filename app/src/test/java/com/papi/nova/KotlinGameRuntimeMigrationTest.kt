package com.papi.nova

import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.papi.nova.binding.input.GameInputDevice
import com.papi.nova.binding.input.driver.UsbDriverService
import com.papi.nova.binding.input.evdev.EvdevListener
import com.papi.nova.binding.video.PerfOverlayListener
import com.papi.nova.binding.video.PerfOverlaySample
import com.papi.nova.nvstream.NvConnectionListener
import com.papi.nova.ui.ExternalControllerView
import com.papi.nova.ui.GameGestures
import com.papi.nova.ui.StreamContainer
import java.io.File
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinGameRuntimeMigrationTest {

    @Test
    fun gameRuntimeIsKotlinSource() {
        assertFalse(File("src/main/java/com/papi/nova/Game.java").exists())
        assertTrue(File("src/main/java/com/papi/nova/Game.kt").exists())
    }

    @Test
    fun gameKeepsJavaFacingStaticContracts() {
        assertEquals("Host", Game.EXTRA_HOST)
        assertEquals("Port", Game.EXTRA_PORT)
        assertEquals("HttpsPort", Game.EXTRA_HTTPS_PORT)
        assertEquals("AppName", Game.EXTRA_APP_NAME)
        assertEquals("AppUUID", Game.EXTRA_APP_UUID)
        assertEquals("AppId", Game.EXTRA_APP_ID)
        assertEquals("UniqueId", Game.EXTRA_UNIQUEID)
        assertEquals("UUID", Game.EXTRA_PC_UUID)
        assertEquals("PcName", Game.EXTRA_PC_NAME)
        assertEquals("HDR", Game.EXTRA_APP_HDR)
        assertEquals("ServerCert", Game.EXTRA_SERVER_CERT)
        assertEquals("VirtualDisplay", Game.EXTRA_VDISPLAY)
        assertEquals("DisplayModeExplicit", Game.EXTRA_DISPLAY_MODE_EXPLICIT)
        assertEquals("WatchOnly", Game.EXTRA_WATCH_ONLY)
        assertEquals("StreamWidth", Game.EXTRA_STREAM_WIDTH)
        assertEquals("StreamHeight", Game.EXTRA_STREAM_HEIGHT)
        assertEquals("StreamFps", Game.EXTRA_STREAM_FPS)
        assertEquals("AiProfilePreference", Game.EXTRA_AI_PROFILE_PREFERENCE)
        assertEquals("LaunchOptimization", Game.EXTRA_LAUNCH_OPTIMIZATION)
        assertEquals("ServerCommands", Game.EXTRA_SERVER_COMMANDS)
        assertEquals("DisplayID", Game.EXTRA_DISPLAY_ID)
        assertEquals("ArtemisStreaming", Game.CLIPBOARD_IDENTIFIER)

        val instance = Game::class.java.getField("instance")
        val isStreamActive = Game::class.java.getField("isStreamActive")
        assertTrue(Modifier.isStatic(instance.modifiers))
        assertTrue(Modifier.isStatic(isStreamActive.modifiers))
        assertTrue(Modifier.isVolatile(isStreamActive.modifiers))

        assertFalse(Game.shouldRequestHdrStream(false, false, Build.VERSION_CODES.TIRAMISU, true))
        assertTrue(Game.shouldRequestHdrStream(true, false, Build.VERSION_CODES.TIRAMISU, false))
        assertTrue(Game.shouldShowSdr10BitOptInToast(true, false, Build.VERSION_CODES.TIRAMISU, false))
        assertTrue(Game.shouldShowHdrRequiresAndroidNToast(true, false, Build.VERSION_CODES.M))
        assertTrue(Game.shouldShowPolarisLockOverlay(true, false))
        assertFalse(Game.shouldShowPolarisLockOverlay(true, true))
        assertFalse(Game.shouldShowPolarisLockOverlay(false, true))
        assertEquals(SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(0)), Game.formatCurrentTime(0))
    }

    @Test
    @Suppress("DEPRECATION")
    fun gameKeepsRuntimeInterfacesAndPublicCallbacks() {
        assertTrue(AppCompatActivity::class.java.isAssignableFrom(Game::class.java))
        assertTrue(SurfaceHolder.Callback::class.java.isAssignableFrom(Game::class.java))
        assertTrue(View.OnGenericMotionListener::class.java.isAssignableFrom(Game::class.java))
        assertTrue(View.OnTouchListener::class.java.isAssignableFrom(Game::class.java))
        assertTrue(NvConnectionListener::class.java.isAssignableFrom(Game::class.java))
        assertTrue(EvdevListener::class.java.isAssignableFrom(Game::class.java))
        assertTrue(View.OnSystemUiVisibilityChangeListener::class.java.isAssignableFrom(Game::class.java))
        assertTrue(GameGestures::class.java.isAssignableFrom(Game::class.java))
        assertTrue(StreamContainer.InputCallbacks::class.java.isAssignableFrom(Game::class.java))
        assertTrue(ExternalControllerView.InputCallbacks::class.java.isAssignableFrom(Game::class.java))
        assertTrue(PerfOverlayListener::class.java.isAssignableFrom(Game::class.java))
        assertTrue(UsbDriverService.UsbDriverStateListener::class.java.isAssignableFrom(Game::class.java))
        assertTrue(View.OnKeyListener::class.java.isAssignableFrom(Game::class.java))

        Game::class.java.getConstructor()
        Game::class.java.getMethod("getConfiguredHudTargetFps")
        Game::class.java.getMethod("isKeyboardLayoutVisible")
        Game::class.java.getMethod("toggleKeyboardController")
        Game::class.java.getMethod("toggleFullKeyboard")
        Game::class.java.getMethod("toggleVirtualController")
        Game::class.java.getMethod("updatePipAutoEnter")
        Game::class.java.getMethod("setMetaKeyCaptureState", Boolean::class.javaPrimitiveType!!)
        Game::class.java.getMethod("isOnExternalDisplay")
        Game::class.java.getMethod("handleKeyDown", KeyEvent::class.java)
        Game::class.java.getMethod("handleKeyUp", KeyEvent::class.java)
        Game::class.java.getMethod("handleKeyMultiple", KeyEvent::class.java)
        Game::class.java.getMethod("sendKeys", ShortArray::class.java)
        Game::class.java.getMethod("handleFocusChange", Boolean::class.javaPrimitiveType!!)
        Game::class.java.getMethod("sendClipboard", Boolean::class.javaPrimitiveType!!)
        Game::class.java.getMethod("getClipboard", Int::class.javaPrimitiveType!!)
        Game::class.java.getMethod("handleMotionEvent", View::class.java, MotionEvent::class.java)
        Game::class.java.getMethod("sendExecServerCmd", Int::class.javaPrimitiveType!!)
        Game::class.java.getMethod("getServerCmds")
        Game::class.java.getMethod("isZoomModeEnabled")
        Game::class.java.getMethod("toggleZoomMode")
        Game::class.java.getMethod("rotateScreen")
        Game::class.java.getMethod("selectMouseMode", android.content.Context::class.java)
        Game::class.java.getMethod("getNovaApiClient")
        Game::class.java.getMethod("getCurrentMouseModeLabel")
        Game::class.java.getMethod("toggleHUD")
        Game::class.java.getMethod("cycleNovaHudFromController")
        Game::class.java.getMethod("switchTouchSensitivity")
        Game::class.java.getMethod("disconnect")
        Game::class.java.getMethod("relaunchStream")
        Game::class.java.getMethod("quit")
        Game::class.java.getMethod("showGameMenu", GameInputDevice::class.java)
        Game::class.java.getMethod("hideGameMenu")
        Game::class.java.getMethod("toggleFloatingButtonVisibility")
        Game::class.java.getMethod("handleCommitText", CharSequence::class.java)
        Game::class.java.getMethod("handleDeleteSurroundingText", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        Game::class.java.getMethod("onPerfSample", PerfOverlaySample::class.java)
    }

    @Test
    fun gameMenuCallbackInterfaceKeepsJavaShape() {
        Game.GameMenuCallbacks::class.java.getMethod("showMenu", GameInputDevice::class.java)
        Game.GameMenuCallbacks::class.java.getMethod("hideMenu")
        assertEquals(
            Boolean::class.javaPrimitiveType!!,
            Game.GameMenuCallbacks::class.java.getMethod("isMenuOpen").returnType
        )
    }

    @Test
    fun gameTouchInputUsesNumericConversionsInsteadOfRuntimeCasts() {
        val source = readGameSource()
        val touchInput = source.substring(
            source.indexOf("private fun handleTouchInput("),
            source.indexOf("private fun handleMultiTouchGesture(")
        )

        assertFalse(
            "MotionEvent Float coordinates must not be runtime-cast to Int",
            touchInput.contains(" as Int")
        )
        assertTrue(touchInput.contains("event!!.getHistoricalX(aActionIndex, i).toInt()"))
        assertTrue(touchInput.contains("event!!.getHistoricalY(aActionIndex, i).toInt()"))
        assertTrue(touchInput.contains("event!!.getX(aActionIndex).toInt()"))
        assertTrue(touchInput.contains("event!!.getY(aActionIndex).toInt()"))
        assertTrue(touchInput.contains("event!!.getX(actualActionIndex).toInt()"))
        assertTrue(touchInput.contains("event!!.getY(actualActionIndex).toInt()"))
        assertTrue(touchInput.contains("event!!.getX(1).toInt()"))
        assertTrue(touchInput.contains("event!!.getY(1).toInt()"))
        assertTrue(source.contains("getRelativeAxisX(event).toInt().toShort()"))
        assertTrue(source.contains("getRelativeAxisY(event).toInt().toShort()"))
        assertTrue(source.contains("getAxisValue(MotionEvent.AXIS_VSCROLL) * 120).toInt().toShort()"))
        assertTrue(source.contains("getAxisValue(MotionEvent.AXIS_HSCROLL) * 120).toInt().toShort()"))
    }

    @Test
    fun stylusEventsTryNativePenBeforePointerCaptureGate() {
        val source = readGameSource()
        val pointerInputBranch = source.substring(
            source.indexOf("// This case is for mice and non-finger touch devices"),
            source.indexOf("// Handle stylus presses")
        )

        val nativePenAttempt = pointerInputBranch.indexOf("trySendPenEvent(view, event)")
        val captureGate = pointerInputBranch.indexOf("if (!inputCaptureProvider!!.isCapturingActive())")

        assertTrue("Game should attempt native pen events from stylus/eraser MotionEvents", nativePenAttempt >= 0)
        assertTrue("Game should still guard mouse input on inactive pointer capture", captureGate >= 0)
        assertTrue(
            "Native pen events must be attempted before the pointer-capture gate so touchscreen stylus pressure survives on devices without SOURCE_MOUSE_RELATIVE",
            nativePenAttempt < captureGate
        )
    }

    @Test
    fun quickMenuKeyboardActionTogglesFullKeyboardOverlay() {
        val source = String(
            Files.readAllBytes(Path.of("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt")),
            StandardCharsets.UTF_8
        )
        val keyboardAction = source.substring(
            source.indexOf("NovaQuickMenuActionId.KEYBOARD ->"),
            source.indexOf("else -> Unit", source.indexOf("NovaQuickMenuActionId.KEYBOARD ->"))
        )

        assertTrue(keyboardAction.contains("game.toggleFullKeyboard()"))
        assertFalse(keyboardAction.contains("game.toggleKeyboard()"))
    }

    @Test
    fun disconnectStartsBackgroundResumePolicyWithoutQuitFlag() {
        val source = readGameSource()
        val disconnect = source.substring(
            source.indexOf("fun disconnect()"),
            source.indexOf("fun relaunchStream()")
        )
        val onStop = source.substring(
            source.indexOf("override fun onStop()"),
            source.indexOf("private fun setInputGrabState(")
        )
        val resumePolicy = source.substring(
            source.indexOf("private fun prepareBackgroundResumeWindow("),
            source.indexOf("fun disconnect()")
        )

        assertTrue(
            "explicit disconnect should prepare the Polaris resume window",
            disconnect.contains("prepareBackgroundResumeWindow()")
        )
        assertFalse(
            "disconnect must not take the quit path",
            disconnect.contains("quitOnStop = true")
        )
        assertTrue(
            "backgrounding the stream should also preserve the resume window",
            onStop.contains("prepareBackgroundResumeWindow()")
        )
        assertTrue(
            "resume policy should use the existing foreground keep-alive service",
            resumePolicy.contains("NovaStreamKeepAlive.start")
        )
        assertTrue(
            "resume policy should sync the Polaris timeout preference",
            resumePolicy.contains("disconnectResumeTimeoutSeconds")
        )
    }

    @Test
    fun streamShutdownReportUsesRuntimeTasksInsteadOfRawThread() {
        val source = readGameSource()
        val stopConnection = source.substring(
            source.indexOf("private fun stopConnection()"),
            source.indexOf("override fun stageFailed(")
        )
        val sessionReport = stopConnection.substring(
            stopConnection.indexOf("// Raw Doctor sampling runs independently of HUD visibility."),
            stopConnection.indexOf("novaHud?.dismiss()")
        )

        assertTrue(sessionReport.contains("launchRuntimeIo(\"NovaSessionReport\")"))
        assertFalse(sessionReport.contains("Thread({"))
    }

    @Test
    fun hudBitrateAdjustUsesReplacingRuntimeTask() {
        val source = readGameSource()
        val bitrateAdjust = source.substring(
            source.indexOf("// Wire proactive bitrate adjustment"),
            source.indexOf("schedulePolarisLiveSessionStatusRefresh(true)", source.indexOf("// Wire proactive bitrate adjustment"))
        )

        assertTrue(bitrateAdjust.contains("launchReplacingRuntimeIo(\"NovaBitrateAdjust\")"))
        assertFalse(bitrateAdjust.contains("Thread({"))
    }

    @Test
    fun gameForwardsStructuredPerfSamplesToNovaHud() {
        val source = readGameSource()

        assertTrue(source.contains("override fun onPerfSample(sample:PerfOverlaySample)"))
        assertTrue(source.contains("novaHud!!.updateFromPerfSample(sample)"))
        assertTrue(source.contains("override fun onPerfUpdate(text:String)"))
        assertTrue(source.contains("novaHud!!.updateFromPerfText(text)"))
    }

    private fun readGameSource(): String {
        return String(Files.readAllBytes(Path.of("src/main/java/com/papi/nova/Game.kt")), StandardCharsets.UTF_8)
    }
}
