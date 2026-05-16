package com.papi.nova;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Build;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.papi.nova.binding.input.GameInputDevice;
import com.papi.nova.binding.input.driver.UsbDriverService;
import com.papi.nova.binding.input.evdev.EvdevListener;
import com.papi.nova.binding.video.PerfOverlayListener;
import com.papi.nova.nvstream.NvConnectionListener;
import com.papi.nova.ui.ExternalControllerView;
import com.papi.nova.ui.GameGestures;
import com.papi.nova.ui.StreamContainer;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

public class KotlinGameRuntimeMigrationTest {
    @Test
    public void gameRuntimeIsKotlinSource() {
        assertFalse(new File("src/main/java/com/papi/nova/Game.java").exists());
        assertTrue(new File("src/main/java/com/papi/nova/Game.kt").exists());
    }

    @Test
    public void gameKeepsJavaFacingStaticContracts() throws Exception {
        assertEquals("Host", Game.EXTRA_HOST);
        assertEquals("Port", Game.EXTRA_PORT);
        assertEquals("HttpsPort", Game.EXTRA_HTTPS_PORT);
        assertEquals("AppName", Game.EXTRA_APP_NAME);
        assertEquals("AppUUID", Game.EXTRA_APP_UUID);
        assertEquals("AppId", Game.EXTRA_APP_ID);
        assertEquals("UniqueId", Game.EXTRA_UNIQUEID);
        assertEquals("UUID", Game.EXTRA_PC_UUID);
        assertEquals("PcName", Game.EXTRA_PC_NAME);
        assertEquals("HDR", Game.EXTRA_APP_HDR);
        assertEquals("ServerCert", Game.EXTRA_SERVER_CERT);
        assertEquals("VirtualDisplay", Game.EXTRA_VDISPLAY);
        assertEquals("DisplayModeExplicit", Game.EXTRA_DISPLAY_MODE_EXPLICIT);
        assertEquals("WatchOnly", Game.EXTRA_WATCH_ONLY);
        assertEquals("ServerCommands", Game.EXTRA_SERVER_COMMANDS);
        assertEquals("DisplayID", Game.EXTRA_DISPLAY_ID);
        assertEquals("ArtemisStreaming", Game.CLIPBOARD_IDENTIFIER);

        Field instance = Game.class.getField("instance");
        Field isStreamActive = Game.class.getField("isStreamActive");
        assertTrue(Modifier.isStatic(instance.getModifiers()));
        assertTrue(Modifier.isStatic(isStreamActive.getModifiers()));
        assertTrue(Modifier.isVolatile(isStreamActive.getModifiers()));

        assertFalse(Game.shouldRequestHdrStream(false, false, Build.VERSION_CODES.TIRAMISU, true));
        assertTrue(Game.shouldRequestHdrStream(true, false, Build.VERSION_CODES.TIRAMISU, false));
        assertTrue(Game.shouldShowSdr10BitOptInToast(true, false, Build.VERSION_CODES.TIRAMISU, false));
        assertTrue(Game.shouldShowHdrRequiresAndroidNToast(true, false, Build.VERSION_CODES.M));
        assertEquals(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(0)), Game.formatCurrentTime(0));
    }

    @Test
    public void gameKeepsRuntimeInterfacesAndPublicCallbacks() throws Exception {
        assertTrue(AppCompatActivity.class.isAssignableFrom(Game.class));
        assertTrue(SurfaceHolder.Callback.class.isAssignableFrom(Game.class));
        assertTrue(View.OnGenericMotionListener.class.isAssignableFrom(Game.class));
        assertTrue(View.OnTouchListener.class.isAssignableFrom(Game.class));
        assertTrue(NvConnectionListener.class.isAssignableFrom(Game.class));
        assertTrue(EvdevListener.class.isAssignableFrom(Game.class));
        assertTrue(View.OnSystemUiVisibilityChangeListener.class.isAssignableFrom(Game.class));
        assertTrue(GameGestures.class.isAssignableFrom(Game.class));
        assertTrue(StreamContainer.InputCallbacks.class.isAssignableFrom(Game.class));
        assertTrue(ExternalControllerView.InputCallbacks.class.isAssignableFrom(Game.class));
        assertTrue(PerfOverlayListener.class.isAssignableFrom(Game.class));
        assertTrue(UsbDriverService.UsbDriverStateListener.class.isAssignableFrom(Game.class));
        assertTrue(View.OnKeyListener.class.isAssignableFrom(Game.class));

        Game.class.getConstructor();
        Game.class.getMethod("getConfiguredHudTargetFps");
        Game.class.getMethod("isKeyboardLayoutVisible");
        Game.class.getMethod("toggleKeyboardController");
        Game.class.getMethod("toggleFullKeyboard");
        Game.class.getMethod("toggleVirtualController");
        Game.class.getMethod("updatePipAutoEnter");
        Game.class.getMethod("setMetaKeyCaptureState", boolean.class);
        Game.class.getMethod("isOnExternalDisplay");
        Game.class.getMethod("handleKeyDown", KeyEvent.class);
        Game.class.getMethod("handleKeyUp", KeyEvent.class);
        Game.class.getMethod("handleKeyMultiple", KeyEvent.class);
        Game.class.getMethod("sendKeys", short[].class);
        Game.class.getMethod("handleFocusChange", boolean.class);
        Game.class.getMethod("sendClipboard", boolean.class);
        Game.class.getMethod("getClipboard", int.class);
        Game.class.getMethod("handleMotionEvent", View.class, MotionEvent.class);
        Game.class.getMethod("sendExecServerCmd", int.class);
        Game.class.getMethod("getServerCmds");
        Game.class.getMethod("isZoomModeEnabled");
        Game.class.getMethod("toggleZoomMode");
        Game.class.getMethod("rotateScreen");
        Game.class.getMethod("selectMouseMode", android.content.Context.class);
        Game.class.getMethod("getNovaApiClient");
        Game.class.getMethod("getCurrentMouseModeLabel");
        Game.class.getMethod("toggleHUD");
        Game.class.getMethod("switchTouchSensitivity");
        Game.class.getMethod("disconnect");
        Game.class.getMethod("relaunchStream");
        Game.class.getMethod("quit");
        Game.class.getMethod("showGameMenu", GameInputDevice.class);
        Game.class.getMethod("hideGameMenu");
        Game.class.getMethod("toggleFloatingButtonVisibility");
        Game.class.getMethod("handleCommitText", CharSequence.class);
        Game.class.getMethod("handleDeleteSurroundingText", int.class, int.class);
    }

    @Test
    public void gameMenuCallbackInterfaceKeepsJavaShape() throws Exception {
        Game.GameMenuCallbacks.class.getMethod("showMenu", GameInputDevice.class);
        Game.GameMenuCallbacks.class.getMethod("hideMenu");
        assertEquals(boolean.class, Game.GameMenuCallbacks.class.getMethod("isMenuOpen").getReturnType());
    }

    @Test
    public void gameTouchInputUsesNumericConversionsInsteadOfRuntimeCasts() throws Exception {
        String source = readGameSource();
        String touchInput = source.substring(
                source.indexOf("private fun handleTouchInput("),
                source.indexOf("private fun handleMultiTouchGesture("));

        assertFalse("MotionEvent Float coordinates must not be runtime-cast to Int",
                touchInput.contains(" as Int"));
        assertTrue(touchInput.contains("event!!.getHistoricalX(aActionIndex, i).toInt()"));
        assertTrue(touchInput.contains("event!!.getHistoricalY(aActionIndex, i).toInt()"));
        assertTrue(touchInput.contains("event!!.getX(aActionIndex).toInt()"));
        assertTrue(touchInput.contains("event!!.getY(aActionIndex).toInt()"));
        assertTrue(touchInput.contains("event!!.getX(actualActionIndex).toInt()"));
        assertTrue(touchInput.contains("event!!.getY(actualActionIndex).toInt()"));
        assertTrue(touchInput.contains("event!!.getX(1).toInt()"));
        assertTrue(touchInput.contains("event!!.getY(1).toInt()"));
        assertTrue(source.contains("getRelativeAxisX(event).toInt().toShort()"));
        assertTrue(source.contains("getRelativeAxisY(event).toInt().toShort()"));
        assertTrue(source.contains("getAxisValue(MotionEvent.AXIS_VSCROLL) * 120).toInt().toShort()"));
        assertTrue(source.contains("getAxisValue(MotionEvent.AXIS_HSCROLL) * 120).toInt().toShort()"));
    }

    @Test
    public void quickMenuKeyboardActionTogglesFullKeyboardOverlay() throws Exception {
        String source = new String(Files.readAllBytes(Path.of("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt")),
                StandardCharsets.UTF_8);
        String keyboardAction = source.substring(
                source.indexOf("NovaQuickMenuActionId.KEYBOARD ->"),
                source.indexOf("else -> Unit", source.indexOf("NovaQuickMenuActionId.KEYBOARD ->")));

        assertTrue(keyboardAction.contains("game.toggleFullKeyboard()"));
        assertFalse(keyboardAction.contains("game.toggleKeyboard()"));
    }

    @Test
    public void disconnectStartsBackgroundResumePolicyWithoutQuitFlag() throws Exception {
        String source = readGameSource();
        String disconnect = source.substring(
                source.indexOf("fun disconnect()"),
                source.indexOf("fun relaunchStream()"));
        String onStop = source.substring(
                source.indexOf("override fun onStop()"),
                source.indexOf("private fun setInputGrabState("));
        String resumePolicy = source.substring(
                source.indexOf("private fun prepareBackgroundResumeWindow("),
                source.indexOf("fun disconnect()"));

        assertTrue("explicit disconnect should prepare the Polaris resume window",
                disconnect.contains("prepareBackgroundResumeWindow()"));
        assertFalse("disconnect must not take the quit path",
                disconnect.contains("quitOnStop = true"));
        assertTrue("backgrounding the stream should also preserve the resume window",
                onStop.contains("prepareBackgroundResumeWindow()"));
        assertTrue("resume policy should use the existing foreground keep-alive service",
                resumePolicy.contains("NovaStreamKeepAlive.start"));
        assertTrue("resume policy should sync the Polaris timeout preference",
                resumePolicy.contains("disconnectResumeTimeoutSeconds"));
    }

    private static String readGameSource() throws Exception {
        return new String(Files.readAllBytes(Path.of("src/main/java/com/papi/nova/Game.kt")),
                StandardCharsets.UTF_8);
    }
}
