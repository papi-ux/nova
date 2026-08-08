package com.papi.nova


import com.google.android.material.snackbar.Snackbar
import com.papi.nova.utils.ServerHelper.getActiveDisplay
import com.papi.nova.utils.ServerHelper.getAndroidCompanionDisplay

import com.papi.nova.api.PolarisSessionEvents
import com.papi.nova.binding.PlatformBinding
import com.papi.nova.binding.audio.AndroidAudioRenderer
import com.papi.nova.binding.input.ControllerHandler
import com.papi.nova.binding.input.GameInputDevice
import com.papi.nova.binding.input.KeyboardTranslator
import com.papi.nova.binding.input.NovaControllerShortcutAction
import com.papi.nova.binding.input.NovaControllerShortcutState
import com.papi.nova.binding.input.capture.InputCaptureManager
import com.papi.nova.binding.input.capture.InputCaptureProvider
import com.papi.nova.binding.input.touch.AbsoluteTouchContext
import com.papi.nova.binding.input.touch.RelativeTouchContext
import com.papi.nova.binding.input.driver.UsbDriverService
import com.papi.nova.binding.input.evdev.EvdevListener
import com.papi.nova.binding.input.touch.TouchContext
import com.papi.nova.binding.input.touch.TrackpadContext
import com.papi.nova.binding.input.virtual_controller.VirtualController
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardController
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardLayoutController
import com.papi.nova.binding.video.CrashListener
import com.papi.nova.binding.video.MediaCodecDecoderRenderer
import com.papi.nova.binding.video.MediaCodecHelper
import com.papi.nova.binding.video.PerfOverlayListener
import com.papi.nova.binding.video.PerfOverlaySample
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.NvConnectionListener
import com.papi.nova.nvstream.StreamConfiguration
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.input.KeyboardPacket
import com.papi.nova.nvstream.input.MouseButtonPacket
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.preferences.GlPreferences
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.runtime.BackgroundResumePolicy
import com.papi.nova.runtime.NovaRuntimeTasks
import com.papi.nova.ui.ExternalControllerView
import com.papi.nova.ui.GameGestures
import com.papi.nova.ui.NovaHudSessionSummaryLog
import com.papi.nova.ui.NovaCompanionCommandDeckState
import com.papi.nova.ui.NovaHudMode
import com.papi.nova.ui.NovaHudUiState
import com.papi.nova.ui.NovaSnackbar
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.NovaSheetChrome
import com.papi.nova.ui.StreamContainer
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.papi.nova.utils.Dialog
import com.papi.nova.utils.DeviceUtils
import com.papi.nova.utils.DisplayFocusTelemetry
import com.papi.nova.utils.CompanionControlHostPolicy
import com.papi.nova.utils.CompanionControlLifecyclePolicy
import com.papi.nova.utils.CompanionControlReopenGeneration
import com.papi.nova.utils.DualScreenQuickMenuPolicy
import com.papi.nova.utils.ExternalDisplayControlActivity
import com.papi.nova.utils.ExternalDisplayControlHost
import com.papi.nova.utils.ExternalDisplayControlPresentation
import com.papi.nova.utils.GameDisplayLaunchTrampolineActivity
import com.papi.nova.utils.AndroidStreamDisplayTarget
import com.papi.nova.utils.MouseModeOption
import com.papi.nova.utils.PanZoomHandler
import com.papi.nova.utils.PerformanceDataTracker
import com.papi.nova.utils.ServerHelper
import com.papi.nova.utils.ShortcutHelper
import com.papi.nova.utils.SpinnerDialog
import com.papi.nova.utils.UiHelper

import org.json.JSONObject

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.PersistableBundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Rational
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.View.OnGenericMotionListener
import android.view.View.OnSystemUiVisibilityChangeListener
import android.view.View.OnTouchListener
import android.view.ViewOutlineProvider
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageButton
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager

import android.os.Looper
import java.nio.charset.StandardCharsets
import java.util.HashSet
import java.util.Objects
import java.util.Queue
import java.util.ArrayDeque

import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Arrays
import java.util.Date
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import android.view.SurfaceView
import android.view.ViewGroup


class Game : NovaActivity(), SurfaceHolder.Callback, OnGenericMotionListener, OnTouchListener, NvConnectionListener, EvdevListener, OnSystemUiVisibilityChangeListener, GameGestures, StreamContainer.InputCallbacks, ExternalControllerView.InputCallbacks, PerfOverlayListener, UsbDriverService.UsbDriverStateListener, View.OnKeyListener {
    override fun shouldRecreateForFontScaleChange(): Boolean = false


private val runtimeTasks:NovaRuntimeTasks = NovaRuntimeTasks(this, "Nova runtime")
private var novaHud:com.papi.nova.ui.NovaStreamHud? = null
 var configuredHudTargetFps:Float = 0f
private var configuredStreamFrameRateFps:Float = 0f
private var configuredStreamBitrateKbps:Int = 0
@Volatile private var lastCompanionPerfSample:PerfOverlaySample? = null
private var preferStableRefreshMultipleForAutoSafe:Boolean = false
private var audioHapticEngine:com.papi.nova.ui.AudioHapticEngine? = null
private var gyroAimController:com.papi.nova.ui.GyroAimController? = null
private var novaDisconnectReceiver:android.content.BroadcastReceiver? = null
private var lastButtonState:Int = 0

 // Only 2 touches are supported
    private var touchContextMap:Array<TouchContext?> = arrayOfNulls<TouchContext?>(2)
private var trackpadContextMap:Array<TouchContext?> = arrayOfNulls<TouchContext?>(2)
private var panZoomHandler:PanZoomHandler? = null
private var threeFingerDownTime:Long = 0
private var fourFingerDownTime:Long = 0
private var fiveFingerDownTime:Long = 0

private var timerHandler:Handler? = null

private var controllerHandler:ControllerHandler? = null
private var keyboardTranslator:KeyboardTranslator? = null
private var virtualController:VirtualController? = null

private var keyBoardController:KeyBoardController? = null

private var keyBoardLayoutController:KeyBoardLayoutController? = null

 lateinit var prefConfig:PreferenceConfiguration
private var tombstonePrefs:SharedPreferences? = null

private var displayWidth:Int = 0
private var displayHeight:Int = 0
private var currentOrientation:Int = 0

 var conn:NvConnection? = null

 // Nova: Polaris integration
     var novaApiClient:com.papi.nova.api.PolarisApiClient? = null
@Volatile private var lastPolarisSessionStatus:com.papi.nova.api.PolarisSessionStatus? = null
private var polarisSessionStatusRefreshInFlight:AtomicBoolean = AtomicBoolean(false)
private var novaResilienceManager:com.papi.nova.manager.ConnectionResilienceManager? = null
private var novaEventSource:com.papi.nova.api.PolarisEventSource? = null
private var polarisSseSawCurrentSessionEvent = false
private var novaProgressOverlay:com.papi.nova.ui.SessionProgressOverlay? = null
private var novaLockScreenOverlay:com.papi.nova.ui.LockScreenOverlay? = null
private var novaReconnectOverlay:com.papi.nova.ui.ReconnectOverlay? = null
private var spinner:SpinnerDialog? = null
private var displayedFailureDialog:Boolean = false
private var connecting:Boolean = false
 @JvmField var connected:Boolean = false
private var autoEnterPip:Boolean = false
private var surfaceCreated:Boolean = false
private var attemptedConnection:Boolean = false
private var suppressPipRefCount:Int = 0
private var pcName:String? = null
private var appName:String? = null
private var app:NvApp? = null
private var desiredRefreshRate:Float = 0.toFloat()

private var inputCaptureProvider:InputCaptureProvider? = null
private val fallbackNovaShortcutState:NovaControllerShortcutState = NovaControllerShortcutState().apply {
    loneAppSwitchOpensQuickMenu = true
}
private var modifierFlags:Int = 0
private var grabbedInput:Boolean = true
private var cursorVisible:Boolean = false
private var currentMouseModeIndex:Int = 0
private var streamingDisplayId:Int = Display.DEFAULT_DISPLAY
private var companionControlDisplayId:Int = INVALID_DISPLAY_ID
private var companionControlHasWindowFocus:Boolean = false
private var companionControlsDismissedByUser = false
private val companionControlReopenGeneration = CompanionControlReopenGeneration()
private var lastQuickMenuInteractionDisplayId:Int = INVALID_DISPLAY_ID
private var isTopResumedActivity:Boolean = false
private var externalDisplayControlPresentation:ExternalDisplayControlHost? = null
private var externalDisplayListener:DisplayManager.DisplayListener? = null
@Volatile private var lastClientPresentationRefreshRate:Float = 0f
@Volatile private var lastClientPresentationDisplayModeId:Int = 0
@Volatile private var lastClientPresentationDisplayMode:String = ""
@Volatile private var lastReportedClientPresentationKey:String = ""
@Volatile private var lastPolarisDeviceCapabilities:JSONObject? = null
@Volatile private var lastPolarisAppliedStreamSettings:JSONObject? = null
@Volatile private var lastClientProfileProvenance:com.papi.nova.manager.ClientProfileProvenance =
com.papi.nova.manager.ClientProfileProvenance(com.papi.nova.manager.ClientProfileSource.LOCAL_DEFAULT)
private var launchProfilePreference:String = "auto"
private var launchOptimizationJson:String? = null
private var mirrorDesktop:Boolean = false
private var forcePrivateAfterSteamClose:Boolean = false
private var clientPresentationReportInFlight:AtomicBoolean = AtomicBoolean(false)
private var cursorVisibilitySyncLock:Any = Any()
private var pendingHostCursorVisible:Boolean = false
private var hasPendingCursorVisibilitySync:Boolean = false
private var cursorVisibilitySyncScheduled:Boolean = false
 var isZoomModeEnabled:Boolean = false
private var synthClickPending:Boolean = false
private var pointerSwiping:Boolean = false
private var waitingForAllModifiersUp:Boolean = false
private var specialKeyCode:Int = KeyEvent.KEYCODE_UNKNOWN
private var polarisSessionStatusRefreshTick:Runnable = object : Runnable {
override fun run() {
if (!connected || !isStreamActive || timerHandler == null)
{
return
}
refreshPolarisLiveSessionStatus()
timerHandler!!.postDelayed(this, POLARIS_SESSION_STATUS_REFRESH_MS)
}
}
private var streamContainer:StreamContainer? = null
private var synthTouchDownTime:Long = 0

private var pendingDrag:Boolean = false
private var isDragging:Boolean = false
private var lastTouchDownX:Float = 0.toFloat()
private var lastTouchDownY:Float = 0.toFloat()

private var lastAbsTouchUpTime:Long = 0
private var lastAbsTouchDownTime:Long = 0
private var lastAbsTouchUpX:Float = 0.toFloat()
private var lastAbsTouchUpY:Float = 0.toFloat()
private var lastAbsTouchDownX:Float = 0.toFloat()
private var lastAbsTouchDownY:Float = 0.toFloat()

private var quitOnStop:Boolean = false
private var localSessionEndMarked:Boolean = false
@Volatile private var hostSessionEnded:Boolean = false
private var isHidingOverlays:Boolean = false
private var floatingButtonShown:Boolean = false
private var overlayToggleZoomButtonShown:Boolean = false
private var notificationOverlayView:TextView? = null
private var requestedNotificationOverlayVisibility:Int = View.GONE
private var performanceOverlayView:View? = null

private var performanceOverlayLite:TextView? = null

private var performanceOverlayBig:TextView? = null

private var decoderRenderer:MediaCodecDecoderRenderer? = null
private var reportedCrash:Boolean = false

private var highPerfWifiLock:WifiManager.WifiLock? = null
private var lowLatencyWifiLock:WifiManager.WifiLock? = null

private var connectedToUsbDriverService:Boolean = false
private var usbDriverServiceConnection:ServiceConnection = object : ServiceConnection {
override fun onServiceConnected(componentName:ComponentName?, iBinder:IBinder?) {
var binder:UsbDriverService.UsbDriverBinder? = iBinder as UsbDriverService.UsbDriverBinder?
binder!!.setListener(controllerHandler)
binder!!.setStateListener(this@Game)
binder!!.start()
connectedToUsbDriverService = true
}
override fun onServiceDisconnected(componentName:ComponentName?) {
connectedToUsbDriverService = false
}
}

private var appUUID:String? = null
private var host:String? = null
private var port:Int = 0
private var httpsPort:Int = 0
private var appId:Int = 0
private var uniqueId:String? = null
private var serverCert:X509Certificate? = null
private var vDisplay:Boolean = false
private var watchOnlyRequested:Boolean = false
private var watchStreamWidth:Int = 0
private var watchStreamHeight:Int = 0
private var watchStreamFps:Float = 0f
private var backgroundResumePrepared:Boolean = false
@Volatile private var disconnectResumeTimeoutSyncInFlight:Boolean = false
private var disconnectResumeTimeoutSynced:Boolean = false
 var serverCmds:ArrayList<String> = ArrayList()

private var rootView:ViewParent? = null
private var clipboardManager:ClipboardManager? = null
private var clipboardSyncRunning:Boolean = false

private var httpConn:NvHTTP? = null

 var gameMenuCallbacks:GameMenuCallbacks? = null

 var isInputOnly:Boolean = true
 var allowChangeMouseMode:Boolean = true
 var isOnExternalDisplay:Boolean = false
private var floatingMenuButton:ImageButton? = null
private var overlayToggleButton:ImageButton? = null
private var floatingButtonDX:Float = 0.toFloat()
private var floatingButtonDY:Float = 0.toFloat()
private var isButtonMoving:Boolean = false
private var floatingButtonStartX:Float = 0.toFloat()
private var floatingButtonStartY:Float = 0.toFloat()

 // Zoom button drag state
    private var zoomButtonDX:Float = 0.toFloat()
private var zoomButtonDY:Float = 0.toFloat()
private var isZoomButtonMoving:Boolean = false
private var zoomButtonStartX:Float = 0.toFloat()
private var zoomButtonStartY:Float = 0.toFloat()
private var commitTextQueue:Queue<String?> = ArrayDeque()
private var commitTextHandler:Handler = Handler(Looper.getMainLooper())

private var flushCommitTextQueue:Runnable = object : Runnable {
override fun run() {
if (commitTextQueue.isEmpty())
{
return
}
var chunk:String? = commitTextQueue.poll()
if (conn != null)
{
conn!!.sendUtf8Text(chunk)
}
if (!commitTextQueue.isEmpty())
{
commitTextHandler.postDelayed(this, 15)
}
}
}

private var backgroundPing:Runnable = Runnable { if (connected)
{
timerHandler!!.postDelayed(backgroundPing, 20)
MoonBridge.sendEmptyPayload()
} }

 val isKeyboardLayoutVisible:Boolean
get() {
return keyBoardLayoutController != null && keyBoardLayoutController!!.shown
}

private val streamingDisplay:Display?
get() {
var display:Display? = null
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
{
var displayManager:DisplayManager? = getSystemService(DisplayManager::class.java)
if (displayManager != null)
{
display = displayManager!!.getDisplay(streamingDisplayId)
}
}
return if (display != null) display else getWindowManager().getDefaultDisplay()
}

private fun getStreamAudioContext(): Context {
val streamingDisplay:Display? = this.streamingDisplay
return if (streamingDisplay != null)
{
LimeLog.info("Nova: Android display audio context stream_id=$streamingDisplayId display_id=${streamingDisplay.displayId}")
createDisplayContext(streamingDisplay)
}
else
{
LimeLog.info("Nova: Android display audio context stream_id=$streamingDisplayId fallback=activity")
this
}
}

fun streamingDisplayIdForCompanion(): Int = streamingDisplayId

private fun logGameDisplayFocus(hasWindowFocus:Boolean) {
// Focus restoration after menu dismissal is not user input; keep provenance unchanged.
LimeLog.info(DisplayFocusTelemetry.game(streamingDisplayId, hasWindowFocus, isTopResumedActivity))
}

fun logCompanionDisplayFocus(displayId:Int, hasWindowFocus:Boolean) {
// Presentation focus is lifecycle telemetry. Touch/key paths record real interaction origin.
LimeLog.info(DisplayFocusTelemetry.companion(displayId, hasWindowFocus, isTopResumedActivity))
}

fun recordQuickMenuInteraction(displayId:Int) {
if (displayId == streamingDisplayId || displayId == companionControlDisplayId)
{
lastQuickMenuInteractionDisplayId = displayId
}
}

private fun getCompanionControlDisplay(): Display? {
if (!::prefConfig.isInitialized)
{
return null
}
return getAndroidCompanionDisplay(this, prefConfig, streamingDisplayId)
}

private fun shouldLaunchCompanionControls(): Boolean {
return ::prefConfig.isInitialized && prefConfig.enableFullExDisplay && getCompanionControlDisplay() != null
}

private fun updateCompanionCommandDeck() {
val sample = lastCompanionPerfSample
val status = lastPolarisSessionStatus
val targetFps = listOf(
status?.encoder?.sessionTargetFps ?: 0.0,
status?.encoder?.encodeTargetFps ?: 0.0,
status?.encoder?.requestedClientFps ?: 0.0,
configuredHudTargetFps.toDouble(),
configuredStreamFrameRateFps.toDouble(),
).firstOrNull { it > 0.0 }
val bitrateKbps = listOf(
status?.autoQuality?.liveBitrateKbps ?: 0,
status?.encoder?.bitrateKbps ?: 0,
configuredStreamBitrateKbps,
).firstOrNull { it > 0 }
val sessionState = status?.state.orEmpty().ifBlank {
when {
connected -> "streaming"
connecting -> "connecting"
else -> ""
}
}
val state = NovaCompanionCommandDeckState.from(
hud = NovaHudUiState.from(
mode = NovaHudMode.DEBUG,
fps = sample?.fps ?: 0.0,
targetFps = targetFps ?: 0.0,
latencyMs = sample?.rttMs ?: 0,
codec = (sample?.codec?.takeIf { it.isNotBlank() } ?: status?.encoder?.codec).orEmpty(),
bitrateKbps = bitrateKbps ?: 0,
width = sample?.width ?: 0,
height = sample?.height ?: 0,
status = status,
sparklineSamples = emptyList(),
),
sessionState = sessionState,
displayRole = getString(R.string.companion_deck_display_role),
unavailableLabel = getString(R.string.companion_deck_status_unavailable),
hideCompanionEnabled = ExternalDisplayControlPresentation.canUseCompanionControlsNotification(this),
)
externalDisplayControlPresentation?.updateCommandDeckState(state)
}

private fun launchCompanionControlsIfAvailable() {
val companionDisplay:Display? = getCompanionControlDisplay()
if (::prefConfig.isInitialized && prefConfig.enableFullExDisplay && companionDisplay != null)
{
val companionDisplayId:Int = companionDisplay.getDisplayId()
val currentPresentation:ExternalDisplayControlHost? = externalDisplayControlPresentation
if (currentPresentation == null || !currentPresentation.isHostShowing() || companionControlDisplayId != companionDisplayId)
{
currentPresentation?.dismissAfterCurrentCallback()
when (CompanionControlHostPolicy.select(companionDisplayId)) {
CompanionControlHostPolicy.HostType.ACTIVITY -> {
externalDisplayControlPresentation = null
companionControlDisplayId = companionDisplayId
companionControlHasWindowFocus = false
ExternalDisplayControlActivity.launch(this, companionDisplayId)
ExternalDisplayControlPresentation.ensureCompanionControlsNotification(this)
}
CompanionControlHostPolicy.HostType.PRESENTATION -> {
val presentation = ExternalDisplayControlPresentation(this, companionDisplay)
presentation.setOnDismissListener {
if (externalDisplayControlPresentation === presentation)
{
val shouldMigrateOpenMenu = presentation.shouldMigrateOpenMenuToStream(canMigrateCompanionMenuToStream())
externalDisplayControlPresentation = null
if (lastQuickMenuInteractionDisplayId == companionDisplayId)
{
lastQuickMenuInteractionDisplayId = streamingDisplayId
}
companionControlDisplayId = INVALID_DISPLAY_ID
if (shouldMigrateOpenMenu)
{
showQuickMenuOnStreamAfterCompanionClose()
}
}
}
externalDisplayControlPresentation = presentation
companionControlDisplayId = companionDisplayId
companionControlHasWindowFocus = false
try
{
presentation.show()
updateCompanionCommandDeck()
ExternalDisplayControlPresentation.ensureCompanionControlsNotification(this)
}
catch (e:WindowManager.InvalidDisplayException)
{
presentation.disposeAfterFailedShow()
externalDisplayControlPresentation = null
if (lastQuickMenuInteractionDisplayId == companionDisplayId)
{
lastQuickMenuInteractionDisplayId = streamingDisplayId
}
companionControlDisplayId = INVALID_DISPLAY_ID
companionControlHasWindowFocus = false
LimeLog.warning("Nova: Android companion presentation unavailable display_id=$companionDisplayId")
}
}
}
}
listenForExternalDisplayRemoval()
}
}

fun beginExplicitCompanionControlsReopen(): Long {
val requestGeneration = companionControlReopenGeneration.beginRequest()
runOnUiThread {
if (companionControlReopenGeneration.isCurrent(requestGeneration)) {
companionControlsDismissedByUser = false
}
}
return requestGeneration
}

fun hideCompanionControlsForSession() {
runOnUiThread {
val reopenAvailable = ExternalDisplayControlPresentation.canUseCompanionControlsNotification(this)
if (!CompanionControlLifecyclePolicy.canHide(reopenAvailable)) {
updateCompanionCommandDeck()
Toast.makeText(
this,
getString(R.string.companion_deck_hide_requires_notifications),
Toast.LENGTH_SHORT,
).show()
return@runOnUiThread
}
companionControlReopenGeneration.invalidatePendingRequests()
ExternalDisplayControlPresentation.ensureCompanionControlsNotification(this)
companionControlsDismissedByUser = true
externalDisplayControlPresentation?.dismissAfterCurrentCallback()
LimeLog.info("Nova: Android companion controls hidden for current stream session")
}
}

fun showCompanionControls(
explicitUserRequest: Boolean = false,
requestGeneration: Long? = null,
) {
runOnUiThread {
if (
explicitUserRequest &&
(requestGeneration == null || !companionControlReopenGeneration.isCurrent(requestGeneration))
) {
LimeLog.info("Nova: Ignoring stale companion reopen request generation=$requestGeneration")
return@runOnUiThread
}
val reopenAvailable = ExternalDisplayControlPresentation.canUseCompanionControlsNotification(this)
if (CompanionControlLifecyclePolicy.shouldRestoreDismissedCompanion(
companionControlsDismissedByUser,
reopenAvailable,
)) {
companionControlsDismissedByUser = false
LimeLog.info("Nova: Restoring companion controls because the notification reopen path is unavailable")
}
val canShow = CompanionControlLifecyclePolicy.canShow(
isStreamActive,
isFinishing(),
isDestroyed,
companionControlsDismissedByUser,
explicitUserRequest,
)
if (!canShow) {
LimeLog.info("Nova: Skipping companion controls active=$isStreamActive finishing=${isFinishing()} destroyed=$isDestroyed dismissed_by_user=$companionControlsDismissedByUser explicit_request=$explicitUserRequest")
if (!isStreamActive || isFinishing() || isDestroyed) {
closeCompanionControls()
} else if (companionControlsDismissedByUser && shouldLaunchCompanionControls()) {
ExternalDisplayControlPresentation.ensureCompanionControlsNotification(this)
}
return@runOnUiThread
}
if (explicitUserRequest) {
companionControlsDismissedByUser = false
}
launchCompanionControlsIfAvailable()
}
}

fun attachExternalDisplayControlActivity(activity:ExternalDisplayControlActivity):Boolean {
if (!CompanionControlLifecyclePolicy.canShow(
isStreamActive,
isFinishing(),
isDestroyed,
companionControlsDismissedByUser,
explicitUserRequest = false,
))
{
return false
}
val companionDisplayId = getCompanionControlDisplay()?.displayId ?: return false
if (companionDisplayId != Display.DEFAULT_DISPLAY)
{
return false
}
externalDisplayControlPresentation = activity
companionControlDisplayId = companionDisplayId
companionControlHasWindowFocus = false
updateCompanionCommandDeck()
return true
}

fun detachExternalDisplayControlActivity(activity:ExternalDisplayControlActivity) {
if (externalDisplayControlPresentation === activity)
{
val shouldMigrateOpenMenu = activity.shouldMigrateOpenMenuToStream(canMigrateCompanionMenuToStream())
externalDisplayControlPresentation = null
if (lastQuickMenuInteractionDisplayId == companionControlDisplayId)
{
lastQuickMenuInteractionDisplayId = streamingDisplayId
}
companionControlDisplayId = INVALID_DISPLAY_ID
companionControlHasWindowFocus = false
if (shouldMigrateOpenMenu)
{
showQuickMenuOnStreamAfterCompanionClose()
}
}
}

@SuppressLint("InlinedApi")
private var hideSystemUi:Runnable = object : Runnable {
override fun run() {
 // TODO: Do we want to use WindowInsetsController here on R+ instead of
            // SYSTEM_UI_FLAG_IMMERSIVE_STICKY? They seem to do the same thing as of S...

            // In multi-window mode on N+, we need to drop our layout flags or we'll
            // be drawing underneath the system UI.
            if (!prefConfig!!.fullScreen || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode()))
{
this@Game.getWindow().getDecorView().setSystemUiVisibility(
View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
}
else
{
 // Use immersive mode
                this@Game.getWindow().getDecorView().setSystemUiVisibility(
(View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
View.SYSTEM_UI_FLAG_FULLSCREEN or
View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY))
}
}
}

private var toggleGrab:Runnable = object : Runnable {
override fun run() {
setInputGrabState(!grabbedInput)
}
}

private val modifierState:Byte
get() {
return modifierFlags.toByte()
}

 //灵敏度保存到集合 适配多个手指
    private var sensitivityMap:MutableMap<String, SensitivityBean> = HashMap()

 val currentMouseModeLabel:String?
get() {
var mouseModes:Array<String?>? = getResources().getStringArray(R.array.mouse_mode_names)
if (currentMouseModeIndex >= 0 && currentMouseModeIndex < mouseModes!!.size)
{
return mouseModes!![currentMouseModeIndex]
}
return getString(R.string.mouse_mode_absolute_touch)
}

	 interface GameMenuCallbacks {
	 fun showMenu(device:GameInputDevice?)
	 fun hideMenu()
	 fun isMenuOpen():Boolean
	}

	@SuppressLint("MissingInflatedId", "ClickableViewAccessibility", "UnspecifiedRegisterReceiverFlag")
override fun onCreate(savedInstanceState:Bundle?) {
super.onCreate(savedInstanceState)

instance = this
timerHandler = Handler(Looper.getMainLooper())

UiHelper.setLocale(this)

 // We don't want a title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE)

 // Read the stream preferences
        prefConfig = PreferenceConfiguration.readPreferences(this)
tombstonePrefs = this@Game.getSharedPreferences("DecoderTombstone", 0)

if (isDisconnectIntent(getIntent()))
{
finish()
return
}

if (prefConfig!!.fullScreen)
{
 // Full-screen
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

 // If we're going to use immersive mode, we want to have
            // the entire screen
            getWindow().getDecorView().setSystemUiVisibility(
(View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN))
}

getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

 // Listen for UI visibility events
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(this)

 // Change volume button behavior
        setVolumeControlStream(AudioManager.STREAM_MUSIC)

 // Inflate the content
        setContentView(R.layout.activity_game)

clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

 // Show the verbose Nova session progress overlay immediately; keep spinner nullable for legacy cleanup paths.
        novaProgressOverlay = com.papi.nova.ui.SessionProgressOverlay(this)
        novaProgressOverlay?.show()
        novaProgressOverlay?.updateState("conn_establishing", getResources().getString(R.string.conn_establishing_msg))


val requestedDisplayId = getIntent().getIntExtra(EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
@Suppress("DEPRECATION")
val currentDisplay: Display = getWindowManager().getDefaultDisplay()
if (requestedDisplayId != currentDisplay.displayId) {
LimeLog.warning(
"Nova: Android stream display mismatch requested_id=$requestedDisplayId " +
"window_id=${currentDisplay.displayId}; using window display metrics"
)
}
streamingDisplayId = currentDisplay.displayId
isOnExternalDisplay = currentDisplay.displayId != Display.DEFAULT_DISPLAY

var shouldInvertDecoderResolution:Boolean = false

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isOnExternalDisplay)
{
var currentMode:Display.Mode? = currentDisplay!!.getMode()
displayWidth = currentMode!!.getPhysicalWidth()
displayHeight = currentMode!!.getPhysicalHeight()
prefConfig!!.width = displayWidth
prefConfig!!.height = displayHeight
prefConfig!!.fps = currentMode!!.getRefreshRate()
prefConfig!!.enableFloatingButton = false
prefConfig!!.showOverlayZoomToggleButton = false
prefConfig!!.enablePip = false
currentOrientation = Configuration.ORIENTATION_LANDSCAPE
setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)
}
else
{
if (prefConfig!!.autoOrientation)
{
currentOrientation = getResources().getConfiguration().orientation
}
else
{
currentOrientation = Configuration.ORIENTATION_LANDSCAPE
}

var portraitMode:Boolean = currentOrientation == Configuration.ORIENTATION_PORTRAIT
shouldInvertDecoderResolution = portraitMode && prefConfig!!.autoInvertVideoResolution

displayWidth = if (shouldInvertDecoderResolution) prefConfig!!.height else prefConfig!!.width
displayHeight = if (shouldInvertDecoderResolution) prefConfig!!.width else prefConfig!!.height

 // Enter landscape unless we're on a square screen
	            setPreferredOrientationForActivity()
	}

if (prefConfig!!.enableFullExDisplay) {
val physicalSize = Point()
@Suppress("DEPRECATION")
currentDisplay.getRealSize(physicalSize)
val windowSize = Point()
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
val bounds = getWindowManager().currentWindowMetrics.bounds
windowSize.set(bounds.width(), bounds.height())
} else {
windowSize.set(physicalSize.x, physicalSize.y)
}
val configuredWidth = if (isOnExternalDisplay) physicalSize.x else displayWidth
val configuredHeight = if (isOnExternalDisplay) physicalSize.y else displayHeight
val resolution = AndroidStreamDisplayTarget.resolveStreamResolution(
modeWidth = configuredWidth,
modeHeight = configuredHeight,
windowWidth = windowSize.x,
windowHeight = windowSize.y,
landscape = currentOrientation != Configuration.ORIENTATION_PORTRAIT,
)
displayWidth = resolution.width
displayHeight = resolution.height
prefConfig!!.width = displayWidth
prefConfig!!.height = displayHeight
LimeLog.info(
"Nova: Android stream geometry display_id=$streamingDisplayId physical=${physicalSize.x}x${physicalSize.y} " +
"window=${windowSize.x}x${windowSize.y} selected=${displayWidth}x${displayHeight}"
)
}

watchOnlyRequested = this@Game.getIntent().getBooleanExtra(EXTRA_WATCH_ONLY, false)
watchStreamWidth = this@Game.getIntent().getIntExtra(EXTRA_STREAM_WIDTH, 0)
watchStreamHeight = this@Game.getIntent().getIntExtra(EXTRA_STREAM_HEIGHT, 0)
watchStreamFps = this@Game.getIntent().getFloatExtra(EXTRA_STREAM_FPS, 0f)
if (watchStreamWidth > 0 && watchStreamHeight > 0)
{
if (watchOnlyRequested)
{
LimeLog.info("Nova: Watch mode using active stream resolution " + watchStreamWidth + "x" + watchStreamHeight)
}
else
{
LimeLog.info("Nova: Launch using explicit stream resolution " + watchStreamWidth + "x" + watchStreamHeight)
}
displayWidth = watchStreamWidth
displayHeight = watchStreamHeight
}

	if ((prefConfig!!.videoScaleMode == PreferenceConfiguration.ScaleMode.STRETCH || shouldIgnoreInsetsForResolution(displayWidth, displayHeight)))
	{
 // Allow the activity to layout under notches if the fill-screen option
            // was turned on by the user or it's a full-screen native resolution
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
{
getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
}
else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
{
getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
}
}

 //光标是否显示
        cursorVisible = prefConfig!!.enableMouseLocalCursor

 // Listen for non-touch events on the game surface
        streamContainer = findViewById(R.id.streamContainer)
streamContainer!!.init(this, prefConfig)
streamContainer!!.setOnGenericMotionListener(this)
streamContainer!!.setOnKeyListener(this)
streamContainer!!.setInputCallbacks(this)
streamContainer!!.setCommitTextEnabled(prefConfig!!.enableCommitText)

rootView = streamContainer!!.getParent()

 //串流画面 顶部居中显示
        if (prefConfig!!.alignDisplayTopCenter)
{
var params:FrameLayout.LayoutParams? = streamContainer!!.getLayoutParams() as FrameLayout.LayoutParams
params!!.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
}
 // Listen for touch events on the background touch view to enable trackpad mode
        // to work on areas outside of the StreamView itself. We use a separate View
        // for this rather than just handling it at the Activity level, because that
        // allows proper touch splitting, which the OSC relies upon.
        var backgroundTouchView:View? = findViewById(R.id.backgroundTouchView)
backgroundTouchView!!.setOnTouchListener(this)


panZoomHandler = PanZoomHandler(
getApplicationContext(),
this,
streamContainer!!.getSurfaceView()!!,
streamContainer,
prefConfig
)

 // Restore previous zoom & pan if enabled and saved
        if (prefConfig!!.rememberZoomPan)
{
streamContainer!!.post({ panZoomHandler!!.setInitialZoomAndPan(
prefConfig!!.zoomScale,
prefConfig!!.panOffsetX,
prefConfig!!.panOffsetY
) })
}

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
{
 // Request unbuffered input event dispatching for all input classes we handle here.
            // Without this, input events are buffered to be delivered in lock-step with VBlank,
            // artificially increasing input latency while streaming.
            streamContainer!!.requestUnbufferedDispatch(
(InputDevice.SOURCE_CLASS_BUTTON or // Keyboards

InputDevice.SOURCE_CLASS_JOYSTICK or // Gamepads

InputDevice.SOURCE_CLASS_POINTER or // Touchscreens and mice (w/o pointer capture)

InputDevice.SOURCE_CLASS_POSITION or // Touchpads

InputDevice.SOURCE_CLASS_TRACKBALL) // Mice (pointer capture)
)
backgroundTouchView!!.requestUnbufferedDispatch(
(InputDevice.SOURCE_CLASS_BUTTON or // Keyboards

InputDevice.SOURCE_CLASS_JOYSTICK or // Gamepads

InputDevice.SOURCE_CLASS_POINTER or // Touchscreens and mice (w/o pointer capture)

InputDevice.SOURCE_CLASS_POSITION or // Touchpads

InputDevice.SOURCE_CLASS_TRACKBALL) // Mice (pointer capture)
)
}

notificationOverlayView = findViewById(R.id.notificationOverlay)

performanceOverlayView = findViewById(R.id.performanceOverlay)

performanceOverlayLite = findViewById(R.id.performanceOverlayLite)

performanceOverlayBig = findViewById(R.id.performanceOverlayBig)

inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this, this)

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
{
streamContainer!!.setOnCapturedPointerListener(object : View.OnCapturedPointerListener {
override fun onCapturedPointer(view:View?, motionEvent:MotionEvent?):Boolean {
 //                    LimeLog.info("onCapturedPointer="+motionEvent.toString());
 //                    LimeLog.info("onCapturedPointer-Device="+motionEvent.getDevice().toString());
                    return handleMotionEvent(view, motionEvent)
}
})
}

 // Warn the user if they're on a metered connection
        var connMgr:ConnectivityManager? = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
var isMetered:Boolean = connMgr!!.isActiveNetworkMetered()
if (isMetered)
{
displayTransientMessage(getResources().getString(R.string.conn_metered))
}

 // Make sure Wi-Fi is fully powered up
        var wifiMgr:WifiManager? = getApplicationContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
try
{
highPerfWifiLock = wifiMgr!!.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Nova High Perf Lock")
highPerfWifiLock!!.setReferenceCounted(false)
highPerfWifiLock!!.acquire()

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
{
lowLatencyWifiLock = wifiMgr!!.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Nova Low Latency Lock")
lowLatencyWifiLock!!.setReferenceCounted(false)
lowLatencyWifiLock!!.acquire()
}
}
catch (e:SecurityException) {
 // Some Samsung Galaxy S10+/S10e devices throw a SecurityException from
            // WifiLock.acquire() even though we have android.permission.WAKE_LOCK in our manifest.
            e!!.printStackTrace()
}

appName = this@Game.getIntent().getStringExtra(EXTRA_APP_NAME)
pcName = this@Game.getIntent().getStringExtra(EXTRA_PC_NAME)

host = this@Game.getIntent().getStringExtra(EXTRA_HOST)
port = this@Game.getIntent().getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT)
httpsPort = this@Game.getIntent().getIntExtra(EXTRA_HTTPS_PORT, 0) // 0 is treated as unknown
appUUID = this@Game.getIntent().getStringExtra(EXTRA_APP_UUID)
appId = this@Game.getIntent().getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID)
uniqueId = this@Game.getIntent().getStringExtra(EXTRA_UNIQUEID)
vDisplay = this@Game.getIntent().getBooleanExtra(EXTRA_VDISPLAY, false)
var displayModeExplicit:Boolean = this@Game.getIntent().getBooleanExtra(EXTRA_DISPLAY_MODE_EXPLICIT, false)
mirrorDesktop = this@Game.getIntent().getBooleanExtra(EXTRA_MIRROR_DESKTOP, false)
forcePrivateAfterSteamClose = this@Game.getIntent().getBooleanExtra(EXTRA_FORCE_PRIVATE_AFTER_STEAM_CLOSE, false)
launchProfilePreference = this@Game.getIntent().getStringExtra(EXTRA_AI_PROFILE_PREFERENCE) ?: ""
launchOptimizationJson = this@Game.getIntent().getStringExtra(EXTRA_LAUNCH_OPTIMIZATION)
serverCmds = this@Game.getIntent().getStringArrayListExtra(EXTRA_SERVER_COMMANDS) ?: ArrayList()
var appSupportsHdr:Boolean = this@Game.getIntent().getBooleanExtra(EXTRA_APP_HDR, false)
var derCertData:ByteArray? = this@Game.getIntent().getByteArrayExtra(EXTRA_SERVER_CERT)

app = NvApp(if (appName != null) appName else "app", appUUID, appId, appSupportsHdr)

try
{
if (derCertData != null)
{
serverCert = CertificateFactory.getInstance("X.509")
.generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate

httpConn = NvHTTP(ComputerDetails.AddressTuple(host ?: "", port), httpsPort, uniqueId ?: "", serverCert, PlatformBinding.getCryptoProvider(this))
}
}
catch (e:Exception) {
e!!.printStackTrace()
}

 // Nova: set up Polaris integration without blocking stream startup on REST probes.
        com.papi.nova.manager.FeatureFlagManager.reset()
novaApiClient = com.papi.nova.api.PolarisApiClient(this, host ?: "", httpsPort, serverCert)
novaLockScreenOverlay = com.papi.nova.ui.LockScreenOverlay(this, novaApiClient!!)
novaReconnectOverlay = com.papi.nova.ui.ReconnectOverlay(this)
novaResilienceManager = com.papi.nova.manager.ConnectionResilienceManager(
novaApiClient!!,
{ LimeLog.info("Nova: Attempting reconnect...") },
{ handlePolarisHostSessionEnded() }
)
com.papi.nova.jni.PolarisNativeHook.register(novaResilienceManager!!)
syncDisconnectResumeTimeoutPolicy()
startNovaFeatureProbe()

if (appId == StreamConfiguration.INVALID_APP_ID)
{
finish()
return
}

var launchOptimization:JSONObject? = if (watchOnlyRequested) null else loadLaunchOptimization(appName)
lastClientProfileProvenance = com.papi.nova.manager.StreamSyncManager.resolveProfileProvenance(launchOptimization, manualOverride = isManualProfileOverride())

 // Initialize the MediaCodec helper before creating the decoder
        var glPrefs:GlPreferences? = GlPreferences.readPreferences(this)
MediaCodecHelper.initialize(this, glPrefs!!.glRenderer)
MediaCodecHelper.setPreferStabilityDecoders(
com.papi.nova.manager.StreamSyncManager.shouldPreferStabilityDecoder(launchOptimization)
)
var forceFreshLaunch:Boolean = !watchOnlyRequested && com.papi.nova.manager.StreamSyncManager.shouldForceFreshLaunch(launchOptimization)
if (forceFreshLaunch)
{
LimeLog.info("Nova: Auto Safe requires fresh launch before streaming")
}

 // Check if the user has enabled HDR
        var displaySupportsHdr10:Boolean = false
if (!isOnExternalDisplay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
{
displaySupportsHdr10 = displaySupportsHdr10(currentDisplay!!.getHdrCapabilities())
}

var willStreamHdr:Boolean = shouldRequestHdrStream(
prefConfig!!.enableHdr,
isOnExternalDisplay,
Build.VERSION.SDK_INT,
displaySupportsHdr10
)

if (shouldShowSdr10BitOptInToast(
prefConfig!!.enableHdr,
isOnExternalDisplay,
Build.VERSION.SDK_INT,
displaySupportsHdr10
))
{
NovaSnackbar.show(this, getString(R.string.nova_hdr_display_unsupported), Snackbar.LENGTH_LONG)
}
else if (shouldShowHdrRequiresAndroidNToast(
prefConfig!!.enableHdr,
isOnExternalDisplay,
Build.VERSION.SDK_INT
))
{
NovaSnackbar.show(this, getString(R.string.nova_hdr_requires_android_n), Snackbar.LENGTH_LONG)
}

 // Check if the user has enabled performance stats overlay
        if (prefConfig!!.enablePerfOverlay)
{
performanceOverlayView!!.setVisibility(View.VISIBLE)
if (prefConfig!!.enablePerfOverlayLite)
{
performanceOverlayLite!!.setVisibility(View.VISIBLE)
if (prefConfig!!.enablePerfOverlayLiteDialog)
{
performanceOverlayLite!!.setOnClickListener({ v-> showGameMenu(null) })
}
}
else
{
performanceOverlayBig!!.setVisibility(View.VISIBLE)
}
if (prefConfig!!.enablePerfOverlayBottom)
{
 //performanceOverlayView.getLayoutParams().layout_gravity = Gravity.BOTTOM;
                var params:FrameLayout.LayoutParams? = performanceOverlayView!!.getLayoutParams() as FrameLayout.LayoutParams
params!!.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
performanceOverlayView!!.setLayoutParams(params)
}
}

decoderRenderer = MediaCodecDecoderRenderer(
this,
prefConfig,
object : CrashListener {
override fun notifyCrash(e:Exception) {
 // The MediaCodec instance is going down due to a crash
                        // let's tell the user something when they open the app again

                        // We must use commit because the app will crash when we return from this function
                        tombstonePrefs!!.edit().putInt("CrashCount", tombstonePrefs!!.getInt("CrashCount", 0) + 1).commit()
reportedCrash = true
}
},
tombstonePrefs!!.getInt("CrashCount", 0),
connMgr!!.isActiveNetworkMetered(),
willStreamHdr,
shouldInvertDecoderResolution,
glPrefs!!.glRenderer,
this)
syncPerfTextWanted()

 // --- Force tight thresholds (prefConfig.forceTightThresholds) ---
        try
{
var forceTight:Boolean = false
if (prefConfig != null)
{
try
{
var f:java.lang.reflect.Field? = prefConfig!!.javaClass.getDeclaredField("forceTightThresholds")
f!!.setAccessible(true)
var v:Any? = f!!.get(prefConfig)
if (v is Boolean) forceTight = (v as Boolean?)!!
}
catch (ignored:Throwable) {}

}
try
{
decoderRenderer!!.setForceTightThresholds(forceTight)
}
catch (ignored:Throwable) {}

if (forceTight)
{
LimeLog.info("ForceTightThresholds enabled: using vsync-based thresholds on all devices")
}
}
catch (ignored:Throwable) {}

 // --- latency profile selection ---
        try
{
if (prefConfig != null && prefConfig!!.preferLowerDelays)
{
 // Intermediate: more responsive than Balanced but not 0 µs
                decoderRenderer!!.setPreferLowerDelays(true)
decoderRenderer!!.setPreferLowerDelaysTimeoutUs(500)  // 0.5 ms
LimeLog.info("PreferLowerDelays: preferLowerDelays=true, timeout=500us")
}
else
{
 // Balanced default
                decoderRenderer!!.setPreferLowerDelays(false)
decoderRenderer!!.setPreferLowerDelaysTimeoutUs(2000) // 2 ms
LimeLog.info("Balanced: preferLowerDelays=false, timeout=2000us")
}
}
catch (ignored:Throwable) {}

 // Don't stream HDR if the decoder can't support it
        if (willStreamHdr && !decoderRenderer!!.isHevcMain10Hdr10Supported && !decoderRenderer!!.isAv1Main10Supported)
{
willStreamHdr = false
NovaSnackbar.showError(this, getString(R.string.nova_hdr_decoder_unsupported))
}
 // Display a message to the user if HEVC was forced on but we still didn't find a decoder
        if (prefConfig!!.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC && !decoderRenderer!!.isHevcSupported)
{
NovaSnackbar.showError(this, getString(R.string.nova_decoder_no_hevc))
}

 // Display a message to the user if AV1 was forced on but we still didn't find a decoder
        if (prefConfig!!.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 && !decoderRenderer!!.isAv1Supported)
{
NovaSnackbar.showError(this, getString(R.string.nova_decoder_no_av1))
}

 // H.264 is always supported
        var supportedVideoFormats:Int = MoonBridge.VIDEO_FORMAT_H264
if (decoderRenderer!!.isHevcSupported)
{
supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_H265
if (willStreamHdr && decoderRenderer!!.isHevcMain10Hdr10Supported)
{
supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_H265_MAIN10
}
}
if (decoderRenderer!!.isAv1Supported)
{
supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_AV1_MAIN8
if (willStreamHdr && decoderRenderer!!.isAv1Main10Supported)
{
supportedVideoFormats = supportedVideoFormats or MoonBridge.VIDEO_FORMAT_AV1_MAIN10
}
}

var gamepadMask:Int = ControllerHandler.getAttachedControllerMask(this).toInt()
if (!prefConfig!!.multiController)
{
 // Always set gamepad 1 present for when multi-controller is
            // disabled for games that don't properly support detection
            // of gamepads removed and replugged at runtime.
            gamepadMask = 1
}
if (prefConfig!!.onscreenController)
{
 // If we're using OSC, always set at least gamepad 1.
            gamepadMask = gamepadMask or 1
}

var explicitStreamFpsOverride:Boolean = watchStreamFps > 0f
var launchRefreshRate:Float = if (explicitStreamFpsOverride) watchStreamFps else prefConfig!!.fps
var maxSupportedLaunchRefreshRate:Float = getMaxSupportedRefreshRate(currentDisplay)
if (!explicitStreamFpsOverride && (maxSupportedLaunchRefreshRate > 0 && launchRefreshRate > maxSupportedLaunchRefreshRate + 0.5f))
{
LimeLog.info(("Clamping launch refresh rate from " + launchRefreshRate +
" to display max " + maxSupportedLaunchRefreshRate))
launchRefreshRate = maxSupportedLaunchRefreshRate
}
if (explicitStreamFpsOverride)
{
if (watchOnlyRequested)
{
LimeLog.info("Nova: Watch mode using active stream FPS " + watchStreamFps)
}
else
{
LimeLog.info("Nova: Launch using explicit stream FPS " + watchStreamFps)
}
}
var autoSafeTargetFps:Float = com.papi.nova.manager.StreamSyncManager.resolveAutoSafeTargetFps(
launchRefreshRate,
launchOptimization
)
if (autoSafeTargetFps > 0f && autoSafeTargetFps + 0.5f < launchRefreshRate)
{
var displayCompatibleTargetFps:Float = com.papi.nova.manager.StreamSyncManager.resolveDisplayCompatibleAutoSafeTargetFps(
autoSafeTargetFps,
getMaxAllowedRefreshRate(currentDisplay),
getSupportedRefreshRates(currentDisplay)
)
if ((displayCompatibleTargetFps > 0f && displayCompatibleTargetFps + 0.5f < autoSafeTargetFps))
{
LimeLog.info(("Nova: Auto Safe display cadence fallback FPS " +
autoSafeTargetFps + " -> " + displayCompatibleTargetFps))
autoSafeTargetFps = displayCompatibleTargetFps
}
}
if (autoSafeTargetFps > 0f && autoSafeTargetFps + 0.5f < launchRefreshRate)
{
configuredStreamFrameRateFps = autoSafeTargetFps
}
else
{
configuredStreamFrameRateFps = launchRefreshRate
}
preferStableRefreshMultipleForAutoSafe = com.papi.nova.manager.StreamSyncManager.shouldPreferStableRefreshMultiple(
launchOptimization,
configuredStreamFrameRateFps
)

 // Set to the optimal mode for streaming
        var displayRefreshRate:Float = prepareDisplayForRendering(currentDisplay)
LimeLog.info("Display refresh rate: " + displayRefreshRate)

 // If the user requested frame pacing using a capped FPS, we will need to change our
        // desired FPS setting here in accordance with the active display refresh rate.
        var roundedRefreshRate:Int = Math.round(displayRefreshRate)
var chosenFrameRate:Float = if (configuredStreamFrameRateFps > 0f) configuredStreamFrameRateFps else prefConfig!!.fps
if (prefConfig!!.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS)
{
if (chosenFrameRate >= roundedRefreshRate)
{
if (chosenFrameRate > roundedRefreshRate + 3)
{
 // Use frame drops when rendering above the screen frame rate
                    prefConfig!!.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED
LimeLog.info("Using drop mode for FPS > Hz")
}
else if (roundedRefreshRate <= 49)
{
 // Let's avoid clearly bogus refresh rates and fall back to legacy rendering
                    prefConfig!!.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED
LimeLog.info("Bogus refresh rate: " + roundedRefreshRate)
}
else
{
chosenFrameRate = (roundedRefreshRate - 1).toFloat()
LimeLog.info("Adjusting FPS target for screen to " + chosenFrameRate)
}
}
}

if (prefConfig!!.framePacingWarpFactor > 0)
{
chosenFrameRate *= prefConfig!!.framePacingWarpFactor
}

configuredStreamBitrateKbps = if (isMetered) prefConfig!!.meteredBitrate else prefConfig!!.bitrate
var autoSafeBitrateKbps:Int = com.papi.nova.manager.StreamSyncManager.resolveAutoSafeBitrateKbps(
configuredStreamBitrateKbps,
launchOptimization
)
if (autoSafeBitrateKbps > 0 && autoSafeBitrateKbps != configuredStreamBitrateKbps)
{
LimeLog.info(("Nova: Auto Safe launch bitrate " + configuredStreamBitrateKbps +
" -> " + autoSafeBitrateKbps + " kbps"))
configuredStreamBitrateKbps = autoSafeBitrateKbps
}
var autoSafeResolution:com.papi.nova.manager.StreamSyncManager.StreamResolution? = com.papi.nova.manager.StreamSyncManager.resolveAutoSafeResolution(
displayWidth,
displayHeight,
launchOptimization
)
if ((autoSafeResolution!!.isValid() && (autoSafeResolution!!.width != displayWidth || autoSafeResolution!!.height != displayHeight)))
{
LimeLog.info(("Nova: Auto Safe launch resolution " + displayWidth + "x" + displayHeight +
" -> " + autoSafeResolution!!.width + "x" + autoSafeResolution!!.height))
displayWidth = autoSafeResolution!!.width
displayHeight = autoSafeResolution!!.height
}
if (autoSafeTargetFps > 0f && autoSafeTargetFps + 0.5f < launchRefreshRate)
{
LimeLog.info(("Nova: Auto Safe launch FPS " + launchRefreshRate +
" -> " + autoSafeTargetFps))
launchRefreshRate = autoSafeTargetFps
chosenFrameRate = Math.min(chosenFrameRate, autoSafeTargetFps)
}
configuredStreamFrameRateFps = chosenFrameRate
configuredHudTargetFps = launchRefreshRate
lastPolarisDeviceCapabilities = com.papi.nova.manager.StreamSyncManager.buildDeviceCapabilities(
this,
currentDisplay,
decoderRenderer,
supportedVideoFormats,
displaySupportsHdr10,
isOnExternalDisplay
)
lastPolarisAppliedStreamSettings = com.papi.nova.manager.StreamSyncManager.buildAppliedStreamSettings(
configuredStreamBitrateKbps,
displayWidth,
displayHeight,
launchRefreshRate,
chosenFrameRate,
vDisplay,
willStreamHdr,
supportedVideoFormats,
prefConfig!!.videoFormat,
displayModeExplicit
)
try
{
lastPolarisAppliedStreamSettings?.put("profile_preference", launchProfilePreference)
}
catch (ignored:Exception) {}

var config:StreamConfiguration = StreamConfiguration.Builder()
.setResolution(
displayWidth,
displayHeight
)
.setLaunchRefreshRate(launchRefreshRate)
.setRefreshRate(chosenFrameRate)
.setVirtualDisplay(vDisplay)
.setDisplayModeExplicit(displayModeExplicit)
.setMirrorDesktop(mirrorDesktop)
.setForcePrivateAfterSteamClose(forcePrivateAfterSteamClose)
.setResolutionScaleFactor(prefConfig!!.resolutionScaleFactor)
.setApp(app)
.setEnableUltraLowLatency(prefConfig!!.enableUltraLowLatency)
.setForceFreshLaunch(forceFreshLaunch)
.setBitrate(configuredStreamBitrateKbps)
.setEnableSops(prefConfig!!.enableSops)
.setProfilePreference(launchProfilePreference)
.enableLocalAudioPlayback(prefConfig!!.playHostAudio)
.setMaxPacketSize(1392)
.setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO) // NvConnection will perform LAN and VPN detection
.setSupportedVideoFormats(supportedVideoFormats)
.setAttachedGamepadMask(gamepadMask)
.setClientRefreshRateX100((displayRefreshRate * 100).toInt())
.setAudioConfiguration(prefConfig!!.audioConfiguration)
.setColorSpace(decoderRenderer!!.getPreferredColorSpace())
.setColorRange(decoderRenderer!!.getPreferredColorRange())
.setPersistGamepadsAfterDisconnect(!prefConfig!!.multiController)
.build()

queuePolarisClientSettingsSnapshot(null)

 // Initialize the connection
	        val newConn = NvConnection(getApplicationContext(),
ComputerDetails.AddressTuple(host ?: "", port),
httpsPort, uniqueId ?: "", config,
PlatformBinding.getCryptoProvider(this), serverCert)
conn = newConn
newConn.setWatchOnlyRequested(watchOnlyRequested)
controllerHandler = ControllerHandler(this, newConn, this, prefConfig)
keyboardTranslator = KeyboardTranslator(prefConfig)

var inputManager:InputManager? = getSystemService(Context.INPUT_SERVICE) as InputManager
inputManager!!.registerInputDeviceListener(keyboardTranslator, null)

 // Initialize trackpad contexts
        for (i:Int in trackpadContextMap.indices)
{
trackpadContextMap[i] = TrackpadContext(newConn, i, prefConfig!!.trackpadSwapAxis, prefConfig!!.trackpadSensitivityX, prefConfig!!.trackpadSensitivityY)
}

if (Objects.equals(appUUID, NvApp.REMOTE_INPUT_UUID))
{
 // Force trackpad mode since we won't see anything on the screen
            isInputOnly = true
allowChangeMouseMode = false
applyMouseMode(2)
}
else
{
 // Initialize touch contexts based on preferences
            // The mouse mode preference is also read in PreferenceConfiguration to set the boolean flags
            initMouseMode()
}

if (prefConfig!!.onscreenController)
{
 // create virtual onscreen controller
            if (prefConfig!!.hideOSCWhenHasGamepad)
{
if (!controllerHandler!!.hasController())
{
initVirtualController()
}
}
else
{
initVirtualController()
}
}

 //特殊按键屏幕布局
        if (prefConfig!!.enableKeyboard)
{
initKeyboardController()
}

if (!decoderRenderer!!.isAvcSupported)
{
novaProgressOverlay?.dismiss()
if (spinner != null)
{
spinner!!.dismiss()
spinner = null
}

 // If we can't find an AVC decoder, we can't proceed
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title),
"This device or ROM doesn't support hardware accelerated H.264 playback.", true)
return
}

 // The connection will be started when the surface gets created
        //streamContainer.getHolder().addCallback(this);

        streamContainer!!.setOnSurfaceAvailable({ if (!attemptedConnection)
{
LimeLog.info("Surface is available, starting connection...")
attemptedConnection = true

 // Der Decoder erhält die jeweils aktive Oberfläche vom Container
                decoderRenderer!!.setRenderTarget(streamContainer!!.getSurface())

 // Starten Sie die NvConnection
                conn!!.start(AndroidAudioRenderer(getStreamAudioContext(), prefConfig!!.playHostAudio),
decoderRenderer!!, this@Game)
} })

gameMenuCallbacks = com.papi.nova.ui.NovaQuickMenu(this)

 // Register disconnect broadcast receiver for QS tile
        novaDisconnectReceiver = object:android.content.BroadcastReceiver() {
override fun onReceive(context:android.content.Context?, intent:android.content.Intent?) {
if (com.papi.nova.service.NovaQsTile.NOVA_DISCONNECT_ACTION.equals(intent!!.getAction()))
{
disconnect()
}
}
}
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
{
registerReceiver(novaDisconnectReceiver,
android.content.IntentFilter(com.papi.nova.service.NovaQsTile.NOVA_DISCONNECT_ACTION),
android.content.Context.RECEIVER_NOT_EXPORTED)
}
else
{
registerReceiver(novaDisconnectReceiver,
android.content.IntentFilter(com.papi.nova.service.NovaQsTile.NOVA_DISCONNECT_ACTION))
}

floatingMenuButton = findViewById(R.id.floatingMenuButton)
updateFloatingButtonVisibility(prefConfig!!.enableBackMenu && prefConfig!!.enableFloatingButton)
initFloatingButton()

overlayToggleButton = findViewById(R.id.overlayToggleZoomButton)
setupOverlayToggleButton()

 //fixed size + pacing without back-pressure on MTK
        try
{
var root:View? = findViewById(android.R.id.content)
 // Niente getIdentifier: troviamo la prima SurfaceView nel layout
            var streamSurfaceView:SurfaceView? = findFirstSurfaceViewFrom(root)

if (streamSurfaceView != null)
{
 // Avoid resizes/glitches that break the compositor
                var vw:Int = if ((prefConfig != null && prefConfig!!.width > 0)) prefConfig!!.width else displayWidth
var vh:Int = if ((prefConfig != null && prefConfig!!.height > 0)) prefConfig!!.height else displayHeight
try
{
streamSurfaceView!!.getHolder().setFixedSize(vw, vh)
}
catch (ignored:Throwable) {}

try
{
streamSurfaceView!!.setZOrderOnTop(false)
}
catch (ignored:Throwable) {}

try
{
streamSurfaceView!!.setZOrderMediaOverlay(false)
}
catch (ignored:Throwable) {}

 // 2) setFrameRate via reflection (compat < 30)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
{
var displayHz:Float = 60f
try
{
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
{
displayHz = currentDisplay!!.getMode().getRefreshRate()
}
else
{
displayHz = currentDisplay!!.getRefreshRate()
}
}
catch (ignored:Throwable) {}

var targetFps:Float = if (chosenFrameRate > 0f)
chosenFrameRate
else
(if ((prefConfig != null && prefConfig!!.fps > 0)) prefConfig!!.fps else displayHz)

var isMTKDevice:Boolean
try
{
var sum:String? = (android.os.Build.MANUFACTURER + " " + android.os.Build.HARDWARE + " " + android.os.Build.BOARD)
.lowercase(java.util.Locale.US)
isMTKDevice = sum!!.contains("mtk") || sum!!.contains("mediatek")
}
catch (t:Throwable) {
isMTKDevice = false
}

var surfaceFrameRate:Float = chooseSurfaceFrameRateHint(targetFps, displayHz)
var compat:Int = chooseSurfaceFrameRateCompatibility(targetFps, displayHz, isMTKDevice)

try
{
var m:java.lang.reflect.Method? = SurfaceView::class.java!!.getMethod("setFrameRate", Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
m!!.invoke(streamSurfaceView, surfaceFrameRate, compat)
if (Math.abs(surfaceFrameRate - targetFps) > 0.5f)
{
LimeLog.info(("Nova: Surface frame-rate hint " + surfaceFrameRate +
" Hz for " + targetFps + " FPS stream on " + displayHz + " Hz display"))
}
}
catch (ignored:Throwable) {}

}
}
}
catch (ignored:Throwable) {}

}

@SuppressLint("ClickableViewAccessibility")
private fun setupOverlayToggleButton() {
if (overlayToggleButton != null)
{
if (prefConfig!!.showOverlayZoomToggleButton)
{
overlayToggleButton!!.setVisibility(View.VISIBLE)

 // Set initial appearance based on current state
                updateZoomButtonAppearance()

 // Touch listener for drag and click
	                overlayToggleButton!!.setOnTouchListener { view, event -> when (event!!.getAction()) {
MotionEvent.ACTION_DOWN -> {
zoomButtonStartX = event!!.getRawX()
zoomButtonStartY = event!!.getRawY()
zoomButtonDX = view!!.getX() - event!!.getRawX()
zoomButtonDY = view!!.getY() - event!!.getRawY()
isZoomButtonMoving = false
return@setOnTouchListener true
}
MotionEvent.ACTION_MOVE -> {
var newX:Float = event!!.getRawX() + zoomButtonDX
var newY:Float = event!!.getRawY() + zoomButtonDY

 // Check if it's a move or just a tap
                            if ((Math.abs(event!!.getRawX() - zoomButtonStartX) > CLICK_ACTION_THRESHOLD || Math.abs(event!!.getRawY() - zoomButtonStartY) > CLICK_ACTION_THRESHOLD))
{
isZoomButtonMoving = true
}

 // Ensure the button stays within screen bounds
                            if (newX < 0) newX = 0f
if (newY < 0) newY = 0f

var maxOffsetX:Int = getWindow().getDecorView().getWidth() - view!!.getWidth()
if (newX > maxOffsetX)
{
newX = maxOffsetX.toFloat()
}

var maxOffsetY:Int = getWindow().getDecorView().getHeight() - view!!.getHeight()
if (newY > maxOffsetY)
{
newY = maxOffsetY.toFloat()
}

view!!.setX(newX)
view!!.setY(newY)
return@setOnTouchListener true
}
MotionEvent.ACTION_UP -> {
if (!isZoomButtonMoving)
{
 // It's a click event, toggle zoom mode
                                toggleZoomMode()
updateZoomButtonAppearance()
}
isZoomButtonMoving = false
return@setOnTouchListener true
}
else -> return@setOnTouchListener false
} }
}
else
{
overlayToggleButton!!.setVisibility(View.GONE)
}
}
}

private fun updateZoomButtonAppearance() {
if (overlayToggleButton != null)
{
 // Change background based on pan/zoom mode state
            overlayToggleButton!!.setBackgroundResource(if (isZoomModeEnabled)
R.drawable.floating_menu_button_active
else
R.drawable.floating_menu_button)
 // No need for alpha changes since the color indicates the state
            overlayToggleButton!!.setAlpha(1.0f)
}
}

private fun listenForExternalDisplayRemoval() {
if (externalDisplayListener != null) return

val displayManager:DisplayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
val listener:DisplayManager.DisplayListener = object : DisplayManager.DisplayListener {
override fun onDisplayAdded(displayId:Int) {
if (isStreamActive && !isFinishing()) {
LimeLog.info("Nova: Android companion display added id=$displayId stream_id=$streamingDisplayId")
showCompanionControls()
}
}
override fun onDisplayChanged(displayId:Int) {
decoderRenderer?.refreshDisplayParameters()
}
override fun onDisplayRemoved(displayId:Int) {
handleDisplayRemoved(displayId)
}
}
externalDisplayListener = listener
displayManager.registerDisplayListener(listener, null)
}

private fun stopListeningForExternalDisplayRemoval() {
val listener:DisplayManager.DisplayListener = externalDisplayListener ?: return
val displayManager:DisplayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
displayManager.unregisterDisplayListener(listener)
externalDisplayListener = null
}

private fun canMigrateCompanionMenuToStream():Boolean {
return isStreamActive && !isFinishing() && !isDestroyed && gameMenuCallbacks != null
}

private fun showQuickMenuOnStreamAfterCompanionClose() {
gameMenuCallbacks?.showMenu(null)
LimeLog.info("Nova: Android companion quick menu migrated to stream after companion close stream_display_id=$streamingDisplayId")
}

private fun closeCompanionControls(
migrateOpenMenuToStream:Boolean = false,
preserveReopenNotification:Boolean = false,
) {
if (preserveReopenNotification) {
ExternalDisplayControlPresentation.ensureCompanionControlsNotification(this)
} else {
NotificationManagerCompat.from(baseContext)
.cancel(ExternalDisplayControlPresentation.SECONDARY_SCREEN_NOTIFICATION_ID)
}
val presentation:ExternalDisplayControlHost? = externalDisplayControlPresentation
val shouldMigrateOpenMenu = migrateOpenMenuToStream &&
DualScreenQuickMenuPolicy.shouldMigrateCompanionMenu(
presentation?.isGameMenuOpen() == true,
dismissalRequestedByNova = false,
streamAvailable = canMigrateCompanionMenuToStream(),
)
if (shouldMigrateOpenMenu)
{
presentation?.hideGameMenu()
}
externalDisplayControlPresentation = null
if (lastQuickMenuInteractionDisplayId == companionControlDisplayId)
{
lastQuickMenuInteractionDisplayId = streamingDisplayId
}
companionControlDisplayId = INVALID_DISPLAY_ID
presentation?.dismissAfterCurrentCallback()
if (shouldMigrateOpenMenu)
{
showQuickMenuOnStreamAfterCompanionClose()
}
}

private fun closeCompanionControlsForDisplayRemoval() {
val preserveReopenNotification = CompanionControlLifecyclePolicy.shouldPreserveReopenNotification(
streamActive = isStreamActive,
dismissedByUser = companionControlsDismissedByUser,
)
closeCompanionControls(
migrateOpenMenuToStream = true,
preserveReopenNotification = preserveReopenNotification,
)
}

private fun handleDisplayRemoved(removedDisplayId:Int) {
when {
removedDisplayId == streamingDisplayId -> {
closeCompanionControls()
finish()
}
removedDisplayId == companionControlDisplayId -> closeCompanionControlsForDisplayRemoval()
else -> {
if (getCompanionControlDisplay() == null)
{
closeCompanionControlsForDisplayRemoval()
}
}
}
}

@SuppressLint("ClickableViewAccessibility")
private fun initFloatingButton() {
 // Touch listener for drag and click
        if (floatingMenuButton != null)
{
	floatingMenuButton!!.setOnTouchListener { view, event -> when (event!!.getAction()) {
MotionEvent.ACTION_DOWN -> {
floatingButtonStartX = event!!.getRawX()
floatingButtonStartY = event!!.getRawY()
floatingButtonDX = view!!.getX() - event!!.getRawX()
floatingButtonDY = view!!.getY() - event!!.getRawY()
isButtonMoving = false
return@setOnTouchListener true
}
MotionEvent.ACTION_MOVE -> {
var newX:Float = event!!.getRawX() + floatingButtonDX
var newY:Float = event!!.getRawY() + floatingButtonDY

 // Check if it's a move or just a tap
                        if ((Math.abs(event!!.getRawX() - floatingButtonStartX) > CLICK_ACTION_THRESHOLD || Math.abs(event!!.getRawY() - floatingButtonStartY) > CLICK_ACTION_THRESHOLD))
{
isButtonMoving = true
}

 // Ensure the button stays within screen bounds
                        if (newX < 0) newX = 0f
if (newY < 0) newY = 0f

var maxOffsetX:Int = getWindow().getDecorView().getWidth() - view!!.getWidth()
if (newX > maxOffsetX)
{
newX = maxOffsetX.toFloat()
}

var maxOffsetY:Int = getWindow().getDecorView().getHeight() - view!!.getHeight()
if (newY > maxOffsetY)
{
newY = maxOffsetY.toFloat()
}

view!!.setX(newX)
view!!.setY(newY)
return@setOnTouchListener true
}
MotionEvent.ACTION_UP -> {
if (!isButtonMoving)
{
 // It's a click event, show menu
                            showGameMenu(null)
}
isButtonMoving = false
return@setOnTouchListener true
}
else -> return@setOnTouchListener false
} }
}
}

private fun initKeyboardController() {
keyBoardController = KeyBoardController(conn, rootView as FrameLayout, this)
keyBoardController!!.refreshLayout()
keyBoardController!!.show()
}

private fun initVirtualController() {
virtualController = VirtualController(controllerHandler, rootView as FrameLayout, this)
virtualController!!.refreshLayout()
virtualController!!.show()
}

private fun initkeyBoardLayoutController() {
keyBoardLayoutController = KeyBoardLayoutController(rootView as FrameLayout, this, prefConfig)
keyBoardLayoutController!!.refreshLayout()
keyBoardLayoutController!!.show()
}

 //显示隐藏虚拟特殊按键
 fun toggleKeyboardController() {
if (keyBoardController == null)
{
initKeyboardController()
return
}
keyBoardController!!.toggleVisibility()
}
 fun toggleFullKeyboard() {
if (externalDisplayControlPresentation?.isHostShowing() == true)
{
externalDisplayControlPresentation?.toggleFullKeyboard()
return
}
if (keyBoardLayoutController == null)
{
initkeyBoardLayoutController()
return
}
keyBoardLayoutController!!.toggleVisibility()
}

 //显示隐藏虚拟手柄控制器
 fun toggleVirtualController() {
if (virtualController == null)
{
initVirtualController()
prefConfig!!.onscreenController = true
return
}
prefConfig!!.onscreenController = virtualController!!.switchShowHide() != 0
}

private fun setPreferredOrientationForActivity() {
var display:Display? = getActiveDisplay(this@Game, prefConfig)

 // For semi-square displays, we use more complex logic to determine which orientation to use (if any)
        if (PreferenceConfiguration.isSquarishScreen(display!!))
{
var desiredOrientation:Int = Configuration.ORIENTATION_UNDEFINED

 // OSC doesn't properly support portrait displays, so don't use it in portrait mode by default
            if (prefConfig!!.onscreenController)
{
desiredOrientation = Configuration.ORIENTATION_LANDSCAPE
}

 // For native resolution, we will lock the orientation to the one that matches the specified resolution
            if (PreferenceConfiguration.isNativeResolution(prefConfig!!.width, prefConfig!!.height))
{
if (displayWidth > displayHeight)
{
desiredOrientation = Configuration.ORIENTATION_LANDSCAPE
}
else
{
desiredOrientation = Configuration.ORIENTATION_PORTRAIT
}
}

if (desiredOrientation == Configuration.ORIENTATION_LANDSCAPE)
{
setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)
}
else if (desiredOrientation == Configuration.ORIENTATION_PORTRAIT)
{
setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT)
}
else
{
 // If we don't have a reason to lock to portrait or landscape, allow any orientation
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER)
}
}
else
{
 // Lock to current orientation
            if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE)
{
setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)
}
else
{
setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT)
}
}
}
override fun onConfigurationChanged(newConfig:Configuration) {
super.onConfigurationChanged(newConfig)

 // Set requested orientation for possible new screen size
        setPreferredOrientationForActivity()

if (virtualController != null)
{
 // Refresh layout of OSC for possible new screen size
            virtualController!!.refreshLayout()
}

if (keyBoardController != null)
{
keyBoardController!!.refreshLayout()
}

if (keyBoardLayoutController != null)
{
keyBoardLayoutController!!.refreshLayout()
}

 // Hide on-screen overlays in PiP mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
{
if (isInPictureInPictureMode())
{
isHidingOverlays = true

floatingButtonShown = floatingMenuButton!!.isShown()

if (floatingButtonShown)
{
floatingMenuButton!!.setVisibility(View.GONE)
}

overlayToggleZoomButtonShown = overlayToggleButton != null && overlayToggleButton!!.isShown()

if (overlayToggleZoomButtonShown)
{
overlayToggleButton!!.setVisibility(View.GONE)
}

if (virtualController != null)
{
virtualController!!.hide()
}

if (keyBoardController != null && keyBoardController!!.shown)
{
keyBoardController!!.hide(true)
}

if (keyBoardLayoutController != null && keyBoardLayoutController!!.shown)
{
keyBoardLayoutController!!.hide(true)
}

hideGameMenu()

performanceOverlayView!!.setVisibility(View.GONE)
notificationOverlayView!!.setVisibility(View.GONE)

 // Disable sensors while in PiP mode
                controllerHandler!!.disableSensors()

 // Update GameManager state to indicate we're in PiP (still gaming, but interruptible)
                UiHelper.notifyStreamEnteringPiP(this)
}
else
{
isHidingOverlays = false

if (floatingButtonShown)
{
floatingMenuButton!!.setVisibility(View.VISIBLE)
}

if (overlayToggleZoomButtonShown)
{
overlayToggleButton!!.setVisibility(View.VISIBLE)
}

 // Restore overlays to previous state when leaving PiP

                if (virtualController != null)
{
virtualController!!.show()
}

if (keyBoardController != null && keyBoardController!!.shown)
{
keyBoardController!!.show()
}

if (keyBoardLayoutController != null && keyBoardLayoutController!!.shown)
{
keyBoardLayoutController!!.show()
}

if (prefConfig!!.enablePerfOverlay)
{
performanceOverlayView!!.setVisibility(View.VISIBLE)
}

notificationOverlayView!!.setVisibility(requestedNotificationOverlayVisibility)

 // Enable sensors again after exiting PiP
                controllerHandler!!.enableSensors()

 // Update GameManager state to indicate we're out of PiP (gaming, non-interruptible)
                UiHelper.notifyStreamExitingPiP(this)
}
}
}

@TargetApi(Build.VERSION_CODES.O)
private fun getPictureInPictureParams(autoEnter:Boolean):PictureInPictureParams {
var view:View?
var hint:Rect?
if (prefConfig!!.videoScaleMode == PreferenceConfiguration.ScaleMode.FIT && streamContainer!!.getScaleX() == 1f)
{
view = streamContainer
}
else
{
view = rootView as View
}

var viewLocation:IntArray = IntArray(2)

view!!.getLocationOnScreen(viewLocation)

var left:Int = viewLocation[0]
var top:Int = viewLocation[1]
var width:Int = view!!.getWidth()
var height:Int = view!!.getHeight()
var aspectRatio:Rational = Rational(width, height)
hint = Rect(left, top, left + width, top + height)

var builder:PictureInPictureParams.Builder? = PictureInPictureParams.Builder()
.setAspectRatio(aspectRatio)
.setSourceRectHint(hint)

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
{
builder!!.setAutoEnterEnabled(autoEnter)
builder!!.setSeamlessResizeEnabled(true)
}

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
{
if (appName != null)
{
builder!!.setTitle(appName)
if (pcName != null)
{
builder!!.setSubtitle(pcName)
}
}
else if (pcName != null)
{
builder!!.setTitle(pcName)
}
}

return builder!!.build()
}
 fun updatePipAutoEnter() {
if (!prefConfig!!.enablePip || isOnExternalDisplay)
{
return
}

var autoEnter:Boolean = connected && suppressPipRefCount == 0

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
{
setPictureInPictureParams(getPictureInPictureParams(autoEnter))
}
else
{
autoEnterPip = autoEnter
}
}

 fun setMetaKeyCaptureState(enabled:Boolean) {
 // This uses custom APIs present on some Samsung devices to allow capture of
        // meta key events while streaming.
        try
{
var semWindowManager:Class<*>? = Class.forName("com.samsung.android.view.SemWindowManager")
var getInstanceMethod:Method? = semWindowManager!!.getMethod("getInstance")
var manager:Any? = getInstanceMethod!!.invoke(null)

if (manager != null)
{
	var requestMetaKeyEventMethod:Method? = semWindowManager!!.getDeclaredMethod(
	"requestMetaKeyEvent",
	ComponentName::class.java,
	Boolean::class.javaPrimitiveType
	)
requestMetaKeyEventMethod!!.invoke(manager, this.getComponentName(), enabled)
}
else
{
LimeLog.warning("SemWindowManager.getInstance() returned null")
}
}
catch (e:ClassNotFoundException) {
e!!.printStackTrace()
}
catch (e:NoSuchMethodException) {
e!!.printStackTrace()
}
catch (e:InvocationTargetException) {
e!!.printStackTrace()
}
catch (e:IllegalAccessException) {
e!!.printStackTrace()
}

}
override fun onUserLeaveHint() {
super.onUserLeaveHint()

 // PiP is only supported on Oreo and later, and we don't need to manually enter PiP on
        // Android S and later. On Android R, we will use onPictureInPictureRequested() instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
{
if (autoEnterPip && !isOnExternalDisplay)
{
try
{
 // This has thrown all sorts of weird exceptions on Samsung devices
                    // running Oreo. Just eat them and close gracefully on leave, rather
                    // than crashing.
                    enterPictureInPictureMode(getPictureInPictureParams(false))
}
catch (e:Exception) {
e!!.printStackTrace()
}

}
}
}

@TargetApi(Build.VERSION_CODES.R)
override fun onPictureInPictureRequested():Boolean {
 // Enter PiP when requested unless we're on Android 12 which supports auto-enter.
        if (autoEnterPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
{
enterPictureInPictureMode(getPictureInPictureParams(false))
}
return true
}
override fun onWindowFocusChanged(hasFocus:Boolean) {
super.onWindowFocusChanged(hasFocus)
logGameDisplayFocus(hasFocus)

 // We can't guarantee the state of modifiers keys which may have
        // lifted while focus was not on us. Clear the modifier state.
        this.modifierFlags = 0
if (!hasFocus)
{
fallbackNovaShortcutState.reset()
}

 // With Android native pointer capture, capture is lost when focus is lost,
        // so it must be requested again when focus is regained.
        inputCaptureProvider!!.onWindowFocusChanged(hasFocus)
}

@RequiresApi(Build.VERSION_CODES.Q)
override fun onTopResumedActivityChanged(isTopResumedActivity:Boolean) {
super.onTopResumedActivityChanged(isTopResumedActivity)
this.isTopResumedActivity = isTopResumedActivity
logGameDisplayFocus(hasWindowFocus())
}

private fun isRefreshRateEqualMatch(refreshRate:Float):Boolean {
var streamFps:Float = getConfiguredStreamFrameRateFps()
return (refreshRate >= streamFps && refreshRate <= streamFps + 3)
}

private fun isRefreshRateGoodMatch(refreshRate:Float):Boolean {
var streamFps:Float = getConfiguredStreamFrameRateFps()
return isWholeRefreshMultiple(refreshRate, streamFps.toDouble())
}

private fun shouldIgnoreInsetsForResolution(width:Int, height:Int):Boolean {
 // Never ignore insets for non-native resolutions
        if (!PreferenceConfiguration.isNativeResolution(width, height))
{
return false
}

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
{
var display:Display? = getActiveDisplay(this@Game, prefConfig)
for (candidate:Display.Mode? in display!!.getSupportedModes())
{
 // Ignore insets if this is an exact match for the display resolution
                if (((width == candidate!!.getPhysicalWidth() && height == candidate!!.getPhysicalHeight()) || (height == candidate!!.getPhysicalWidth() && width == candidate!!.getPhysicalHeight())))
{
return true
}
}
}

return false
}

private fun mayReduceRefreshRate():Boolean {
return (prefConfig!!.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS ||
prefConfig!!.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
(prefConfig!!.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED && prefConfig!!.reduceRefreshRate))
}

private fun shouldPreferExactRefreshRateForStream():Boolean {
return (prefConfig != null &&
getConfiguredStreamFrameRateFps() > 0f &&
getConfiguredStreamFrameRateFps() <= 60f &&
!preferStableRefreshMultipleForAutoSafe &&
!isOnExternalDisplay)
}

private fun isManualProfileOverride():Boolean {
return launchProfilePreference.isNotBlank() && !launchProfilePreference.equals("auto", ignoreCase = true)
}

private fun loadLaunchOptimization(appName:String?):JSONObject? {
if (!launchOptimizationJson.isNullOrBlank())
{
try
{
return JSONObject(launchOptimizationJson!!)
}
catch (e:Exception) {
LimeLog.warning("Nova: Ignoring invalid preflight optimization payload")
}
}
if (novaApiClient == null)
{
return null
}

var result:Array<JSONObject?> = arrayOfNulls<JSONObject?>(1)
var failure:Array<Exception?> = arrayOfNulls<Exception?>(1)
var thread:Thread = Thread({ try
{
var safeAppName:String = appName ?: ""
var preference:String = launchProfilePreference.takeIf { it.isNotBlank() } ?: getSharedPreferences("nova_prefs", MODE_PRIVATE)
.getString("ai_profile_preference_name_" + safeAppName, "auto") ?: "auto"
launchProfilePreference = preference
result[0] = novaApiClient!!.getOptimization(DeviceUtils.getModel(), safeAppName, preference)
}
catch (e:Exception) {
failure[0] = e
}
 }, "NovaLaunchOptimization")
thread.start()

try
{
thread.join(4500)
}
catch (e:InterruptedException) {
Thread.currentThread().interrupt()
return null
}

if (thread.isAlive())
{
LimeLog.warning("Nova: Launch optimization query timed out after 4500ms; starting with local defaults")
return null
}
if (failure[0] != null)
{
LimeLog.warning("Nova: Launch optimization query failed: " + failure[0]!!.message)
return null
}
val optimizationResult = result[0]
if (optimizationResult != null)
{
LimeLog.info(("Nova: Launch optimization loaded source=" + optimizationResult.optString("source", "unknown") +
" mode=" + optimizationResult.optString("display_mode", "")))
}
return optimizationResult
}

private fun getMaxSupportedRefreshRate(display:Display?):Float {
var maxRefreshRate:Float = display!!.getRefreshRate()

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
{
for (candidate:Display.Mode? in display!!.getSupportedModes())
{
maxRefreshRate = Math.max(maxRefreshRate, candidate!!.getRefreshRate())
}
}
else
{
for (candidate:Float in display!!.getSupportedRefreshRates())
{
maxRefreshRate = Math.max(maxRefreshRate, candidate)
}
}

return maxRefreshRate
}

private fun getMaxAllowedRefreshRate(display:Display?):Float {
var maxSupportedRefreshRate:Float = getMaxSupportedRefreshRate(display)
var peakRefreshRate:Float = getSystemRefreshRateSetting("peak_refresh_rate")
if (peakRefreshRate > 0f && maxSupportedRefreshRate > 0f)
{
return Math.min(maxSupportedRefreshRate, peakRefreshRate)
}
return if (peakRefreshRate > 0f) peakRefreshRate else maxSupportedRefreshRate
}

private fun getSystemRefreshRateSetting(key:String?):Float {
try
{
return android.provider.Settings.System.getFloat(getContentResolver(), key)
}
catch (ignored:Exception) {
return 0f
}

}

private fun getSupportedRefreshRates(display:Display?):FloatArray? {
if (display == null)
{
return FloatArray(0)
}

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
{
var modes:Array<Display.Mode?>? = display!!.getSupportedModes()
var refreshRates:FloatArray = FloatArray(modes!!.size)
for (i:Int in modes!!.indices)
{
refreshRates[i] = modes!![i]!!.getRefreshRate()
}
return refreshRates
}

return display!!.getSupportedRefreshRates()
}

private fun getConfiguredStreamFrameRateFps():Float {
if (configuredStreamFrameRateFps > 0f)
{
return configuredStreamFrameRateFps
}
if (prefConfig != null && prefConfig!!.fps > 0)
{
return prefConfig!!.fps
}
return if (desiredRefreshRate > 0f) desiredRefreshRate else 60f
}

private fun chooseSurfaceFrameRateHint(streamFps:Float, displayHz:Float):Float {
if (streamFps <= 0f)
{
return if (displayHz > 0f) displayHz else 60f
}
if ((displayHz > 0f &&
displayHz > streamFps + 0.5f &&
isWholeRefreshMultiple(displayHz, streamFps.toDouble())))
{
return displayHz
}
return if (displayHz > 0f) Math.min(streamFps, displayHz) else streamFps
}

private fun chooseSurfaceFrameRateCompatibility(streamFps:Float, displayHz:Float, isMTKDevice:Boolean):Int {
if ((isMTKDevice || ((displayHz > 0f &&
displayHz > streamFps + 0.5f &&
isWholeRefreshMultiple(displayHz, streamFps.toDouble())))))
{
return Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
}
return Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
}

private fun prepareDisplayForRendering(currentDisplay:Display?):Float {
var windowLayoutParams:WindowManager.LayoutParams? = getWindow().getAttributes()
var displayRefreshRate:Float

 // On M, we can explicitly set the optimal display mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
{
var bestMode:Display.Mode? = currentDisplay!!.getMode()
var isNativeResolutionStream:Boolean = PreferenceConfiguration.isNativeResolution(prefConfig!!.width, prefConfig!!.height)
var preferExactRefreshRate:Boolean = shouldPreferExactRefreshRateForStream()
var preferStableRefreshMultiple:Boolean = preferStableRefreshMultipleForAutoSafe && !isOnExternalDisplay
var refreshRateIsGood:Boolean = isRefreshRateGoodMatch(bestMode!!.getRefreshRate())
var refreshRateIsEqual:Boolean = isRefreshRateEqualMatch(bestMode!!.getRefreshRate())

LimeLog.info(("Current display mode: " + bestMode!!.getPhysicalWidth() + "x" +
bestMode!!.getPhysicalHeight() + "x" + bestMode!!.getRefreshRate()))
if (preferExactRefreshRate)
{
LimeLog.info("Preferring exact display refresh rate for " + getConfiguredStreamFrameRateFps() + " FPS stream")
}
else if (preferStableRefreshMultiple)
{
LimeLog.info(("Preferring lowest stable display refresh multiple for Auto Safe " +
getConfiguredStreamFrameRateFps() + " FPS stream"))
}

for (candidate:Display.Mode? in currentDisplay!!.getSupportedModes())
{
var refreshRateReduced:Boolean = candidate!!.getRefreshRate() < bestMode!!.getRefreshRate()
var resolutionReduced:Boolean = (candidate!!.getPhysicalWidth() < bestMode!!.getPhysicalWidth() || candidate!!.getPhysicalHeight() < bestMode!!.getPhysicalHeight())
var resolutionFitsStream:Boolean = (candidate!!.getPhysicalWidth() >= prefConfig!!.width && candidate!!.getPhysicalHeight() >= prefConfig!!.height)
var candidateRefreshRateIsGood:Boolean = isRefreshRateGoodMatch(candidate!!.getRefreshRate())
var candidateRefreshRateIsEqual:Boolean = isRefreshRateEqualMatch(candidate!!.getRefreshRate())

LimeLog.info(("Examining display mode: " + candidate!!.getPhysicalWidth() + "x" +
candidate!!.getPhysicalHeight() + "x" + candidate!!.getRefreshRate()))

if (candidate!!.getPhysicalWidth() > 4096 && prefConfig!!.width <= 4096)
{
 // Avoid resolutions options above 4K to be safe
                    continue
}

 // On non-4K streams, we force the resolution to never change unless it's above
                // 60 FPS, which may require a resolution reduction due to HDMI bandwidth limitations,
                // or it's a native resolution stream.
                if (prefConfig!!.width < 3840 && prefConfig!!.fps <= 60 && !isNativeResolutionStream)
{
if ((currentDisplay!!.getMode().getPhysicalWidth() != candidate!!.getPhysicalWidth() || currentDisplay!!.getMode().getPhysicalHeight() != candidate!!.getPhysicalHeight()))
{
continue
}
}

 // Make sure the resolution doesn't regress unless if it's over 60 FPS
                // where we may need to reduce resolution to achieve the desired refresh rate.
                if (resolutionReduced && !(prefConfig!!.fps > 60 && resolutionFitsStream))
{
continue
}

if (mayReduceRefreshRate() && refreshRateIsEqual && !isRefreshRateEqualMatch(candidate!!.getRefreshRate()))
{
 // If we had an equal refresh rate and this one is not, skip it. In min latency
                    // mode, we want to always prefer the highest frame rate even though it may cause
                    // microstuttering.
                    continue
}
else if (refreshRateIsGood)
{
 // We've already got a good match, so if this one isn't also good, it's not
                    // worth considering at all.
                    if (!candidateRefreshRateIsGood)
{
continue
}

if (preferStableRefreshMultiple)
{
if (candidate!!.getRefreshRate() >= bestMode!!.getRefreshRate() - 0.5f)
{
continue
}
}
else if (preferExactRefreshRate)
{
if (refreshRateIsEqual && !candidateRefreshRateIsEqual)
{
continue
}

if (refreshRateIsEqual && candidateRefreshRateIsEqual)
{
if (candidate!!.getRefreshRate() > bestMode!!.getRefreshRate())
{
continue
}
}
else if (!candidateRefreshRateIsEqual && refreshRateReduced)
{
continue
}
}
else if (mayReduceRefreshRate())
{
 // User asked for the lowest possible refresh rate, so don't raise it if we
                        // have a good match already
                        if (candidate!!.getRefreshRate() > bestMode!!.getRefreshRate())
{
continue
}
}
else
{
 // User asked for the highest possible refresh rate, so don't reduce it if we
                        // have a good match already
                        if (refreshRateReduced)
{
continue
}
}
}
else if (!candidateRefreshRateIsGood)
{
 // We didn't have a good match and this match isn't good either, so just don't
                    // reduce the refresh rate.
                    if (refreshRateReduced)
{
continue
}
}
else
{
 // We didn't have a good match and this match is good. Prefer this refresh rate
                    // even if it reduces the refresh rate. Lowering the refresh rate can be beneficial
                    // when streaming a 60 FPS stream on a 90 Hz device. We want to select 60 Hz to
                    // match the frame rate even if the active display mode is 90 Hz.
                }

bestMode = candidate
refreshRateIsGood = isRefreshRateGoodMatch(candidate!!.getRefreshRate())
refreshRateIsEqual = isRefreshRateEqualMatch(candidate!!.getRefreshRate())
}

LimeLog.info(("Best display mode: " + bestMode!!.getPhysicalWidth() + "x" +
bestMode!!.getPhysicalHeight() + "x" + bestMode!!.getRefreshRate()))
lastClientPresentationRefreshRate = bestMode!!.getRefreshRate()
lastClientPresentationDisplayModeId = bestMode!!.getModeId()
lastClientPresentationDisplayMode = "${bestMode!!.getPhysicalWidth()}x${bestMode!!.getPhysicalHeight()}x${bestMode!!.getRefreshRate()}"

 // Only apply new window layout parameters if we've actually changed the display mode
            if (currentDisplay!!.getMode().getModeId() != bestMode!!.getModeId())
{
 // If we only changed refresh rate and we're on an OS that supports Surface.setFrameRate()
                // use that instead of using preferredDisplayModeId to avoid the possibility of triggering
                // bugs that can cause the system to switch from 4K60 to 4K24 on Chromecast 4K.
                if ((prefConfig!!.enforceDisplayMode ||
preferStableRefreshMultiple ||
Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
currentDisplay!!.getMode().getPhysicalWidth() != bestMode!!.getPhysicalWidth() ||
currentDisplay!!.getMode().getPhysicalHeight() != bestMode!!.getPhysicalHeight()))
{
 // Apply the display mode change
                    windowLayoutParams!!.preferredDisplayModeId = bestMode!!.getModeId()
getWindow().setAttributes(windowLayoutParams)
}
else
{
LimeLog.info("Using setFrameRate() instead of preferredDisplayModeId due to matching resolution")
}
}
else
{
LimeLog.info("Current display mode is already the best display mode")
}

displayRefreshRate = bestMode!!.getRefreshRate()
}
else
{
var bestRefreshRate:Float = currentDisplay!!.getRefreshRate()
for (candidate:Float in currentDisplay!!.getSupportedRefreshRates())
{
LimeLog.info("Examining refresh rate: " + candidate)

if (candidate > bestRefreshRate)
{
 // Ensure the frame rate stays around 60 Hz for <= 60 FPS streams
                    if (prefConfig!!.fps <= 60)
{
if (candidate >= 63)
{
continue
}
}

bestRefreshRate = candidate
}
}

LimeLog.info("Selected refresh rate: " + bestRefreshRate)
lastClientPresentationRefreshRate = bestRefreshRate
lastClientPresentationDisplayModeId = 0
lastClientPresentationDisplayMode = "refresh_rate:" + bestRefreshRate
windowLayoutParams!!.preferredRefreshRate = bestRefreshRate
displayRefreshRate = bestRefreshRate

 // Apply the refresh rate change
            getWindow().setAttributes(windowLayoutParams)
}// On L, we can at least tell the OS that we want a refresh rate

 // Until Marshmallow, we can't ask for a 4K display mode, so we'll
        // need to hint the OS to provide one.
        var aspectRatioMatch:Boolean = false
if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
{
 // We'll calculate whether we need to scale by aspect ratio. If not, we'll use
            // setFixedSize so we can handle 4K properly. The only known devices that have
            // >= 4K screens have exactly 4K screens, so we'll be able to hit this good path
            // on these devices. On Marshmallow, we can start changing to 4K manually but no
            // 4K devices run 6.0 at the moment.
            var screenSize:Point = Point(0, 0)
currentDisplay!!.getSize(screenSize)

	var screenAspectRatio:Double = screenSize.y.toDouble() / screenSize.x
var streamAspectRatio:Double = (displayHeight.toDouble()) / displayWidth
if (Math.abs(screenAspectRatio - streamAspectRatio) < 0.001 || isOnExternalDisplay)
{
LimeLog.info("Stream has compatible aspect ratio with output display")
aspectRatioMatch = true
}
}

 // Don't do setFixedSize since it might not update the view dimensions correctly when entering PiP mode
        if (!(prefConfig!!.videoScaleMode == PreferenceConfiguration.ScaleMode.STRETCH || aspectRatioMatch))
{
 // Set the surface to scale based on the aspect ratio of the stream
            streamContainer!!.setDesiredAspectRatio(displayWidth.toDouble() / displayHeight.toDouble())
streamContainer!!.setFillDisplay(prefConfig!!.videoScaleMode == PreferenceConfiguration.ScaleMode.FILL)
LimeLog.info("surfaceChanged-->" + displayWidth.toDouble() / displayHeight.toDouble())
LimeLog.info("scaleMode-->" + prefConfig!!.videoScaleMode)
}

 // Set the desired refresh rate that will get passed into setFrameRate() later
        desiredRefreshRate = displayRefreshRate

if ((getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)
|| isOnExternalDisplay))
{// TVs may take a few moments to switch refresh rates, and we can probably assume
 // it will be eventually activated.
            // external displays cant be compared with displaymanager currents display refreshrate
            // TODO: Improve this
            return displayRefreshRate
}
else
{
 // Use the lower of the current refresh rate and the selected refresh rate.
            // The preferred refresh rate may not actually be applied (ex: Battery Saver mode).
            return Math.min(currentDisplay!!.getRefreshRate(), displayRefreshRate)
}
}

private fun hideSystemUi(delay:Int) {
var h:Handler? = getWindow().getDecorView().getHandler()
if (h != null)
{
h!!.removeCallbacks(hideSystemUi)
h!!.postDelayed(hideSystemUi, delay.toLong())
}
}

@TargetApi(Build.VERSION_CODES.N)
override fun onMultiWindowModeChanged(isInMultiWindowMode:Boolean) {
super.onMultiWindowModeChanged(isInMultiWindowMode)

 // In multi-window, we don't want to use the full-screen layout
        // flag. It will cause us to collide with the system UI.
        // This function will also be called for PiP so we can cover
        // that case here too.
        if (isInMultiWindowMode)
{
getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
decoderRenderer!!.notifyVideoBackground()
}
else
{
getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
decoderRenderer!!.notifyVideoForeground()
}

 // Correct the system UI visibility flags
        hideSystemUi(50)
}

override fun onDestroy() {
super.onDestroy()
stopListeningForExternalDisplayRemoval()

 // Nova: clean up Polaris integration
        stopPolarisLiveSessionStatusRefresh()
runtimeTasks.cancelAll()
stopCursorVisibilitySync()
if (novaEventSource != null) novaEventSource!!.stop()
if (novaProgressOverlay != null) novaProgressOverlay!!.dismiss()
if (novaLockScreenOverlay != null) {
novaLockScreenOverlay!!.dismiss()
novaLockScreenOverlay!!.destroy()
}
if (novaReconnectOverlay != null) novaReconnectOverlay!!.dismiss()
novaResilienceManager?.shutdown()
com.papi.nova.jni.PolarisNativeHook.unregister()
com.papi.nova.manager.FeatureFlagManager.reset()

if (novaDisconnectReceiver != null)
{
try
{
unregisterReceiver(novaDisconnectReceiver)
}
catch (ignored:Exception) {}

}
isStreamActive = false
instance = null
timerHandler!!.removeCallbacksAndMessages(null)

if (prefConfig!!.enableFullExDisplay) closeCompanionControls()

if (controllerHandler != null)
{
controllerHandler!!.destroy()
}
if (keyboardTranslator != null)
{
var inputManager:InputManager? = getSystemService(Context.INPUT_SERVICE) as InputManager
inputManager!!.unregisterInputDeviceListener(keyboardTranslator)
}

if (lowLatencyWifiLock != null)
{
lowLatencyWifiLock!!.release()
}
if (highPerfWifiLock != null)
{
highPerfWifiLock!!.release()
}

 // Save zoom/pan before other cleanup
        if (prefConfig != null && prefConfig!!.rememberZoomPan && panZoomHandler != null)
{
var basePrefs:SharedPreferences? = PreferenceManager.getDefaultSharedPreferences(this)
basePrefs!!.edit()
.putFloat("number_zoom_scale", panZoomHandler!!.getScaleFactor())
.putFloat("number_pan_offset_x", panZoomHandler!!.getChildX())
.putFloat("number_pan_offset_y", panZoomHandler!!.getChildY())
.apply()
}

if (connectedToUsbDriverService)
{
 // Unbind from the discovery service
            unbindService(usbDriverServiceConnection)
}

 // Destroy the capture provider
        if (inputCaptureProvider != null)
{
inputCaptureProvider!!.destroy()
}
if (streamContainer != null)
{
streamContainer!!.onDestroy()
}
}

override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<String>, grantResults:IntArray) {
super.onRequestPermissionsResult(requestCode, permissions, grantResults)
if (requestCode == ExternalDisplayControlPresentation.NOTIFICATION_PERMISSION_REQUEST_CODE)
{
val granted:Boolean = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
ExternalDisplayControlPresentation.onCompanionNotificationPermissionResult(
this,
granted,
!isFinishing() && isStreamActive && shouldLaunchCompanionControls()
)
}
}

override fun onNewIntent(intent:Intent?) {
super.onNewIntent(intent)

if (isDisconnectIntent(intent))
{
disconnect()
return
}

if (intent != null) {
val requestedDisplayId = intent.getIntExtra(EXTRA_DISPLAY_ID, streamingDisplayId)
if (AndroidStreamDisplayTarget.requiresGameRecreation(streamingDisplayId, requestedDisplayId)) {
setIntent(intent)
LimeLog.info("Nova: Relaunching stream for Android display change from=$streamingDisplayId to=$requestedDisplayId")
relaunchStream()
return
}
}
setIntent(intent)
}

override fun onResume() {
super.onResume()
if (companionControlsDismissedByUser && isStreamActive && !isFinishing()) {
showCompanionControls()
}
updateCompanionCommandDeck()
}

override fun onPause() {
if (isFinishing())
{
 // Stop any further input device notifications before we lose focus (and pointer capture)
            if (controllerHandler != null)
{
controllerHandler!!.stop()
}

 // Ungrab input to prevent further input device notifications
            setInputGrabState(false)
}

super.onPause()
}

override fun onStop() {
super.onStop()

SpinnerDialog.closeDialogs(this)
Dialog.closeDialogs()

if (virtualController != null)
{
virtualController!!.hide()
}
if (keyBoardController != null)
{
keyBoardController!!.hide()
}

if (keyBoardLayoutController != null)
{
keyBoardLayoutController!!.hide()
}

if (conn != null)
{
var videoFormat:Int = decoderRenderer!!.activeVideoFormat

displayedFailureDialog = true
if (!hostSessionEnded)
{
prepareBackgroundResumeWindow()
}
stopConnection()
var message:String? = null
var selectedVideoFormat:String = ""

var averageEndToEndLat:Int = decoderRenderer!!.getAverageEndToEndLatency()
var averageDecoderLat:Int = decoderRenderer!!.getAverageDecoderLatency()

if (averageEndToEndLat > 0)
{
message = getResources().getString(R.string.conn_client_latency) + " " + averageEndToEndLat + " ms"
if (averageDecoderLat > 0)
{
message += " (" + getResources().getString(R.string.conn_client_latency_hw) + " " + averageDecoderLat + " ms)"
}
}
else if (averageDecoderLat > 0)
{
message = getResources().getString(R.string.conn_hardware_latency) + " " + averageDecoderLat + " ms"
}

 // Add the video codec to the post-stream toast
            selectedVideoFormat += " ["

if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H264) != 0)
{
selectedVideoFormat += "H.264"
}
else if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_H265) != 0)
{
selectedVideoFormat += "HEVC"
}
else if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0)
{
selectedVideoFormat += "AV1"
}
else
{
selectedVideoFormat += "UNKNOWN"
}

if ((videoFormat and MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0)
{
selectedVideoFormat += " HDR"
}

selectedVideoFormat += "]"

if (message != null)
{
message += selectedVideoFormat
}

if (message != null)
{
if (prefConfig!!.enableLatencyToast)
{
Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
}

 // Clear the tombstone count if we terminated normally
            if (!reportedCrash && tombstonePrefs!!.getInt("CrashCount", 0) != 0)
{
tombstonePrefs!!.edit()
.putInt("CrashCount", 0)
.putInt("LastNotifiedCrashCount", 0)
.apply()
}
if (prefConfig!!.enablePerfLogging && decoderRenderer!!.performanceWasTracked() == true)
{
PerformanceDataTracker().savePerformanceStatistics(
getBaseContext(),
Build.MODEL,
Build.VERSION.SDK_INT.toString(),
BuildConfig.VERSION_NAME,
selectedVideoFormat,
decoderRenderer!!.getMinDecoderLatency(),
decoderRenderer!!.getMinDecoderLatencyFullLog(),
java.lang.String.valueOf((prefConfig!!.bitrate / 1000)),
"${displayWidth}x${displayHeight}",
"${prefConfig!!.fps} hz",
"${decoderRenderer!!.getAverageDecoderLatency()} ms",
PreferenceConfiguration.getSelectedFramePacingName(getBaseContext()),
formatCurrentTime(System.currentTimeMillis())
)
}

}

finish()
}

private fun setInputGrabState(grab:Boolean) {
 // Grab/ungrab the mouse cursor
        if (grab)
{
inputCaptureProvider!!.enableCapture()

 // Enabling capture may hide the cursor again, so
            // we will need to show it again.
            if (cursorVisible)
{
inputCaptureProvider!!.showCursor()
}
}
else
{
inputCaptureProvider!!.disableCapture()
}

 // Grab/ungrab system keyboard shortcuts
        setMetaKeyCaptureState(grab)

grabbedInput = grab
}

 // Returns true if the key stroke was consumed
    private fun handleSpecialKeys(androidKeyCode:Int, down:Boolean):Boolean {
var modifierMask:Int = 0
var nonModifierKeyCode:Int = KeyEvent.KEYCODE_UNKNOWN

if ((androidKeyCode == KeyEvent.KEYCODE_CTRL_LEFT || androidKeyCode == KeyEvent.KEYCODE_CTRL_RIGHT))
{
	modifierMask = KeyboardPacket.MODIFIER_CTRL.toInt()
}
else if ((androidKeyCode == KeyEvent.KEYCODE_SHIFT_LEFT || androidKeyCode == KeyEvent.KEYCODE_SHIFT_RIGHT))
{
	modifierMask = KeyboardPacket.MODIFIER_SHIFT.toInt()
}
else if ((androidKeyCode == KeyEvent.KEYCODE_ALT_LEFT || androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT))
{
	modifierMask = KeyboardPacket.MODIFIER_ALT.toInt()
}
else if ((androidKeyCode == KeyEvent.KEYCODE_META_LEFT || androidKeyCode == KeyEvent.KEYCODE_META_RIGHT))
{
	modifierMask = KeyboardPacket.MODIFIER_META.toInt()
}
else
{
nonModifierKeyCode = androidKeyCode
}

if (down)
{
this.modifierFlags = this.modifierFlags or modifierMask
}
else
{
this.modifierFlags = this.modifierFlags and modifierMask.inv()
}

 // Handle the special combos on the key up
        if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN)
{
if (specialKeyCode == androidKeyCode)
{
 // If this is a key up for the special key itself, eat that because the host never saw the original key down
                return true
}
else if (modifierFlags != 0)
{
 // While we're waiting for modifiers to come up, eat all key downs and allow all key ups to pass
                return down
}
else
{
 // When all modifiers are up, perform the special action
                when (specialKeyCode) {
 // Toggle input grab
                    KeyEvent.KEYCODE_Z -> {
var h:Handler? = getWindow().getDecorView().getHandler()
if (h != null)
{
h!!.postDelayed(toggleGrab, 250)
}
}

 // Quit
                    KeyEvent.KEYCODE_Q -> finish()

 // Toggle cursor visibility
                    KeyEvent.KEYCODE_C -> {
if (!grabbedInput)
{
inputCaptureProvider!!.enableCapture()
grabbedInput = true
}
setLocalCursorVisible(!cursorVisible)
}

else -> {}
}

 // Reset special key state
                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
waitingForAllModifiersUp = false
}
}
else if ((((modifierFlags and (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt())) == (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt())) && (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)))
{
when (androidKeyCode) {
KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_C -> {
 // Remember that a special key combo was activated, so we can consume all key
                    // events until the modifiers come up
                    specialKeyCode = androidKeyCode
waitingForAllModifiersUp = true
return true
}

else ->
 // This isn't a special combo that we consume on the client side
                    return false
}
}// Check if Ctrl+Alt+Shift is down when a non-modifier key is pressed

 // Not a special combo
        return false
}

private fun handleFallbackNovaShortcut(event:KeyEvent, down:Boolean):Boolean {
if (event.getDeviceId() > 0)
{
return false
}

val keyCode = event.getKeyCode()
val action = if (down)
{
fallbackNovaShortcutState.onButtonDown(keyCode, event.getRepeatCount())
}
else
{
fallbackNovaShortcutState.onButtonUp(keyCode)
}

when (action) {
NovaControllerShortcutAction.OPEN_QUICK_MENU -> {
showGameMenu(null)
return true
}
NovaControllerShortcutAction.CYCLE_NOVA_HUD -> {
cycleNovaHudFromController()
return true
}
NovaControllerShortcutAction.DEFER_GUIDE,
NovaControllerShortcutAction.CONSUME_CHORD_BUTTON,
-> return true
NovaControllerShortcutAction.FORWARD_GUIDE_TO_HOST,
NovaControllerShortcutAction.PASS_THROUGH_GUIDE_TAP,
NovaControllerShortcutAction.NONE,
-> return false
}
}

 // We cannot simply use modifierFlags for all key event processing, because
    // some IMEs will not generate real key events for pressing Shift. Instead
    // they will simply send key events with isShiftPressed() returning true,
    // and we will need to send the modifier flag ourselves.
    private fun getModifierState(event:KeyEvent?):Byte {
 // Start with the global modifier state to ensure we cover the case
        // detailed in https://github.com/moonlight-stream/moonlight-android/issues/840
        var modifier:Byte = modifierState
if (event!!.isShiftPressed())
{
	modifier = (modifier.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt()).toByte()
}
if (event!!.isCtrlPressed())
{
	modifier = (modifier.toInt() or KeyboardPacket.MODIFIER_CTRL.toInt()).toByte()
}
if (event!!.isAltPressed())
{
	modifier = (modifier.toInt() or KeyboardPacket.MODIFIER_ALT.toInt()).toByte()
}
if (event!!.isMetaPressed())
{
	modifier = (modifier.toInt() or KeyboardPacket.MODIFIER_META.toInt()).toByte()
}
return modifier
}
override fun onKeyDown(keyCode:Int, event:KeyEvent):Boolean {
return handleKeyDown(event) || super.onKeyDown(keyCode, event)
}
override fun handleKeyDown(event:KeyEvent):Boolean {
 // Pass-through virtual navigation keys
        if ((event!!.getFlags() and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0)
{
return false
}

var deviceId:Int = event!!.getDeviceId()
if (handleFallbackNovaShortcut(event, down = true))
{
return true
}
if (prefConfig!!.ignoreSynthEvents && deviceId <= 0)
{
return false
}

 // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click. This event WILL repeat if
        // the right mouse button is held down, so we ignore those.
        var eventSource:Int = event!!.getSource()
if ((((eventSource == InputDevice.SOURCE_MOUSE || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE)) && event!!.getKeyCode() == KeyEvent.KEYCODE_BACK))
{

 // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig!!.mouseNavButtons)
{
conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
}

 // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true
}

var handled:Boolean = false

if (ControllerHandler.isGameControllerDevice(event!!.getDevice()))
{
 // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler!!.handleButtonDown(event)
}

 // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled)
{
 // Let this method take duplicate key down events
            if (handleSpecialKeys(event!!.getKeyCode(), true))
{
return true
}

 // Pass through keyboard input if we're not grabbing
            if (!grabbedInput)
{
return false
}

 // We'll send it as a raw key event if we have a key mapping, otherwise we'll send it
            // as UTF-8 text (if it's a printable character).
            var translated:Short = keyboardTranslator!!.translate(event!!.getKeyCode(), event!!.getScanCode(), deviceId)
if (translated.toInt() == 0)
{
if (prefConfig!!.backAsMeta && event!!.getKeyCode() == KeyEvent.KEYCODE_BACK)
{
translated = 0x5b // Meta key
}
else
{
 // Make sure it has a valid Unicode representation and it's not a dead character
                    // (which we don't support). If those are true, we can send it as UTF-8 text.
                    //
                    // NB: We need to be sure this happens before the getRepeatCount() check because
                    // UTF-8 events don't auto-repeat on the host side.
                    var unicodeChar:Int = event!!.getUnicodeChar()
if ((unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0)
{
conn!!.sendUtf8Text("" + unicodeChar.toChar())
return true
}

return false
}
}

 // Eat repeat down events
            if (event!!.getRepeatCount() > 0)
{
return true
}

conn!!.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, getModifierState(event),
if (keyboardTranslator!!.hasNormalizedMapping(event.getKeyCode(), deviceId)) 0.toByte() else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
}

return true
}
override fun onKeyUp(keyCode:Int, event:KeyEvent):Boolean {
if (keyCode == KeyEvent.KEYCODE_BACK)
{
val eventSource:Int = event.getSource()
val companionBackOrigin:Int? = DualScreenQuickMenuPolicy.legacyCompanionBackOrigin(
companionDisplayId = companionControlDisplayId.takeIf { it != INVALID_DISPLAY_ID },
lastInteractionDisplayId = lastQuickMenuInteractionDisplayId.takeIf { it != INVALID_DISPLAY_ID },
companionHasWindowFocus = companionControlHasWindowFocus,
inputDeviceId = event.getDeviceId(),
isMouseInput = eventSource == InputDevice.SOURCE_MOUSE || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE,
ignoreSyntheticEvents = prefConfig!!.ignoreSynthEvents,
sendMetaOnBack = prefConfig!!.backAsMeta,
)
if (companionBackOrigin != null &&
externalDisplayControlPresentation?.handleBackFromOwningGame() == true)
{
return true
}
}
return handleKeyUp(event) || super.onKeyUp(keyCode, event)
}
override fun handleKeyUp(event:KeyEvent):Boolean {
 // Pass-through virtual navigation keys
        if ((event!!.getFlags() and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0)
{
return false
}

var deviceId:Int = event!!.getDeviceId()
if (handleFallbackNovaShortcut(event, down = false))
{
return true
}
if (prefConfig!!.ignoreSynthEvents && deviceId <= 0)
{
return false
}

 // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click.
        var eventSource:Int = event!!.getSource()
if ((((eventSource == InputDevice.SOURCE_MOUSE || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE)) && event!!.getKeyCode() == KeyEvent.KEYCODE_BACK))
{

 // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig!!.mouseNavButtons)
{
conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
}

 // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true
}

var handled:Boolean = false
if (ControllerHandler.isGameControllerDevice(event!!.getDevice()))
{
 // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler!!.handleButtonUp(event)
}

 // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled)
{
if (handleSpecialKeys(event!!.getKeyCode(), false))
{
return true
}

 // Pass through keyboard input if we're not grabbing
            if (!grabbedInput)
{
return false
}

var translated:Short = keyboardTranslator!!.translate(event!!.getKeyCode(), event!!.getScanCode(), deviceId)
if (translated.toInt() == 0)
{
if (prefConfig!!.backAsMeta && event!!.getKeyCode() == KeyEvent.KEYCODE_BACK)
{
translated = 0x5b // Meta key
}
else
{
 // If we sent this event as UTF-8 on key down, also report that it was handled
                    // when we get the key up event for it.
                    var unicodeChar:Int = event!!.getUnicodeChar()
return (unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0
}
}

conn!!.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, getModifierState(event),
if (keyboardTranslator!!.hasNormalizedMapping(event.getKeyCode(), deviceId)) 0.toByte() else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
}

return true
}
override fun onKeyMultiple(keyCode:Int, repeatCount:Int, event:KeyEvent?):Boolean {
return handleKeyMultiple(event) || super.onKeyMultiple(keyCode, repeatCount, event)
}

 fun handleKeyMultiple(event:KeyEvent?):Boolean {
 // We can receive keys from a software keyboard that don't correspond to any existing
        // KEYCODE value. Android will give those to us as an ACTION_MULTIPLE KeyEvent.
        //
        // Despite the fact that the Android docs say this is unused since API level 29, these
        // events are still sent as of Android 13 for the above case.
        //
        // For other cases of ACTION_MULTIPLE, we will not report those as handled so hopefully
        // they will be passed to us again as regular singular key events.
        if (event!!.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN || event!!.getCharacters() == null)
{
return false
}

conn!!.sendUtf8Text(event!!.getCharacters())
return true
}

 fun sendKeys(keys:ShortArray?) {
	var modifier:ByteArray = byteArrayOf(0.toByte())

for (key:Short in keys!!)
{
	conn!!.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, modifier[0], 0.toByte())

 // Apply the modifier of the pressed key, e.g. CTRL first issues a CTRL event (without
            // modifier) and then sends the following keys with the CTRL modifier applied
            modifier[0] = (modifier[0].toInt() or KeyboardTranslator.getModifier(key).toInt()).toByte()
}

Handler().postDelayed(({ for (pos:Int in keys!!.indices.reversed())
{
var key:Short = keys!![pos]

 // Remove the keys modifier before releasing the key
                modifier[0] = (modifier[0].toInt() and KeyboardTranslator.getModifier(key).toInt().inv()).toByte()

	conn!!.sendKeyboardInput(key, KeyboardPacket.KEY_UP, modifier[0], 0.toByte())
} }), GameMenu.KEY_UP_DELAY)
}

override fun handleFocusChange(hasFocus:Boolean):Boolean {
if (connected && prefConfig!!.smartClipboardSync)
{
if (hasFocus)
{
return sendClipboard(false)
}
else
{
return getClipboard(0)
}
}

return false
}

 // Method to get clipboard content
    private fun getClipboardContent(force:Boolean):String? {
 // Check if there is any clipboard data
        if (clipboardManager!!.hasPrimaryClip())
{
var clipDescription:ClipDescription? = clipboardManager!!.getPrimaryClipDescription()
if (!force && clipDescription != null)
{
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
{
var extras:PersistableBundle? = clipDescription!!.getExtras()
if (extras != null && extras!!.getBoolean(CLIPBOARD_IDENTIFIER))
{
 // We're getting the clipboard data we just set/read a while ago
                        return null
}
}
else
{
var clipLabel:CharSequence? = clipDescription!!.label
if (clipLabel != null && clipLabel!!.equals(CLIPBOARD_IDENTIFIER))
{
 // We're getting the clipboard data we set a while ago
                        return null
}
}
}

var clipData:ClipData? = clipboardManager!!.getPrimaryClip()

if (clipData != null && clipData!!.getItemCount() > 0)
{
 // Get the first item from the clipboard data
                var item:ClipData.Item? = clipData!!.getItemAt(0)

 // Mark the clip as visited
                if (clipDescription != null)
{
var clonedClip:ClipData? = cloneClipData(clipDescription, item)
	clipboardManager!!.setPrimaryClip(clonedClip!!)
}

 // Get the text data from the clipboard item
                var clipText:CharSequence? = item!!.getText()
if (clipText == null)
{
return null
}
return clipText!!.toString()
}
}

return null
}

 fun sendClipboard(force:Boolean):Boolean {
if (httpConn == null)
{
LimeLog.warning("httpConn not ready, cannot send clipboard!")
return false
}

var clipboardText:String? = getClipboardContent(force)
if (clipboardText != null)
{
object : Thread() {
override fun run() {
try
{
if (!httpConn!!.sendClipboard(clipboardText))
{
if (prefConfig!!.smartClipboardSyncToast)
{
this@Game.runOnUiThread({ NovaSnackbar.showError(this@Game, getString(R.string.clipboard_sync_unsupported)) })
}
}
else
{
if (prefConfig!!.smartClipboardSyncToast)
{
this@Game.runOnUiThread({ NovaSnackbar.showSuccess(this@Game, getString(R.string.send_clipboard_success)) })
}
}
}
catch (e:Exception) {
e!!.printStackTrace()
if (prefConfig!!.smartClipboardSyncToast)
{
this@Game.runOnUiThread({ NovaSnackbar.showError(this@Game, getString(R.string.send_clipboard_failed) + e!!.message) })
}
}

}
}.start()

return true
}

return false
}

 fun getClipboard(delay:Int):Boolean {
if (httpConn == null)
{
LimeLog.warning("httpConn not ready, cannot get clipboard!")
return false
}

if (delay == 0 && gameMenuCallbacks != null && gameMenuCallbacks!!.isMenuOpen())
{
return false
}

object : Thread() {
override fun run() {
if (clipboardSyncRunning)
{
return
}

clipboardSyncRunning = true
try
{
if (delay > 0)
{
sleep(delay.toLong())
}
var clipboardContent:String? = httpConn!!.getClipboard()
var clipData:ClipData? = ClipData.newPlainText(CLIPBOARD_IDENTIFIER, clipboardContent)

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
{
var clipDescription:ClipDescription? = clipData!!.getDescription()
var newExtras:PersistableBundle = PersistableBundle()
newExtras.putBoolean(CLIPBOARD_IDENTIFIER, true)
if (prefConfig!!.hideClipboardContent)
{
 // We don't know if the message is sensitive or not, to be safe mark them all as sensitive.
                            newExtras.putBoolean("android.content.extra.IS_SENSITIVE", true)
}
clipDescription!!.setExtras(newExtras)
}

clipboardManager!!.setPrimaryClip(clipData!!)
if (prefConfig!!.smartClipboardSyncToast)
{
this@Game.runOnUiThread({ NovaSnackbar.showSuccess(this@Game, getString(R.string.get_clipboard_success)) })
}
}
catch (e:Exception) {
e!!.printStackTrace()
if (prefConfig!!.smartClipboardSyncToast)
{
this@Game.runOnUiThread({ NovaSnackbar.showError(this@Game, getString(R.string.get_clipboard_failed) + e!!.message) })
}
}

clipboardSyncRunning = false
}
}.start()

return true
}

private fun getTouchContext(actionIndex:Int, inputContextMap:Array<TouchContext?>?):TouchContext? {
if (actionIndex < inputContextMap!!.size)
{
return inputContextMap!![actionIndex]
}
else
{
return null
}
}
override fun toggleKeyboard() {
if (externalDisplayControlPresentation?.isHostShowing() == true)
{
externalDisplayControlPresentation?.toggleKeyboard()
}
else
{
LimeLog.info("Toggling keyboard overlay")
var inputManager:InputMethodManager? = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
inputManager!!.toggleSoftInput(0, 0)
}
}

private fun getLiTouchTypeFromEvent(event:MotionEvent?):Byte {
when (event!!.getActionMasked()) {
MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> return MoonBridge.LI_TOUCH_EVENT_DOWN

MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if ((event!!.getFlags() and MotionEvent.FLAG_CANCELED) != 0)
{
return MoonBridge.LI_TOUCH_EVENT_CANCEL
}
else
{
return MoonBridge.LI_TOUCH_EVENT_UP
}

MotionEvent.ACTION_MOVE -> return MoonBridge.LI_TOUCH_EVENT_MOVE

MotionEvent.ACTION_CANCEL ->
 // ACTION_CANCEL applies to *all* pointers in the gesture, so it maps to CANCEL_ALL
                // rather than CANCEL. For a single pointer cancellation, that's indicated via
                // FLAG_CANCELED on a ACTION_POINTER_UP.
                // https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi
                return MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL

MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> return MoonBridge.LI_TOUCH_EVENT_HOVER

MotionEvent.ACTION_HOVER_EXIT -> return MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE

MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> return MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY

else -> return -1
}
}

 //修改移动的触控灵敏度（通过修改移动的距离实现） 默认使用右半边屏幕的时候开启
    private fun getStreamViewRelativeSensitivityXY(event:MotionEvent?, normalizedXInput:Float, normalizedYInput:Float, pointerIndex:Int):FloatArray? {
var normalizedX = normalizedXInput
var normalizedY = normalizedYInput
var normalized:FloatArray = FloatArray(2)
normalized[0] = normalizedX
normalized[1] = normalizedY

 //如果不是全局模式 并且 坐标 不在右边 则返回
        if (!prefConfig!!.touchSensitivityGlobal && normalizedX < getResources().getDisplayMetrics().widthPixels / 2)
{
return normalized
}
if (event!!.getActionMasked() == MotionEvent.ACTION_MOVE)
{
var bean:SensitivityBean? = sensitivityMap.get(java.lang.String.valueOf(event!!.getPointerId(pointerIndex)))
if (bean == null)
{
bean = SensitivityBean()
}
if (bean!!.getLastAbsoluteX() != -1f)
{
var dx:Float = normalizedX - bean!!.getLastAbsoluteX()
var dy:Float = normalizedY - bean!!.getLastAbsoluteY()
dx *= 0.01f * prefConfig!!.touchSensitivityX//灵敏度
dy *= 0.01f * prefConfig!!.touchSensitivityY
normalizedX = bean!!.getLastRelativelyX() + dx
normalizedY = bean!!.getLastRelativelyY() + dy
}
if (prefConfig!!.touchSensitivityRotationAuto)
{
if (normalizedX >= streamContainer!!.getWidth())
{
normalizedX = streamContainer!!.getWidth() / 2.0f
}
if (normalizedY >= streamContainer!!.getHeight())
{
normalizedY = streamContainer!!.getHeight() / 2.0f
}
}
bean!!.setLastAbsoluteX(event!!.getX(pointerIndex))
bean!!.setLastAbsoluteY(event!!.getY(pointerIndex))
bean!!.setLastRelativelyX(normalizedX)
bean!!.setLastRelativelyY(normalizedY)
sensitivityMap.put(java.lang.String.valueOf(event!!.getPointerId(pointerIndex)), bean)
}
 //抬起的时候，恢复初始化状态
        if (event!!.getActionMasked() == MotionEvent.ACTION_UP || event!!.getActionMasked() == MotionEvent.ACTION_POINTER_UP)
{
sensitivityMap.remove(java.lang.String.valueOf(event!!.getPointerId(pointerIndex)))
}
normalized[0] = normalizedX
normalized[1] = normalizedY
return normalized
}


private fun getStreamViewRelativeNormalizedXY(view:View?, event:MotionEvent?, pointerIndex:Int):FloatArray? {
var normalizedX:Float = event!!.getX(pointerIndex)
var normalizedY:Float = event!!.getY(pointerIndex)
 //开启自定义修改触控灵敏度 并且 数值不为100
        if (prefConfig!!.enableTouchSensitivity && (prefConfig!!.touchSensitivityX != 100 || prefConfig!!.touchSensitivityY != 100))
{
var normalized:FloatArray? = getStreamViewRelativeSensitivityXY(event, normalizedX, normalizedY, pointerIndex)
normalizedX = normalized!![0]
normalizedY = normalized!![1]
}
 // For the containing background view, we must subtract the origin
        // of the StreamView to get video-relative coordinates.
        if (view != streamContainer)
{
var normalized:FloatArray? = getNormalizedCoordinates(streamContainer, normalizedX, normalizedY)
normalizedX = normalized!![0]
normalizedY = normalized!![1]
}

normalizedX = Math.max(normalizedX, 0.0f)
normalizedY = Math.max(normalizedY, 0.0f)

normalizedX = Math.min(normalizedX, streamContainer!!.getWidth().toFloat())
normalizedY = Math.min(normalizedY, streamContainer!!.getHeight().toFloat())

normalizedX /= streamContainer!!.getWidth()
normalizedY /= streamContainer!!.getHeight()

return floatArrayOf(normalizedX, normalizedY)
}

private fun getNormalizedCoordinates(streamView:View?, rawX:Float, rawY:Float):FloatArray? {
var scaleX:Float = streamView!!.getScaleX()
var scaleY:Float = streamView!!.getScaleY()

var normalizedX:Float = (rawX - streamView!!.getX()) / scaleX
var normalizedY:Float = (rawY - streamView!!.getY()) / scaleY

return floatArrayOf(normalizedX, normalizedY)
}

private fun getStreamViewNormalizedContactArea(event:MotionEvent?, pointerIndex:Int):FloatArray? {
var orientation:Float

 // If the orientation is unknown, we'll just assume it's at a 45 degree angle and scale it by
        // X and Y scaling factors evenly.
        if (event!!.getDevice() == null || event!!.getDevice().getMotionRange(MotionEvent.AXIS_ORIENTATION, event!!.getSource()) == null)
{
orientation = (Math.PI / 4).toFloat()
}
else
{
orientation = event!!.getOrientation(pointerIndex)
}

var contactAreaMajor:Float
var contactAreaMinor:Float
when (event!!.getActionMasked()) {
 // Hover events report the tool size
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> {
contactAreaMajor = event!!.getToolMajor(pointerIndex)
contactAreaMinor = event!!.getToolMinor(pointerIndex)
}

 // Other events report contact area
            else -> {
contactAreaMajor = event!!.getTouchMajor(pointerIndex)
contactAreaMinor = event!!.getTouchMinor(pointerIndex)
}
}

 // The contact area major axis is parallel to the orientation, so we simply convert
        // polar to cartesian coordinates using the orientation as theta.
var contactAreaMajorCartesian:FloatArray = polarToCartesian(contactAreaMajor, orientation)!!

 // The contact area minor axis is perpendicular to the contact area major axis (and thus
        // the orientation), so rotate the orientation angle by 90 degrees.
var contactAreaMinorCartesian:FloatArray = polarToCartesian(contactAreaMinor, orientation + (Math.PI / 2).toFloat())!!

 // Normalize the contact area to the stream view size
        contactAreaMajorCartesian[0] = Math.min(Math.abs(contactAreaMajorCartesian[0]), streamContainer!!.getWidth().toFloat()) / streamContainer!!.getWidth()
contactAreaMinorCartesian[0] = Math.min(Math.abs(contactAreaMinorCartesian[0]), streamContainer!!.getWidth().toFloat()) / streamContainer!!.getWidth()
contactAreaMajorCartesian[1] = Math.min(Math.abs(contactAreaMajorCartesian[1]), streamContainer!!.getHeight().toFloat()) / streamContainer!!.getHeight()
contactAreaMinorCartesian[1] = Math.min(Math.abs(contactAreaMinorCartesian[1]), streamContainer!!.getHeight().toFloat()) / streamContainer!!.getHeight()

 // Convert the normalized values back into polar coordinates
        return floatArrayOf(cartesianToR(contactAreaMajorCartesian), cartesianToR(contactAreaMinorCartesian))
}

private fun sendPenEventForPointer(view:View?, event:MotionEvent?, eventType:Byte, toolType:Byte, pointerIndex:Int):Boolean {
var penButtons:Byte = 0
if ((event!!.getButtonState() and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
{
	penButtons = (penButtons.toInt() or MoonBridge.LI_PEN_BUTTON_PRIMARY.toInt()).toByte()
}
if ((event!!.getButtonState() and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0)
{
	penButtons = (penButtons.toInt() or MoonBridge.LI_PEN_BUTTON_SECONDARY.toInt()).toByte()
}

var tiltDegrees:Byte = MoonBridge.LI_TILT_UNKNOWN
var dev:InputDevice? = event!!.getDevice()
if (dev != null)
{
if (dev!!.getMotionRange(MotionEvent.AXIS_TILT, event!!.getSource()) != null)
{
	tiltDegrees = Math.toDegrees(event!!.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex).toDouble()).toInt().toByte()
}
}

var normalizedCoords:FloatArray? = getStreamViewRelativeNormalizedXY(view, event, pointerIndex)
var normalizedContactArea:FloatArray? = getStreamViewNormalizedContactArea(event, pointerIndex)
return (conn!!.sendPenEvent(eventType, toolType, penButtons,
normalizedCoords!![0], normalizedCoords!![1],
getPressureOrDistance(event, pointerIndex),
normalizedContactArea!![0], normalizedContactArea!![1],
getRotationDegrees(event, pointerIndex), tiltDegrees) != MoonBridge.LI_ERR_UNSUPPORTED)
}

private fun trySendPenEvent(view:View?, event:MotionEvent?):Boolean {
var eventType:Byte = getLiTouchTypeFromEvent(event)
if (eventType < 0)
{
return false
}

if (event!!.getActionMasked() == MotionEvent.ACTION_MOVE)
{
 // Move events may impact all active pointers
            var handledStylusEvent:Boolean = false
for (i:Int in 0 until event!!.getPointerCount())
{
var toolType:Byte = convertToolTypeToStylusToolType(event, i)
if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN)
{
 // Not a stylus pointer, so skip it
                    continue
}
else
{
 // This pointer is a stylus, so we'll report that we handled this event
                    handledStylusEvent = true
}

if (!sendPenEventForPointer(view, event, eventType, toolType, i))
{
 // Pen events aren't supported by the host
                    return false
}
}
return handledStylusEvent
}
else if (event!!.getActionMasked() == MotionEvent.ACTION_CANCEL)
{
 // Cancel impacts all active pointers
            return (conn!!.sendPenEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, MoonBridge.LI_TOOL_TYPE_UNKNOWN, 0.toByte(),
0f, 0f, 0f, 0f, 0f,
MoonBridge.LI_ROT_UNKNOWN, MoonBridge.LI_TILT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED)
}
else
{
 // Up, Down, and Hover events are specific to the action index
            var toolType:Byte = convertToolTypeToStylusToolType(event, event!!.getActionIndex())
if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN)
{
 // Not a stylus event
                return false
}
return sendPenEventForPointer(view, event, eventType, toolType, event!!.getActionIndex())
}
}

private fun sendTouchEventForPointer(view:View?, event:MotionEvent?, eventType:Byte, pointerIndex:Int):Boolean {
var normalizedCoords:FloatArray? = getStreamViewRelativeNormalizedXY(view, event, pointerIndex)
var normalizedContactArea:FloatArray? = getStreamViewNormalizedContactArea(event, pointerIndex)
return (conn!!.sendTouchEvent(eventType, event!!.getPointerId(pointerIndex),
normalizedCoords!![0], normalizedCoords!![1],
getPressureOrDistance(event, pointerIndex),
normalizedContactArea!![0], normalizedContactArea!![1],
getRotationDegrees(event, pointerIndex)) != MoonBridge.LI_ERR_UNSUPPORTED)
}

private fun trySendTouchEvent(view:View?, event:MotionEvent?):Boolean {
var eventType:Byte = getLiTouchTypeFromEvent(event)
if (eventType < 0)
{
return false
}

if (event!!.getActionMasked() == MotionEvent.ACTION_MOVE)
{
 // Move events may impact all active pointers
            for (i:Int in 0 until event!!.getPointerCount())
{
if (!sendTouchEventForPointer(view, event, eventType, i))
{
return false
}
}
return true
}
else if (event!!.getActionMasked() == MotionEvent.ACTION_CANCEL)
{
 // Cancel impacts all active pointers
            return (conn!!.sendTouchEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0,
0f, 0f, 0f, 0f, 0f,
MoonBridge.LI_ROT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED)
}
else
{
 // Up, Down, and Hover events are specific to the action index
            return sendTouchEventForPointer(view, event, eventType, event!!.getActionIndex())
}
}

 // Returns true if the event was consumed
    // NB: View is only present if called from a view callback
     fun handleMotionEvent(view:View?, event:MotionEvent?):Boolean {
view?.display?.displayId?.let(::recordQuickMenuInteraction)
 // Pass through mouse/touch/joystick input if we're not grabbing
        if (!grabbedInput)
{
return false
}

var deviceId:Int = event!!.getDeviceId()
if (prefConfig!!.ignoreSynthEvents && deviceId <= 0)
{
return false
}

var eventSource:Int = event!!.getSource()
var deviceSources:Int = if (event!!.getDevice() != null) event!!.getDevice().getSources() else 0
if ((eventSource and InputDevice.SOURCE_CLASS_JOYSTICK) != 0)
{
if (controllerHandler!!.handleMotionEvent(event))
{
return true
}
}
else if ((deviceSources and InputDevice.SOURCE_CLASS_JOYSTICK) != 0 && controllerHandler!!.tryHandleTouchpadEvent(event))
{
return true
}
else if (((eventSource and InputDevice.SOURCE_CLASS_POINTER) != 0 ||
(eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0 ||
eventSource == InputDevice.SOURCE_MOUSE_RELATIVE))
{
var hasActionButton:Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || (event!!.getActionButton() != 0)
 // This case is for mice and non-finger touch devices
            if ((eventSource == InputDevice.SOURCE_MOUSE ||
((eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0 && hasActionButton) || // SOURCE_TOUCHPAD

((eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
((event!!.getPointerCount() >= 1 && ((event!!.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
event!!.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
event!!.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)))) ||
eventSource == 12290))) // 12290 = Samsung DeX mode desktop mouse
)
{
var buttonState:Int = event!!.getButtonState()
var changedButtons:Int = buttonState xor lastButtonState

 // Two finger click
                if (((eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0 &&
event!!.getPointerCount() == 2 &&
(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && event!!.getActionButton() == MotionEvent.BUTTON_PRIMARY)))
{
if (event!!.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS)
{
buttonState = buttonState or MotionEvent.BUTTON_SECONDARY
}
else if (event!!.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE)
{
buttonState = buttonState and MotionEvent.BUTTON_SECONDARY.inv()
}
 // We may not pressing the primary button down from a previous event,
                    // so be sure to clear that bit out the button state.
                    buttonState = buttonState and MotionEvent.BUTTON_PRIMARY.inv()
buttonState = buttonState or (lastButtonState and MotionEvent.BUTTON_PRIMARY)

changedButtons = buttonState xor lastButtonState
}

if (view != null && event!!.getPointerCount() >= 1 &&
(event!!.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
event!!.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) &&
trySendPenEvent(view, event))
{
 // If our host supports pen events, send stylus/eraser input directly before the
                    // mouse pointer-capture gate. Pure touchscreen stylus devices do not expose
                    // SOURCE_MOUSE_RELATIVE, so Android pointer capture may never become active.
                    return true
}

 // Ignore mouse input if we're not capturing from our input source
                if (!inputCaptureProvider!!.isCapturingActive())
{
 // We return true here because otherwise the events may end up causing
                    // Android to synthesize d-pad events.
                    return true
}

 // Always update the position before sending any button events. If we're
                // dealing with a stylus without hover support, our position might be
                // significantly different than before.
                if (inputCaptureProvider!!.eventHasRelativeMouseAxes(event))
{
 // Send the deltas straight from the motion event
	                    var deltaX:Short = inputCaptureProvider!!.getRelativeAxisX(event).toInt().toShort()
	var deltaY:Short = inputCaptureProvider!!.getRelativeAxisY(event).toInt().toShort()

if (deltaX.toInt() != 0 || deltaY.toInt() != 0)
{
if (prefConfig!!.absoluteMouseMode)
{
 // NB: view may be null, but we can unconditionally use streamView because we don't need to adjust
                            // relative axis deltas for the position of the streamView within the parent's coordinate system.
	                            conn!!.sendMouseMoveAsMousePosition(deltaX, deltaY, streamContainer!!.getWidth().toShort(), streamContainer!!.getHeight().toShort())
}
else
{
conn!!.sendMouseMove(deltaX, deltaY)
}
}
}
else if ((eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0)
{
 // If this input device is not associated with the view itself (like a trackpad),
                    // we'll convert the device-specific coordinates to use to send the cursor position.
                    // This really isn't ideal but it's probably better than nothing.
                    //
                    // Trackpad on newer versions of Android (Oreo and later) should be caught by the
                    // relative axes case above. If we get here, we're on an older version that doesn't
                    // support pointer capture.
                    var device:InputDevice? = event!!.getDevice()
if (device != null)
{
var xRange:InputDevice.MotionRange? = device!!.getMotionRange(MotionEvent.AXIS_X, eventSource)
var yRange:InputDevice.MotionRange? = device!!.getMotionRange(MotionEvent.AXIS_Y, eventSource)

 // All touchpads coordinate planes should start at (0, 0)
                        if (xRange != null && yRange != null && xRange!!.getMin() == 0f && yRange!!.getMin() == 0f)
{
var xMax:Int = xRange!!.getMax().toInt()
var yMax:Int = yRange!!.getMax().toInt()

 // Touchpads must be smaller than (65535, 65535)
                            if (xMax <= Short.MAX_VALUE && yMax <= Short.MAX_VALUE)
{
conn!!.sendMousePosition(event!!.getX().toInt().toShort(), event!!.getY().toInt().toShort(),
xMax.toShort(), yMax.toShort())
}
}
}
}
else if (view != null && trySendPenEvent(view, event))
{
 // If our host supports pen events, send it directly
                    return true
}
else if (view != null)
{
if (event!!.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER)
{
 // Handle trackpad two finger swipes when pointer is not captured by synthesizing a trackpad movement
                        // Android emulates trackpad  two finger swipes as one finger swipe on the screen
                        var eventAction:Int = event!!.getActionMasked()
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && event!!.getClassification() == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE)
{
if (!pointerSwiping)
{
pointerSwiping = true
handleTouchInput(event, trackpadContextMap, false, prefConfig!!.trackpadSwapAxis, MotionEvent.ACTION_POINTER_DOWN, 1, 2)
}
return handleTouchInput(event, trackpadContextMap, false, prefConfig!!.trackpadSwapAxis, MotionEvent.ACTION_MOVE, 1, 2)
}
else if (pointerSwiping && eventAction == MotionEvent.ACTION_UP)
{
pointerSwiping = false
synthClickPending = false
handleTouchInput(event, trackpadContextMap, false, prefConfig!!.trackpadSwapAxis, MotionEvent.ACTION_POINTER_UP, 1, 2)
return true
}

 // Press & Hold / Double-Tap & Hold for Selection or Drag & Drop
                        var positionDelta:Double = Math.sqrt(
(Math.pow((event!!.getX() - lastTouchDownX).toDouble(), 2.0) + Math.pow((event!!.getY() - lastTouchDownY).toDouble(), 2.0))
)

if ((synthClickPending && event!!.getEventTime() - synthTouchDownTime >= prefConfig!!.trackpadDragDropThreshold))
{
if (positionDelta > 50)
{
pendingDrag = false
}
else if (pendingDrag)
{
pendingDrag = false
isDragging = true
if (prefConfig!!.trackpadDragDropVibration)
{
var vibrator:Vibrator? = (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
{
vibrator!!.vibrate(VibrationEffect.createOneShot(20, 127))
}
else
{
vibrator!!.vibrate(20)
}
}
conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
return true
}
}

when (eventAction) {
MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
updateMousePosition(view, event)
return true
}
MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_DOWN -> {
pendingDrag = true
synthClickPending = true
lastTouchDownX = event!!.getX()
lastTouchDownY = event!!.getY()
synthTouchDownTime = event!!.getEventTime()
return true
}
MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_UP -> {
if (synthClickPending)
{
var timeDiff:Long = event!!.getEventTime() - synthTouchDownTime

if (eventSource == 12290)
{
 // Special handle for DeX
                                        // DeX reports button secondary when tapping with two fingers
                                        // So there's no need to distinguish left/right click by time difference
                                        if (timeDiff < 120)
{
conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
}
}
else
{
if (timeDiff < 20)
{
conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
}
else if (timeDiff < 120)
{
conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
}
}
if (isDragging)
{
isDragging = false
conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
}
pendingDrag = false
synthClickPending = false
}
return true
}
MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> synthClickPending = false
else -> {}
}
}
else
{
updateMousePosition(view, event)
}
}

if (event!!.getActionMasked() == MotionEvent.ACTION_SCROLL)
{
 // Send the vertical scroll packet
	                    conn!!.sendMouseHighResScroll((event!!.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120).toInt().toShort())
	conn!!.sendMouseHighResHScroll((event!!.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120).toInt().toShort())
}

if ((changedButtons and MotionEvent.BUTTON_PRIMARY) != 0)
{
if ((buttonState and MotionEvent.BUTTON_PRIMARY) != 0)
{
conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
}
else
{
conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
}
}

 // Mouse secondary or stylus primary is right click (stylus down is left click)
                if ((changedButtons and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0)
{
if ((buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0)
{
conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
}
else
{
conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
}
}

 // Mouse tertiary or stylus secondary is middle click
                if ((changedButtons and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0)
{
if ((buttonState and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0)
{
conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE)
}
else
{
conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE)
}
}

if (prefConfig!!.mouseNavButtons)
{
if ((changedButtons and MotionEvent.BUTTON_BACK) != 0)
{
if ((buttonState and MotionEvent.BUTTON_BACK) != 0)
{
conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_X1)
}
else
{
conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1)
}
}

if ((changedButtons and MotionEvent.BUTTON_FORWARD) != 0)
{
if ((buttonState and MotionEvent.BUTTON_FORWARD) != 0)
{
conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_X2)
}
else
{
conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2)
}
}
}

 // Handle stylus presses
                if (event!!.getPointerCount() == 1 && event!!.getActionIndex() == 0)
{
if (event!!.getActionMasked() == MotionEvent.ACTION_DOWN)
{
if (event!!.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS)
{
lastAbsTouchDownTime = event!!.getEventTime()
lastAbsTouchDownX = event!!.getX(0)
lastAbsTouchDownY = event!!.getY(0)

 // Stylus is left click
                            conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
}
else if (event!!.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)
{
lastAbsTouchDownTime = event!!.getEventTime()
lastAbsTouchDownX = event!!.getX(0)
lastAbsTouchDownY = event!!.getY(0)

 // Eraser is right click
                            conn!!.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
}
}
else if (event!!.getActionMasked() == MotionEvent.ACTION_UP || event!!.getActionMasked() == MotionEvent.ACTION_CANCEL)
{
if (event!!.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS)
{
lastAbsTouchUpTime = event!!.getEventTime()
lastAbsTouchUpX = event!!.getX(0)
lastAbsTouchUpY = event!!.getY(0)

 // Stylus is left click
                            conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
}
else if (event!!.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)
{
lastAbsTouchUpTime = event!!.getEventTime()
lastAbsTouchUpX = event!!.getX(0)
lastAbsTouchUpY = event!!.getY(0)

 // Eraser is right click
                            conn!!.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
}
}
}

lastButtonState = buttonState
}
else
{
if (eventSource == InputDevice.SOURCE_TOUCHPAD)
{
return handleTouchInput(event, trackpadContextMap, false)
}
else
{
if ((virtualController != null && ((virtualController!!.getControllerMode() == VirtualController.ControllerMode.MoveButtons || virtualController!!.getControllerMode() == VirtualController.ControllerMode.ResizeButtons))))
{
 // Ignore presses when the virtual controller is being configured
                        return true
}

if (isZoomModeEnabled)
{
 // panning the streamView
                        panZoomHandler!!.handleTouchEvent(event)
return true
}

 // If touch is disabled or not initialized, we'll try panning the streamView
                    if (touchContextMap[0] == null)
{
return true
}

if (prefConfig!!.enableMultiTouchGestures || !prefConfig!!.enableMultiTouchScreen)
{
var pointerCount:Int = event!!.getPointerCount()
if (pointerCount > 2)
{
var eventAction:Int = event!!.getActionMasked()
if ((((eventAction == MotionEvent.ACTION_POINTER_DOWN
|| eventAction == MotionEvent.ACTION_POINTER_UP
|| eventAction == MotionEvent.ACTION_UP)) && handleMultiTouchGesture(event, eventAction, pointerCount, view)))
{
return true
}
}
}

if (prefConfig!!.enableMultiTouchScreen && !prefConfig!!.touchscreenTrackpad && trySendTouchEvent(view, event))
{
 // If this host supports touch events and absolute touch is enabled,
                        // send it directly as a touch event.
                        return true
}

return handleTouchInput(event, touchContextMap, true)
}
}// This case is for fingers

 // Handled a known source
            return true
}

 // Unknown class
        return false
}

private fun handleTouchInput(event:MotionEvent?, inputContextMap:Array<TouchContext?>?, isTouchScreen:Boolean, invertAxis:Boolean = false, eventAction:Int = event!!.getActionMasked(), actionIndex:Int = event!!.getActionIndex(), pointerCount:Int = event!!.getPointerCount()):Boolean {
var context:TouchContext? = getTouchContext(actionIndex, inputContextMap)
if (context == null)
{
return false
}

var actualActionIndex:Int = event!!.getActionIndex()
var actualPointerCount:Int = event!!.getPointerCount()

var shouldDuplicateMovement:Boolean = actualPointerCount < pointerCount

if (eventAction == MotionEvent.ACTION_MOVE)
{
 // ACTION_MOVE is special because it always has actionIndex == 0
            // We'll call the move handlers for all indexes manually

            // First process the historical events
            for (i:Int in 0 until event!!.getHistorySize())
{
for (aTouchContextMap:TouchContext? in inputContextMap!!)
{
if (aTouchContextMap!!.getActionIndex() < pointerCount)
{
var aActionIndex:Int = if (shouldDuplicateMovement) 0 else aTouchContextMap!!.getActionIndex()
	var historicalX:Int = event!!.getHistoricalX(aActionIndex, i).toInt()
	var historicalY:Int = event!!.getHistoricalY(aActionIndex, i).toInt()
if (isTouchScreen)
{
var normalizedCoords:FloatArray? = getNormalizedCoordinates(streamContainer, historicalX.toFloat(), historicalY.toFloat())
historicalX = normalizedCoords!![0].toInt()
historicalY = normalizedCoords!![1].toInt()
}

 // Invert axis again since synthetic events are not inverted
                        // Invert twice could correct the direction
                        // Blame Android for this problem
                        // some devices report inverted axis when trackpad pointer is captured
                        // but not when they're simulated as swipes on the screen
                        if (invertAxis)
{
aTouchContextMap!!.touchMoveEvent(
historicalY,
historicalX,
event!!.getHistoricalEventTime(i)
)
}
else
{
aTouchContextMap!!.touchMoveEvent(
historicalX,
historicalY,
event!!.getHistoricalEventTime(i)
)
}
}
}
}

 // Now process the current values
            for (aTouchContextMap:TouchContext? in inputContextMap!!)
{
if (aTouchContextMap!!.getActionIndex() < pointerCount)
{
var aActionIndex:Int = if (shouldDuplicateMovement) 0 else aTouchContextMap!!.getActionIndex()
	var currentX:Int = event!!.getX(aActionIndex).toInt()
	var currentY:Int = event!!.getY(aActionIndex).toInt()
if (isTouchScreen)
{
var normalizedCoords:FloatArray? = getNormalizedCoordinates(streamContainer, currentX.toFloat(), currentY.toFloat())
currentX = normalizedCoords!![0].toInt()
currentY = normalizedCoords!![1].toInt()
}

 // Invert axis again since synthetic events are not inverted
                    if (invertAxis)
{
aTouchContextMap!!.touchMoveEvent(
currentY,
currentX,
event!!.getEventTime()
)
}
else
{
aTouchContextMap!!.touchMoveEvent(
currentX,
currentY,
event!!.getEventTime())
}
}
}

return true
}

	var eventX:Int = event!!.getX(actualActionIndex).toInt()
	var eventY:Int = event!!.getY(actualActionIndex).toInt()

 // Handle view scaling
        if (isTouchScreen)
{
var normalizedCoords:FloatArray? = getNormalizedCoordinates(streamContainer, eventX.toFloat(), eventY.toFloat())
eventX = normalizedCoords!![0].toInt()
eventY = normalizedCoords!![1].toInt()
}

when (eventAction) {
MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
for (touchContext:TouchContext? in inputContextMap!!)
{
touchContext!!.setPointerCount(pointerCount)
}
context!!.touchDownEvent(eventX, eventY, event!!.getEventTime(), true)
}
MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
if (prefConfig!!.touchscreenTrackpad)
{
if ((pointerCount == 1 && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || (event!!.getFlags() and MotionEvent.FLAG_CANCELED) == 0)))
{
 // All fingers up
                        var currentEventTime:Long = event!!.getEventTime()
if (currentEventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD)
{
 // This is a 3 finger tap to bring up the keyboard
                            toggleKeyboard()
return true
}
else if (currentEventTime - fourFingerDownTime < FOUR_FINGER_TAP_THRESHOLD)
{
toggleFullKeyboard()
return true
}
else if (currentEventTime - fiveFingerDownTime < FIVE_FINGER_TAP_THRESHOLD)
{
if (prefConfig!!.enableBackMenu)
{
showGameMenu(null)
}
return true
}
}
}
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event!!.getFlags() and MotionEvent.FLAG_CANCELED) != 0)
{
context!!.cancelTouch()
}
else
{
context!!.touchUpEvent(eventX, eventY, event!!.getEventTime())
}

for (touchContext:TouchContext? in inputContextMap!!)
{
touchContext!!.setPointerCount(pointerCount - 1)
}
if (actionIndex == 0 && pointerCount > 1 && !context!!.isCancelled())
{
 // The original secondary touch now becomes primary
	                    var pointer1X:Int = event!!.getX(1).toInt()
	var pointer1Y:Int = event!!.getY(1).toInt()
if (isTouchScreen)
{
var normalizedCoords:FloatArray? = getNormalizedCoordinates(streamContainer, pointer1X.toFloat(), pointer1Y.toFloat())
pointer1X = normalizedCoords!![0].toInt()
pointer1Y = normalizedCoords!![1].toInt()
}
context!!.touchDownEvent(
pointer1X,
pointer1Y,
event!!.getEventTime(), false)
}
}
MotionEvent.ACTION_CANCEL -> for (aTouchContext:TouchContext? in inputContextMap!!)
{
aTouchContext!!.cancelTouch()
aTouchContext!!.setPointerCount(0)
}
else -> return false
}

return true
}

private fun handleMultiTouchGesture(event:MotionEvent?, eventAction:Int, pointerCount:Int, view:View?):Boolean {

if (eventAction == MotionEvent.ACTION_POINTER_DOWN)
{
if (pointerCount == 3)
{
threeFingerDownTime = event!!.getEventTime()
}
else if (pointerCount == 4)
{
threeFingerDownTime = 0
fourFingerDownTime = event!!.getEventTime()
}
else if (pointerCount == 5)
{
threeFingerDownTime = 0
fourFingerDownTime = 0
fiveFingerDownTime = event!!.getEventTime()
}
}

when (eventAction) {
MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
var currentEventTime:Long = event!!.getEventTime()
if (pointerCount >= 5 && fiveFingerDownTime > 0 && currentEventTime - fiveFingerDownTime < FIVE_FINGER_TAP_THRESHOLD)
{
if (prefConfig!!.enableBackMenu)
{
showGameMenu(null)
}
fiveFingerDownTime = 0
cancelStaleTouchState(event, view)
return true
}
else if (pointerCount == 4 && fourFingerDownTime > 0 && currentEventTime - fourFingerDownTime < FOUR_FINGER_TAP_THRESHOLD)
{
toggleFullKeyboard()
fourFingerDownTime = 0
cancelStaleTouchState(event, view)
return true
}
else if (pointerCount == 3 && threeFingerDownTime > 0 && currentEventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD)
{
toggleKeyboard()
threeFingerDownTime = 0
cancelStaleTouchState(event, view)
return true
}
threeFingerDownTime = 0
fourFingerDownTime = 0
fiveFingerDownTime = 0

cancelStaleTouchState(event, view)
return false
}
else -> return false
}

cancelStaleTouchState(event, view)
return true
}

private fun cancelStaleTouchState(event:MotionEvent?, view:View?) {
var cancelEvent:MotionEvent? = MotionEvent.obtain(event)
cancelEvent!!.setAction(MotionEvent.ACTION_CANCEL)
view!!.dispatchTouchEvent(cancelEvent)
cancelEvent!!.recycle()
for (aTouchContext:TouchContext? in touchContextMap)
{
aTouchContext!!.cancelTouch()
aTouchContext!!.setPointerCount(0)
}
}
override fun onGenericMotionEvent(event:MotionEvent?):Boolean {
return handleMotionEvent(null, event) || super.onGenericMotionEvent(event)

}

private fun updateMousePosition(touchedView:View?, event:MotionEvent?) {
 // X and Y are already relative to the provided view object
        var eventX:Float
var eventY:Float
 // For our StreamView itself, we can use the coordinates unmodified.

        if (touchedView == streamContainer)
{
eventX = event!!.getX(0)
eventY = event!!.getY(0)
}
else
{
 // For the containing background view, we must subtract the origin
            // of the StreamView to get video-relative coordinates.
            eventX = event!!.getX(0) - streamContainer!!.getX()
eventY = event!!.getY(0) - streamContainer!!.getY()
}

if ((event!!.getPointerCount() == 1 && event!!.getActionIndex() == 0 &&
((event!!.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER || event!!.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS))))
{
when (event!!.getActionMasked()) {
MotionEvent.ACTION_DOWN, MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_HOVER_MOVE -> if ((event!!.getEventTime() - lastAbsTouchUpTime <= STYLUS_UP_DEAD_ZONE_DELAY && Math.sqrt(Math.pow((eventX - lastAbsTouchUpX).toDouble(), 2.0) + Math.pow((eventY - lastAbsTouchUpY).toDouble(), 2.0)) <= STYLUS_UP_DEAD_ZONE_RADIUS))
{
 // Enforce a small deadzone between touch up and hover or touch down to allow more precise double-clicking
                        return
}

MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if ((event!!.getEventTime() - lastAbsTouchDownTime <= STYLUS_DOWN_DEAD_ZONE_DELAY && Math.sqrt(Math.pow((eventX - lastAbsTouchDownX).toDouble(), 2.0) + Math.pow((eventY - lastAbsTouchDownY).toDouble(), 2.0)) <= STYLUS_DOWN_DEAD_ZONE_RADIUS))
{
 // Enforce a small deadzone between touch down and move or touch up to allow more precise double-clicking
                        return
}
}
}

 // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size. We can't just drop them because we won't always get an event
        // right at the boundary of the view, so dropping them would result in our cursor never really
        // reaching the sides of the screen.
        eventX = Math.min(Math.max(eventX, 0f), streamContainer!!.getWidth().toFloat())
eventY = Math.min(Math.max(eventY, 0f), streamContainer!!.getHeight().toFloat())

conn!!.sendMousePosition(eventX.toInt().toShort(), eventY.toInt().toShort(), streamContainer!!.getWidth().toShort(), streamContainer!!.getHeight().toShort())
}
override fun onGenericMotion(view:View?, event:MotionEvent?):Boolean {
return handleMotionEvent(view, event)
}

@SuppressLint("ClickableViewAccessibility")
override fun onTouch(view:View?, event:MotionEvent?):Boolean {
if (event!!.getAction() == MotionEvent.ACTION_DOWN)
{
 // Tell the OS not to buffer input events for us
            //
            // NB: This is still needed even when we call the newer requestUnbufferedDispatch()!
            view!!.requestUnbufferedDispatch(event)
}

return handleMotionEvent(view, event)
}
override fun stageStarting(stage:String) {
runOnUiThread(object : Runnable {
override fun run() {
if (spinner != null)
{
spinner!!.setMessage(getResources().getString(R.string.conn_starting) + " " + stage)
}
novaProgressOverlay?.updateState(stage)
}
})
}
override fun stageComplete(stage:String) {}

private fun stopConnection() {
if (connecting || connected)
{
connected = false
connecting = connected
isStreamActive = false
closeCompanionControls()
stopPolarisLiveSessionStatusRefresh()
runtimeTasks.cancel("NovaBitrateAdjust")
 // Send AI session report before dismissing HUD
            if (novaHud != null && host != null)
{
val summary:Map<String, Any>? = novaHud!!.getSessionSummary()
com.papi.nova.LimeLog.info("Nova: HUD session summary " + NovaHudSessionSummaryLog.format(summary ?: emptyMap()))
val reportHost:String? = host
val reportHttpsPort:Int = httpsPort
val reportServerCert:X509Certificate? = serverCert
val reportDevice:String? = DeviceUtils.getModel()
val reportUniqueId:String? = uniqueId
val reportGame:String? = if (appName != null) appName else ""
launchRuntimeIo("NovaSessionReport") { try
{
var client:com.papi.nova.api.PolarisApiClient = com.papi.nova.api.PolarisApiClient(this@Game, reportHost ?: "", reportHttpsPort, reportServerCert)
client.sendSessionReport(
reportDevice ?: "", reportUniqueId ?: "", reportGame ?: "",
getSummaryDouble(summary, "avg_fps", 0.0),
getSummaryDouble(summary, "target_fps", 0.0),
getSummaryDouble(summary, "low_1_percent_fps", 0.0),
getSummaryDouble(summary, "min_fps", 0.0),
getSummaryDouble(summary, "frame_pacing_bad_pct", 0.0),
getSummaryDouble(summary, "safe_target_fps", 0.0),
getSummaryDouble(summary, "avg_latency_ms", 0.0),
getSummaryInt(summary, "avg_bitrate_kbps", 0),
getSummaryDouble(summary, "packet_loss_pct", 0.0),
getSummaryString(summary, "codec"),
getSummaryInt(summary, "duration_s", 0),
getSummaryInt(summary, "samples", 0),
"disconnect",
getSummaryString(summary, "optimization_source"),
getSummaryString(summary, "optimization_confidence"),
getSummaryInt(summary, "recommendation_version", 0),
getSummaryString(summary, "health_grade"),
getSummaryString(summary, "primary_issue"),
getSummaryStringList(summary, "issues"),
getSummaryString(summary, "decoder_risk"),
getSummaryString(summary, "hdr_risk"),
getSummaryString(summary, "network_risk"),
getSummaryString(summary, "capture_path"),
getSummaryInt(summary, "safe_bitrate_kbps", 0),
getSummaryString(summary, "safe_codec"),
getSummaryString(summary, "safe_display_mode"),
getSummaryBoolean(summary, "safe_hdr"),
getSummaryBoolean(summary, "relaunch_recommended") == true
)
}
catch (e:kotlinx.coroutines.CancellationException) {
throw e
}
catch (e:Exception) {
com.papi.nova.LimeLog.warning("Nova: Session report failed: " + e!!.message)
}
 }
novaHud!!.dismiss()
novaHud = null
syncPerfTextWanted()
}
else if (novaHud != null)
{
novaHud!!.dismiss()
novaHud = null
syncPerfTextWanted()
}
 // Stop audio haptics and gyro aiming
            if (audioHapticEngine != null)
{
com.papi.nova.binding.audio.AndroidAudioRenderer.hapticEngine = null
audioHapticEngine!!.stop()
audioHapticEngine = null
}
if (gyroAimController != null) {
gyroAimController!!.stop()
gyroAimController = null
}

com.papi.nova.service.NovaStreamNotification.dismiss(this)
updatePipAutoEnter()

controllerHandler!!.stop()

 // Update GameManager state to indicate we're no longer in game
            UiHelper.notifyStreamEnded(this)

 // Stop may take a few hundred ms to do some network I/O to tell
            // the server we're going away and clean up. Let it run in a separate
            // thread to keep things smooth for the UI. Inside moonlight-common,
            // we prevent another thread from starting a connection before and
            // during the process of stopping this one.
            if (quitOnStop && !watchOnlyRequested)
{
markLocalSessionEnd()
}
            object : Thread() {
override fun run() {
conn!!.stop()
if (httpConn != null && quitOnStop && !watchOnlyRequested)
{
try
{
sleep(1000)
httpConn!!.quitApp(if (conn != null) conn!!.getSessionToken() else null)
this@Game.runOnUiThread({ Toast.makeText(this@Game, this@Game.getResources().getString(R.string.applist_quit_success) + " " + appName, Toast.LENGTH_LONG).show() })
}
catch (e:Exception) {
this@Game.runOnUiThread({ Toast.makeText(this@Game, e!!.message, Toast.LENGTH_LONG).show() })
}

}
}
}.start()
}
}
override fun stageFailed(stage:String, portFlags:Int, errorCode:Int):Boolean {
 // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        var portTestResult:Int = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443, portFlags)

if (errorCode == 0 && portFlags != 0 && (portTestResult == MoonBridge.ML_TEST_RESULT_INCONCLUSIVE || portTestResult == 0))
{
novaProgressOverlay?.updateState("unlocking_or_starting", getResources().getString(R.string.unlocking_or_starting))
return true
}

runOnUiThread(object : Runnable {
override fun run() {
if (spinner != null)
{
spinner!!.dismiss()
spinner = null
}

if (!displayedFailureDialog)
{
displayedFailureDialog = true
LimeLog.severe(stage + " failed: " + errorCode)

 // If video initialization failed and the surface is still valid, display extra information for the user
                    var currentSurface:Surface? = streamContainer!!.getSurface()
if (stage.contains("video") && currentSurface != null && currentSurface!!.isValid())
{
NovaSnackbar.showError(this@Game, getString(R.string.video_decoder_init_failed))
}

var dialogText:String = getResources().getString(R.string.conn_error_msg) + " " + stage + " (error " + errorCode + ")"

when (errorCode) {
403 -> {
dialogText += "\n\n" + getResources().getString(R.string.error_msg_permission_denied) + " (" + getResources().getString(R.string.permission_launch_app) + ")"
}
-408 -> {
dialogText += "\n\n" + getResources().getString(R.string.error_msg_timeout)
}
else -> {
 // do nothing
                        }
}

if (portFlags != 0)
{
dialogText += ("\n\n" + getResources().getString(R.string.check_ports_msg) + "\n" +
MoonBridge.stringifyPortFlags(portFlags, "\n"))
}

if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0)
{
dialogText += "\n\n" + getResources().getString(R.string.nettest_text_blocked)
}

showNovaLaunchIssueSheet(dialogText)
finishSecondScreen()
}
}
})

return false
}

private fun showNovaLaunchIssueSheet(message: String) {
runOnUiThread {
if (isFinishing || isDestroyed) return@runOnUiThread
if (spinner != null) {
spinner!!.dismiss()
spinner = null
}
val sheet = BottomSheetDialog(this@Game)
val density = resources.displayMetrics.density
fun dp(value: Int): Int = (value * density).toInt()
val container = LinearLayout(this@Game).apply {
orientation = LinearLayout.VERTICAL
setPadding(dp(18), dp(14), dp(18), dp(18))
background = NovaSheetChrome.createSheetBackground(this@Game)
}
val handle = View(this@Game).apply {
background = NovaSheetChrome.createHandleBackground(this@Game)
}
NovaSheetChrome.attachHandleDragToDismiss(handle, sheet)
container.addView(handle, LinearLayout.LayoutParams(dp(42), dp(4)).apply {
gravity = Gravity.CENTER_HORIZONTAL
bottomMargin = dp(14)
})
val title = TextView(this@Game).apply {
text = getString(R.string.nova_launch_issue_title)
setTextColor(NovaThemeManager.getTextPrimaryColor(this@Game))
textSize = 20f
}
container.addView(title)
val body = TextView(this@Game).apply {
text = message
setTextColor(NovaThemeManager.getTextSecondaryColor(this@Game))
textSize = 14f
setPadding(0, dp(10), 0, dp(12))
}
val scroll = ScrollView(this@Game).apply {
addView(body)
}
container.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
val dismiss = Button(this@Game).apply {
text = getString(R.string.nova_launch_issue_dismiss)
isAllCaps = false
setTextColor(NovaThemeManager.getTextPrimaryColor(this@Game))
background = NovaSheetChrome.createActionBackground(this@Game)
setOnClickListener {
sheet.dismiss()
finish()
}
}
container.addView(dismiss, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
sheet.setContentView(container)
sheet.setOnShowListener { NovaSheetChrome.applyBottomSheetChrome(sheet, container) }
sheet.setOnDismissListener { finish() }
sheet.show()
NovaSheetChrome.applyBottomSheetChrome(sheet, container)
}
}

private fun finishSecondScreen() {
 // Otherwise screen stays connected but not working with no way of quitting it
        if (prefConfig!!.enableFullExDisplay)
{
var h:Handler = Handler()
h.postDelayed(object : Runnable {
override fun run() {
finish()
}
}, 2000)
}
}
override fun connectionTerminated(errorCode:Int) {
 // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        var portFlags:Int = MoonBridge.getPortFlagsFromTerminationErrorCode(errorCode)
var portTestResult:Int = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443, portFlags)

runOnUiThread(object : Runnable {
override fun run() {
 // Let the display go to sleep now
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

 // Stop processing controller input
                controllerHandler!!.stop()
timerHandler!!.removeCallbacksAndMessages(null)

 // Ungrab input
                setInputGrabState(false)

if (!displayedFailureDialog)
{
displayedFailureDialog = true
LimeLog.severe("Connection terminated: " + errorCode)
stopConnection()

 // Display the error dialog if it was an unexpected termination.
                    // Otherwise, just finish the activity immediately.
                    if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION)
{
var message:String?

if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0)
{
 // If we got a blocked result, that supersedes any other error message
                            message = getResources().getString(R.string.nettest_text_blocked)
}
else
{
when (errorCode) {
MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC -> message = getResources().getString(R.string.no_video_received_error)

MoonBridge.ML_ERROR_NO_VIDEO_FRAME -> message = getResources().getString(R.string.no_frame_received_error)

MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION, MoonBridge.ML_ERROR_PROTECTED_CONTENT -> message = getResources().getString(R.string.early_termination_error)

MoonBridge.ML_ERROR_FRAME_CONVERSION -> message = getResources().getString(R.string.frame_conversion_error)

else -> {
var errorCodeString:String?
 // We'll assume large errors are hex values
                                    if (Math.abs(errorCode) > 1000)
{
errorCodeString = Integer.toHexString(errorCode)
}
else
{
errorCodeString = Integer.toString(errorCode)
}
message = (getResources().getString(R.string.conn_terminated_msg) + "\n\n" +
getResources().getString(R.string.error_code_prefix) + " " + errorCodeString)
}
}
}

if (portFlags != 0)
{
message += ("\n\n" + getResources().getString(R.string.check_ports_msg) + "\n" +
MoonBridge.stringifyPortFlags(portFlags, "\n"))
}

Dialog.displayDialog(this@Game, getResources().getString(R.string.conn_terminated_title),
message, true,
getResources().getString(R.string.nova_conn_reconnect),
Runnable { relaunchStream() })
}
else
{
finish()
}
}
}
})
}
override fun connectionStatusUpdate(connectionStatus:Int) {
runOnUiThread(object : Runnable {
override fun run() {
if (prefConfig!!.disableWarnings)
{
return
}

if (connectionStatus == MoonBridge.CONN_STATUS_POOR)
{
if (configuredStreamBitrateKbps > 5000)
{
notificationOverlayView!!.setText(getResources().getString(R.string.slow_connection_msg))
}
else
{
notificationOverlayView!!.setText(getResources().getString(R.string.poor_connection_msg))
}

requestedNotificationOverlayVisibility = View.VISIBLE
}
else if (connectionStatus == MoonBridge.CONN_STATUS_OKAY)
{
requestedNotificationOverlayVisibility = View.GONE
}

if (!isHidingOverlays)
{
notificationOverlayView!!.setVisibility(requestedNotificationOverlayVisibility)
}
}
})
}
override fun connectionStarted() {
runOnUiThread(object : Runnable {
override fun run() {
if (spinner != null)
{
spinner!!.dismiss()
spinner = null
}

novaProgressOverlay?.updateState("input_ready", "Input ready")
timerHandler?.postDelayed(object : Runnable {
override fun run() {
novaProgressOverlay?.dismiss()
}
}, NOVA_PROGRESS_READY_DISMISS_DELAY_MS)

companionControlReopenGeneration.invalidatePendingRequests()
companionControlsDismissedByUser = false
handleStreamStartedState()

if (!Objects.equals(appUUID, NvApp.REMOTE_INPUT_UUID))
{
showCompanionControls()
}

 // Show Nova Stream HUD if enabled
                if (com.papi.nova.ui.NovaStreamHud.Companion.isEnabled(this@Game))
{
showNovaHud()
}

 // Start audio-driven haptics if enabled
                audioHapticEngine = com.papi.nova.ui.AudioHapticEngine(this@Game)
audioHapticEngine!!.start()
com.papi.nova.binding.audio.AndroidAudioRenderer.hapticEngine = audioHapticEngine

 // Start gyro aiming if enabled
                gyroAimController = com.papi.nova.ui.GyroAimController(this@Game)
	gyroAimController!!.onMouseDelta = { dx:Int, dy:Int ->
	 // Send relative mouse movement via Moonlight's native input
	                    com.papi.nova.nvstream.jni.MoonBridge.sendMouseMove(
	dx.toShort(), dy.toShort())
	}
gyroAimController!!.start()

com.papi.nova.service.NovaStreamNotification.show(
this@Game,
if (appName != null) appName!! else "Streaming",
if (pcName != null) pcName!! else "Server"
)
updatePipAutoEnter()

 // Hide the mouse cursor now after a short delay.
                // Doing it before dismissing the spinner seems to be undone
                // when the spinner gets displayed. On Android Q, even now
                // is too early to capture. We will delay a second to allow
                // the spinner to dismiss before capturing.
                timerHandler!!.postDelayed(object : Runnable {
override fun run() {
setInputGrabState(true)
}
}, 500)

 // Keep the display on
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

 // Update GameManager state to indicate we're in game
                UiHelper.notifyStreamConnected(this@Game)

 // Sync local clipboard to host
                handleFocusChange(true)

 // Ensure overlay toggle button visibility is properly set
                setupOverlayToggleButton()

hideSystemUi(1000)

if (prefConfig!!.preventPacketLoss)
{
timerHandler!!.postDelayed(backgroundPing, 1000)
}
}
})

if (prefConfig!!.usbDriver)
{
 // Start the USB driver
            bindService(Intent(this, UsbDriverService::class.java),
usbDriverServiceConnection, Service.BIND_AUTO_CREATE)
}

 // Report this shortcut being used (off the main thread to prevent ANRs)
        var computer:ComputerDetails = ComputerDetails()
computer.name = pcName ?: ""
computer.uuid = this@Game.getIntent().getStringExtra(EXTRA_PC_UUID) ?: ""
var shortcutHelper:ShortcutHelper = ShortcutHelper(this)
shortcutHelper.reportComputerShortcutUsed(computer)
if (appName != null)
{
 // This may be null if launched from the "Resume Session" PC context menu item
            shortcutHelper.reportGameLaunched(computer, app!!)
}
}
fun handleStreamStartedState() {
connected = true
connecting = false
isStreamActive = true
stopBackgroundResumeWindow()
syncDisconnectResumeTimeoutPolicy()
syncPolarisCursorVisibility()
schedulePolarisLiveSessionStatusRefresh(true)
}
override fun displayMessage(message:String) {
runOnUiThread(object : Runnable {
override fun run() {
Toast.makeText(this@Game, message, Toast.LENGTH_LONG).show()
}
})
}
override fun displayTransientMessage(message:String) {
if (!prefConfig!!.disableWarnings)
{
showQuietStreamTransientMessage(message)
}
}
private fun showQuietStreamTransientMessage(message: String) {
runOnUiThread(object : Runnable {
override fun run() {
if (connecting || !connected || spinner != null) {
LimeLog.info("Nova: quiet stream transient during setup: ")
return
}
NovaSnackbar.showQuiet(this@Game, message)
}
})
}
override fun rumble(controllerNumber:Short, lowFreqMotor:Short, highFreqMotor:Short) {
if (prefConfig!!.enableRumble)
{
LimeLog.info(String.format(null as Locale?, "Rumble on gamepad %d: %04x %04x", controllerNumber, lowFreqMotor, highFreqMotor))
controllerHandler!!.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor)
}
}
override fun rumbleTriggers(controllerNumber:Short, leftTrigger:Short, rightTrigger:Short) {
LimeLog.info(String.format(null as Locale?, "Rumble on gamepad triggers %d: %04x %04x", controllerNumber, leftTrigger, rightTrigger))

controllerHandler!!.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger)
}
override fun setHdrMode(enabled:Boolean, hdrMetadata:ByteArray?) {
LimeLog.info("Display HDR mode: " + (if (enabled) "enabled" else "disabled"))
decoderRenderer!!.setHdrMode(enabled, hdrMetadata)
}
override fun setMotionEventState(controllerNumber:Short, motionType:Byte, reportRateHz:Short) {
controllerHandler!!.handleSetMotionEventState(controllerNumber, motionType, reportRateHz)
}
override fun setControllerLED(controllerNumber:Short, r:Byte, g:Byte, b:Byte) {
controllerHandler!!.handleSetControllerLED(controllerNumber, r, g, b)
}
override fun surfaceChanged(holder:SurfaceHolder, format:Int, width:Int, height:Int) {
if (!surfaceCreated)
{
throw IllegalStateException("Surface changed before creation!")
}

LimeLog.info("surfaceChanged-->" + width + " x " + height + "----" + displayWidth + " x " + displayHeight)

panZoomHandler!!.handleSurfaceChange()

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
{
if (!isInPictureInPictureMode())
{
updatePipAutoEnter()
}
}
}
override fun surfaceCreated(holder:SurfaceHolder) {
var desiredFrameRate:Float

surfaceCreated = true

 // Android will pick the lowest matching refresh rate for a given frame rate value, so we want
        // to report the true FPS value if refresh rate reduction is enabled. We also report the true
        // FPS value if there's no suitable matching refresh rate. In that case, Android could try to
        // select a lower refresh rate that avoids uneven pull-down (ex: 30 Hz for a 60 FPS stream on
        // a display that maxes out at 50 Hz).
        var streamFrameRate:Float = getConfiguredStreamFrameRateFps()
if (mayReduceRefreshRate() || desiredRefreshRate < streamFrameRate)
{
desiredFrameRate = streamFrameRate
}
else
{
 // Otherwise, we will pretend that our frame rate matches the refresh rate we picked in
            // prepareDisplayForRendering(). This will usually be the highest refresh rate that our
            // frame rate evenly divides into, which ensures the lowest possible display latency.
            desiredFrameRate = desiredRefreshRate
}
desiredFrameRate = chooseSurfaceFrameRateHint(streamFrameRate, desiredFrameRate)
var frameRateCompatibility:Int = chooseSurfaceFrameRateCompatibility(streamFrameRate, desiredFrameRate, false)

 // Tell the OS about our frame rate to allow it to adapt the display refresh rate appropriately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
{
 // We want to change frame rate even if it's not seamless, since prepareDisplayForRendering()
            // will not set the display mode on S+ if it only differs by the refresh rate. It depends
            // on us to trigger the frame rate switch here.
            holder!!.getSurface().setFrameRate(desiredFrameRate,
frameRateCompatibility,
Surface.CHANGE_FRAME_RATE_ALWAYS)
}
else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
{
holder!!.getSurface().setFrameRate(desiredFrameRate,
frameRateCompatibility)
}
}
override fun surfaceDestroyed(holder:SurfaceHolder) {
if (!surfaceCreated)
{
throw IllegalStateException("Surface destroyed before creation!")
}

if (attemptedConnection)
{
 // Let the decoder know immediately that the surface is gone
            decoderRenderer!!.prepareForStop()

if (connected)
{
stopConnection()
}
}
}
override fun mouseMove(deltaX:Int, deltaY:Int) {
conn!!.sendMouseMove(deltaX.toShort(), deltaY.toShort())
}
override fun mouseButtonEvent(buttonId:Int, down:Boolean) {
var buttonIndex:Byte

when (buttonId) {
EvdevListener.BUTTON_LEFT -> buttonIndex = MouseButtonPacket.BUTTON_LEFT
EvdevListener.BUTTON_MIDDLE -> buttonIndex = MouseButtonPacket.BUTTON_MIDDLE
EvdevListener.BUTTON_RIGHT -> buttonIndex = MouseButtonPacket.BUTTON_RIGHT
EvdevListener.BUTTON_X1 -> buttonIndex = MouseButtonPacket.BUTTON_X1
EvdevListener.BUTTON_X2 -> buttonIndex = MouseButtonPacket.BUTTON_X2
else -> {
LimeLog.warning("Unhandled button: " + buttonId)
return
}
}

if (down)
{
conn!!.sendMouseButtonDown(buttonIndex)
}
else
{
conn!!.sendMouseButtonUp(buttonIndex)
}
}
override fun mouseVScroll(amount:Byte) {
conn!!.sendMouseScroll(amount)
}
override fun mouseHScroll(amount:Byte) {
conn!!.sendMouseHScroll(amount)
}
override fun keyboardEvent(buttonDown:Boolean, keyCode:Short) {
var keyMap:Short = keyboardTranslator!!.translate(keyCode.toInt(), 0, -1)
if (keyMap.toInt() != 0)
{
 // handleSpecialKeys() takes the Android keycode
            if (handleSpecialKeys(keyCode.toInt(), buttonDown))
{
return
}

if (buttonDown)
{
conn!!.sendKeyboardInput(keyMap, KeyboardPacket.KEY_DOWN, modifierState, 0.toByte())
}
else
{
conn!!.sendKeyboardInput(keyMap, KeyboardPacket.KEY_UP, modifierState, 0.toByte())
}
}
}
override fun onSystemUiVisibilityChange(visibility:Int) {
 // Don't do anything if we're not connected
        if (!connected)
{
return
}

 // This flag is set for all devices
        if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0)
{
hideSystemUi(2000)
}
else if ((visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0)
{
hideSystemUi(2000)
}
}
override fun onPerfUpdate(text:String) {
runOnUiThread(object : Runnable {
override fun run() {
 // Legacy perf overlay (only if enabled)
                if (prefConfig!!.enablePerfOverlay)
{
if (prefConfig!!.enablePerfOverlayLite)
{
performanceOverlayLite!!.setText(text)
}
else
{
performanceOverlayBig!!.setText(text)
}
}

 // Feed Nova HUD (works independently of legacy overlay)
                if (novaHud != null && novaHud!!.isShowing)
{
novaHud!!.updateFromPerfText(text)
}
}
})
}
override fun onPerfSample(sample:PerfOverlaySample) {
runOnUiThread(object : Runnable {
override fun run() {
lastCompanionPerfSample = sample
                if (novaHud != null && novaHud!!.isShowing)
{
novaHud!!.updateFromPerfSample(sample)
}
updateCompanionCommandDeck()
}
})
}
override fun onUsbPermissionPromptStarting() {
 // Disable PiP auto-enter while the USB permission prompt is on-screen. This prevents
        // us from entering PiP while the user is interacting with the OS permission dialog.
        suppressPipRefCount++
updatePipAutoEnter()
}
override fun onUsbPermissionPromptCompleted() {
suppressPipRefCount--
updatePipAutoEnter()
}
override fun onKey(view:View?, keyCode:Int, keyEvent:KeyEvent?):Boolean {
when (keyEvent!!.getAction()) {
KeyEvent.ACTION_DOWN -> return handleKeyDown(keyEvent)
KeyEvent.ACTION_UP -> return handleKeyUp(keyEvent)
KeyEvent.ACTION_MULTIPLE -> return handleKeyMultiple(keyEvent)
else -> return false
}
}
override fun onBackPressed() {
val companionBackOrigin = DualScreenQuickMenuPolicy.escapedBackOrigin(
companionDisplayId = companionControlDisplayId.takeIf { it != INVALID_DISPLAY_ID },
lastInteractionDisplayId = lastQuickMenuInteractionDisplayId.takeIf { it != INVALID_DISPLAY_ID },
companionHasWindowFocus = companionControlHasWindowFocus,
)
if (companionBackOrigin != null &&
externalDisplayControlPresentation?.handleBackFromOwningGame() == true)
{
return
}
if (handleQuickMenuBackFromDisplay(streamingDisplayId))
{
return
}
super.onBackPressed()
}

fun handleQuickMenuBackFromDisplay(originDisplayId:Int):Boolean {
recordQuickMenuInteraction(originDisplayId)
when (DualScreenQuickMenuPolicy.backAction(prefConfig!!.enableBackMenu, isAnyGameMenuOpen()))
{
DualScreenQuickMenuPolicy.BackAction.PASS_THROUGH -> return false
DualScreenQuickMenuPolicy.BackAction.DISMISS -> hideGameMenu()
DualScreenQuickMenuPolicy.BackAction.SHOW -> showGameMenuFromDisplay(originDisplayId, null)
}
return true
}

 fun sendExecServerCmd(cmdId:Int) {
conn!!.sendExecServerCmd(cmdId)
}
 fun toggleZoomMode() {
this.isZoomModeEnabled = !this.isZoomModeEnabled
if (this.isZoomModeEnabled)
{
NovaSnackbar.show(this, getString(R.string.pan_zoom_mode_enabled))
}
else
{
NovaSnackbar.show(this, getString(R.string.pan_zoom_mode_disabled))
}
updateZoomButtonAppearance()

externalDisplayControlPresentation?.toggleZoomMode(false)
}
 fun rotateScreen() {
if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE)
{
currentOrientation = Configuration.ORIENTATION_PORTRAIT
setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT)
}
else
{
currentOrientation = Configuration.ORIENTATION_LANDSCAPE
setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)
}
}
// Converted JavaDoc marker retained as a line comment.
    private fun initMouseMode() {
var mouseModes:Array<String?>? = getResources().getStringArray(R.array.mouse_mode_names)

var savedMouseModeIndexStr:String? = ProfilesManager.getInstance()
.getOverlayingSharedPreferences(this)
.getString("mouse_mode_list", "0")

var savedMouseModeIndex:Int
try
{
savedMouseModeIndex = Integer.parseInt(savedMouseModeIndexStr)
}
catch (e:NumberFormatException) {
savedMouseModeIndex = 0
}

var savedMouseModeString:String? = if ((savedMouseModeIndex >= 0 && savedMouseModeIndex < mouseModes!!.size))
mouseModes!![savedMouseModeIndex]
else
null

var natural:String? = getString(R.string.mouse_mode_track_pad_natural)
var gaming:String? = getString(R.string.mouse_mode_track_pad_gaming)
var disabled:String? = getString(R.string.mouse_mode_disabled)

var naturalIndex:Int = 2 //fallback natural mode for secondary screen
for (i:Int in mouseModes!!.indices)
{
if (mouseModes!![i].equals(natural))
{
naturalIndex = i
break
}
}
 // We only want to temporary override the mouse mode to work with external, but not store it
        if (isOnExternalDisplay)
{
if ((savedMouseModeString != null && ((savedMouseModeString!!.equals(natural) ||
savedMouseModeString!!.equals(gaming) ||
savedMouseModeString!!.equals(disabled)))))
{
applyMouseMode(savedMouseModeIndex)
}
else
{
applyMouseMode(naturalIndex)
}
}
else
{
applyMouseMode(savedMouseModeIndex)
}
}
// Converted JavaDoc marker retained as a line comment.
     @JvmOverloads
     fun selectMouseMode(context:Context?, dialogWindowType:Int? = null, dialogWindowToken:IBinder? = null) {
var allModes:Array<String?>? = getResources().getStringArray(R.array.mouse_mode_names)

var allowedLabels:Set<String> = HashSet(Arrays.asList(
getString(R.string.mouse_mode_track_pad_natural),
getString(R.string.mouse_mode_track_pad_gaming),
getString(R.string.mouse_mode_disabled)
))

var options:MutableList<MouseModeOption> = ArrayList()

for (i:Int in allModes!!.indices)
{
var label:String = allModes!![i]!!
var isAllowed:Boolean = !isOnExternalDisplay || allowedLabels.contains(label)
if (isAllowed)
{
options.add(MouseModeOption(i, label))
}
}

options.add(MouseModeOption(-1, getString(R.string.toggle_local_mouse_cursor)))

var labels:Array<String?> = arrayOfNulls<String?>(options.size)
for (i:Int in 0 until options.size)
{
labels[i] = options[i].label
}
var optionArray:Array<MouseModeOption> = options.toTypedArray()

val mouseModeDialog = AlertDialog.Builder(context)
.setTitle(getString(R.string.game_menu_select_mouse_mode))
.setItems(labels, { dialog, which->
dialog!!.dismiss()
var selected:MouseModeOption = optionArray[which]
if (selected.index == -1)
{
toggleMouseLocalCursor()
}
else
{
applyMouseMode(selected.index)
if (prefConfig!!.rememberMouseMode)
{
ProfilesManager.getInstance().getOverlayingSharedPreferences(this)
.edit()
.putString("mouse_mode_list", java.lang.String.valueOf(selected.index))
.apply()
}
} })
.create()
dialogWindowType?.let { windowType ->
mouseModeDialog.window?.setType(windowType)
}
mouseModeDialog.window?.attributes?.token = dialogWindowToken
mouseModeDialog.show()
}

 //本地鼠标光标切换
    private fun toggleMouseLocalCursor() {
if (!grabbedInput)
{
inputCaptureProvider!!.enableCapture()
grabbedInput = true
}
setLocalCursorVisible(!cursorVisible)
}

private fun setLocalCursorVisible(visible:Boolean) {
cursorVisible = visible
if (cursorVisible)
{
inputCaptureProvider!!.showCursor()
}
else
{
inputCaptureProvider!!.hideCursor()
}
syncPolarisCursorVisibility()
}

private fun syncPolarisCursorVisibility() {
queuePolarisCursorVisibilitySync(!cursorVisible)
}

private fun queuePolarisCursorVisibilitySync(hostCursorVisible:Boolean) {
if ((novaApiClient == null || !com.papi.nova.manager.FeatureFlagManager.hasCursorVisibilityControl))
{
return
}

var shouldSchedule:Boolean = false
synchronized (cursorVisibilitySyncLock) {
pendingHostCursorVisible = hostCursorVisible
hasPendingCursorVisibilitySync = true
if (!cursorVisibilitySyncScheduled)
{
cursorVisibilitySyncScheduled = true
shouldSchedule = true
}
}

if (shouldSchedule)
{
launchRuntimeIo("NovaCursorSync") { drainCursorVisibilitySyncQueue() }
}
}

private fun drainCursorVisibilitySyncQueue() {
while (true)
{
var hostCursorVisible:Boolean
synchronized (cursorVisibilitySyncLock) {
if (!hasPendingCursorVisibilitySync)
{
cursorVisibilitySyncScheduled = false
return
}
hostCursorVisible = pendingHostCursorVisible
hasPendingCursorVisibilitySync = false
}

var client:com.papi.nova.api.PolarisApiClient? = novaApiClient
if ((client == null || !com.papi.nova.manager.FeatureFlagManager.hasCursorVisibilityControl))
{
continue
}

var success:Boolean = client!!.setCursorVisibility(hostCursorVisible)
if (success)
{
com.papi.nova.LimeLog.info("Nova: Host cursor visibility synced → " + hostCursorVisible)
}
else
{
com.papi.nova.LimeLog.warning("Nova: Host cursor visibility sync failed")
}
}
}

private fun stopCursorVisibilitySync() {
synchronized (cursorVisibilitySyncLock) {
hasPendingCursorVisibilitySync = false
cursorVisibilitySyncScheduled = false
}
runtimeTasks.cancel("NovaCursorSync")
}

internal fun launchRuntimeIo(name:String, block:suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
runtimeTasks.launchIo(name, block)
}

internal fun launchReplacingRuntimeIo(name:String, block:suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
runtimeTasks.launchIoReplacing(name, block)
}

internal suspend fun runOnMainIfRuntimeActive(block:() -> Unit) {
runtimeTasks.runOnMainIfActive {
if (!isFinishing && !isDestroyed)
{
block()
}
}
}

private fun startNovaFeatureProbe() {
var client:com.papi.nova.api.PolarisApiClient? = novaApiClient
if (client == null)
{
return
}

runtimeTasks.launchIo("NovaFeatureProbe") {
com.papi.nova.manager.FeatureFlagManager.probe(client)
runtimeTasks.runOnMainIfActive {
if (isFinishing || isDestroyed)
{
return@runOnMainIfActive
}
startNovaEventSourceIfSupported()
if (com.papi.nova.manager.FeatureFlagManager.isPolarisServer)
{
queuePolarisClientSettingsSnapshot(null)
schedulePolarisLiveSessionStatusRefresh(true)
}
}
}
}

private fun startNovaEventSourceIfSupported() {
if ((novaEventSource != null ||
novaApiClient == null ||
!com.papi.nova.manager.FeatureFlagManager.isPolarisServer))
{
return
}

if (!connected && !isStreamActive)
{
novaProgressOverlay?.show()
}
novaEventSource = com.papi.nova.api.PolarisEventSource(host ?: "",
object : com.papi.nova.api.PolarisEventSource.EventListener {
override fun onSessionEvent(event:String, state:String, message:String) {
LimeLog.info("Nova SSE: " + event + " [" + state + "] " + message)
if (PolarisSessionEvents.isCurrentSessionEvent(event, state))
{
polarisSseSawCurrentSessionEvent = true
}
novaProgressOverlay!!.updateState(state, message)
if (PolarisSessionEvents.shouldFinishGameActivity(event, state, polarisSseSawCurrentSessionEvent))
{
handlePolarisHostSessionEnded()
}
}
override fun onStateUpdate(sessionState:String, cageRunning:Boolean, screenLocked:Boolean) {
if (PolarisSessionEvents.isCurrentSessionEvent("", sessionState))
{
polarisSseSawCurrentSessionEvent = true
}
novaProgressOverlay!!.updateState(sessionState, "")
if ("streaming".equals(sessionState))
{
schedulePolarisLiveSessionStatusRefresh(true)
}
else if (PolarisSessionEvents.shouldFinishGameActivity("", sessionState, polarisSseSawCurrentSessionEvent))
{
handlePolarisHostSessionEnded()
}
if (com.papi.nova.manager.FeatureFlagManager.hasLockScreenControl)
{
var shouldShowLockOverlay:Boolean = shouldShowPolarisLockOverlay(screenLocked, cageRunning)
if (shouldShowLockOverlay && cageRunning)
{
LimeLog.info("Nova SSE: host lock flag received while stream compositor is running; showing unlock overlay")
}
if (shouldShowLockOverlay)
{
novaLockScreenOverlay!!.show()
}
else
{
novaLockScreenOverlay!!.dismiss()
}
}
}
override fun onConnectionLost() {
LimeLog.warning("Nova SSE: Connection lost")
}
},
novaApiClient!!.client
)
novaEventSource!!.start()
}

private fun schedulePolarisLiveSessionStatusRefresh(immediate:Boolean) {
if (timerHandler == null || novaApiClient == null)
{
return
}
timerHandler!!.removeCallbacks(polarisSessionStatusRefreshTick)
if (immediate)
{
refreshPolarisLiveSessionStatus()
}
timerHandler!!.postDelayed(polarisSessionStatusRefreshTick, POLARIS_SESSION_STATUS_REFRESH_MS)
}

private fun stopPolarisLiveSessionStatusRefresh() {
if (timerHandler != null)
{
timerHandler!!.removeCallbacks(polarisSessionStatusRefreshTick)
}
polarisSessionStatusRefreshInFlight.set(false)
runtimeTasks.cancel("NovaSessionStatus")
}

private fun queuePolarisClientSettingsSnapshot(clientPresentation:JSONObject?) {
if ((novaApiClient == null || !com.papi.nova.manager.FeatureFlagManager.hasClientSettings))
{
return
}
runtimeTasks.launchIo("NovaClientSettingsSync") {
reportPolarisClientSettingsSnapshot(clientPresentation)
}
}

private fun reportPolarisClientSettingsSnapshot(clientPresentation:JSONObject?):Boolean {
if ((novaApiClient == null || !com.papi.nova.manager.FeatureFlagManager.hasClientSettings))
{
return false
}

var targetRefreshRateHz:Float = 0f
var refreshRatePolicy:String = ""
try
{
if (clientPresentation != null)
{
targetRefreshRateHz = clientPresentation!!.optDouble("target_refresh_rate_hz", 0.0).toFloat()
refreshRatePolicy = clientPresentation!!.optString("refresh_rate_policy", "")
}
}
catch (ignored:Exception) {}
var runtime:JSONObject? = com.papi.nova.manager.StreamSyncManager.buildClientRuntime(
this,
decoderRenderer,
if (lastClientPresentationRefreshRate > 0f) lastClientPresentationRefreshRate else desiredRefreshRate,
lastClientPresentationDisplayModeId,
lastClientPresentationDisplayMode,
if (prefConfig != null) prefConfig!!.framePacing else 0,
lastClientProfileProvenance,
targetRefreshRateHz,
refreshRatePolicy
)

var manualProfileOverride:Boolean = isManualProfileOverride()
var syncStatus:com.papi.nova.api.PolarisSessionStatus.SyncStatus? = novaApiClient!!.reportClientSettings(
com.papi.nova.manager.StreamSyncManager.SYNC_MODE_AUTO_SAFE,
manualProfileOverride,
lastPolarisDeviceCapabilities,
runtime,
lastPolarisAppliedStreamSettings,
clientPresentation
)
if (syncStatus != null)
{
LimeLog.info("Nova: Client settings sync → " + syncStatus!!.label)
return true
}

LimeLog.warning("Nova: Client settings sync failed")
return false
}

private fun updateAppliedStreamSettingsFromStatus(status:com.papi.nova.api.PolarisSessionStatus?) {
if (status == null || lastPolarisAppliedStreamSettings == null)
{
return
}
try
{
var applied:JSONObject = JSONObject(lastPolarisAppliedStreamSettings!!.toString())
var adaptiveTarget:Int = if (status!!.tuning.adaptiveTargetBitrateKbps > 0)
status!!.tuning.adaptiveTargetBitrateKbps
else
status!!.adaptiveTargetBitrateKbps
var effectiveBitrate:Int = if (adaptiveTarget > 0) adaptiveTarget else status!!.encoder.bitrateKbps
if (effectiveBitrate > 0) applied.put("target_bitrate_kbps", effectiveBitrate)
if (adaptiveTarget > 0) applied.put("adaptive_target_bitrate_kbps", adaptiveTarget)
applied.put("adaptive_bitrate_enabled",
status!!.tuning.adaptiveBitrateEnabled || status!!.adaptiveBitrateEnabled)
applied.put("ai_optimizer_enabled",
status!!.tuning.aiOptimizerEnabled || status!!.aiOptimizerEnabled)
if (status!!.encoder.codec.isNotEmpty())
{
applied.put("active_codec", status!!.encoder.codec)
}
lastPolarisAppliedStreamSettings = applied
}
catch (ignored:Exception) {}

}

private var shownDisplayModeWarning = ""

/**
 * The host says when a session is not on the display the client asked for (the
 * virtual display fell back, or the private runtime superseded the request) -
 * the silent half of a fallback the journal alone used to know about. Shown
 * once per distinct warning per session: a heads-up, not a nag.
 */
private fun maybeShowDisplayModeWarning(status:com.papi.nova.api.PolarisSessionStatus) {
val warning = status.displayMode.warning
if (warning.isBlank() || warning == shownDisplayModeWarning)
{
return
}
shownDisplayModeWarning = warning
runOnUiThread {
Toast.makeText(this, warning, Toast.LENGTH_LONG).show()
}
}

private fun reportClientPresentationIfNeeded(status:com.papi.nova.api.PolarisSessionStatus?) {
if (status == null || !status!!.isStreaming || novaApiClient == null)
{
return
}
updateAppliedStreamSettingsFromStatus(status)

var policy:com.papi.nova.api.PolarisSessionStatus.PresentationPolicy? = status!!.presentationPolicy
var targetRefreshRate:Double = policy!!.targetRefreshRateHz
var refreshPolicy:String? = policy!!.refreshRatePolicy
var appliedRefreshRate:Float = 0f
var displayModeId:Int = 0
var displayMode:String = ""
var activeDisplay:Display? = streamingDisplay
if (activeDisplay != null)
{
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
{
var activeMode:Display.Mode? = activeDisplay!!.getMode()
appliedRefreshRate = activeMode!!.getRefreshRate()
displayModeId = activeMode!!.getModeId()
displayMode = "${activeMode!!.getPhysicalWidth()}x${activeMode!!.getPhysicalHeight()}x${activeMode!!.getRefreshRate()}"
}
else
{
appliedRefreshRate = activeDisplay!!.getRefreshRate()
displayMode = "refresh_rate:" + appliedRefreshRate
}
}
if (appliedRefreshRate <= 0f)
{
appliedRefreshRate = if (lastClientPresentationRefreshRate > 0f)
lastClientPresentationRefreshRate
else
desiredRefreshRate
displayModeId = lastClientPresentationDisplayModeId
displayMode = lastClientPresentationDisplayMode
}
var decoderName:String? = if (decoderRenderer != null) decoderRenderer!!.activeDecoderName else ""

var presentationStatus:String = "synced"
var reason:String = "Nova presentation state matches the stream policy"
if (policy!!.allowDisplayModeChange && targetRefreshRate > 0.0)
{
if (appliedRefreshRate <= 0f)
{
presentationStatus = "pending"
reason = "Nova has not selected a display refresh rate yet"
}
else if (Math.abs(appliedRefreshRate - targetRefreshRate) <= 0.75)
{
presentationStatus = "synced"
reason = "Nova matched the internal display refresh rate to the stream FPS"
}
else if (isWholeRefreshMultiple(appliedRefreshRate, targetRefreshRate))
{
presentationStatus = "synced"
reason = "Nova selected a display refresh rate that is an even multiple of the stream FPS"
}
else
{
presentationStatus = "blocked"
reason = "Android did not expose an exact refresh-rate match for this stream"
}
}
else if (!policy!!.allowDisplayModeChange)
{
reason = "Polaris did not request a client display-mode change"
}

var reportKey:String? = (status!!.sessionToken + "|" +
targetRefreshRate + "|" +
refreshPolicy + "|" +
appliedRefreshRate + "|" +
displayModeId + "|" +
displayMode + "|" +
decoderName + "|" +
presentationStatus + "|" +
status!!.tuning.adaptiveTargetBitrateKbps + "|" +
status!!.encoder.bitrateKbps)
if ((reportKey!!.equals(lastReportedClientPresentationKey) || !clientPresentationReportInFlight.compareAndSet(false, true)))
{
return
}

try
{
var presentation:JSONObject = JSONObject()
try
{
presentation.put("status", presentationStatus)
if (appliedRefreshRate > 0f) presentation.put("applied_refresh_rate_hz", appliedRefreshRate)
if (displayModeId > 0) presentation.put("display_mode_id", displayModeId)
if (displayMode != null && !displayMode.isEmpty()) presentation.put("display_mode", displayMode)
if (decoderName != null && !decoderName!!.isEmpty()) presentation.put("decoder", decoderName)
if (reason != null && !reason.isEmpty()) presentation.put("reason", reason)
if (targetRefreshRate > 0.0) presentation.put("target_refresh_rate_hz", targetRefreshRate)
if (refreshPolicy != null && !refreshPolicy!!.isEmpty()) presentation.put("refresh_rate_policy", refreshPolicy)
}
catch (ignored:Exception) {}

var success:Boolean = reportPolarisClientSettingsSnapshot(presentation)
if (success)
{
lastReportedClientPresentationKey = reportKey
com.papi.nova.LimeLog.info(("Nova: Client presentation synced → " +
presentationStatus + " @ " + appliedRefreshRate + " Hz"))
}
else
{
com.papi.nova.LimeLog.warning("Nova: Client presentation sync failed")
}
}

finally
{
clientPresentationReportInFlight.set(false)
}
}

private fun isWholeRefreshMultiple(appliedRefreshRate:Float, targetRefreshRate:Double):Boolean {
if (appliedRefreshRate <= 0f || targetRefreshRate <= 0.0 || appliedRefreshRate < targetRefreshRate)
{
return false
}

var ratio:Double = appliedRefreshRate / targetRefreshRate
var nearestWhole:Double = Math.rint(ratio)
return nearestWhole >= 1.0 && Math.abs(ratio - nearestWhole) <= 0.05
}

private fun refreshPolarisLiveSessionStatus() {
if (novaApiClient == null || !polarisSessionStatusRefreshInFlight.compareAndSet(false, true))
{
return
}

runtimeTasks.launchIo("NovaSessionStatus") { try
{
var status:com.papi.nova.api.PolarisSessionStatus? = novaApiClient!!.getSessionStatus()
if (status != null)
{
lastPolarisSessionStatus = status
runtimeTasks.runOnMainIfActive {
if (isFinishing || isDestroyed)
{
return@runOnMainIfActive
}
novaHud?.applySessionStatus(status)
maybeShowDisplayModeWarning(status)
updateCompanionCommandDeck()
}
reportClientPresentationIfNeeded(status)
}
}
catch (e:kotlinx.coroutines.CancellationException) {
throw e
}
catch (e:Exception) {
com.papi.nova.LimeLog.warning("Nova: Live session status refresh failed: " + e!!.message)
}
finally
{
polarisSessionStatusRefreshInFlight.set(false)
} }
}

private fun applyMouseMode(mode:Int) {
currentMouseModeIndex = mode
when (mode) {
0 // Multi-touch
 -> {
prefConfig!!.enableMultiTouchScreen = true
prefConfig!!.touchscreenTrackpad = false
}
1 // Normal mouse
, 5 // Normal mouse with swapped buttons
 -> {
prefConfig!!.enableMultiTouchScreen = false
prefConfig!!.touchscreenTrackpad = false
}
2 // Trackpad (natural)
, 3 // Trackpad (gaming)
 -> {
prefConfig!!.enableMultiTouchScreen = false
prefConfig!!.touchscreenTrackpad = true
}
4 // Touch mouse disabled
 -> {}
else -> {}
}

 //Initialize touch contexts
        for (i:Int in touchContextMap.indices)
{
touchContextMap[i]?.cancelTouch()
if (mode == 4)
{
 // Touch mouse disabled
                touchContextMap[i] = null
}
else if (!prefConfig!!.touchscreenTrackpad)
{
touchContextMap[i] = AbsoluteTouchContext(conn!!, i, streamContainer!!, mode == 5)
}
else if (mode == 3)
{
touchContextMap[i] = RelativeTouchContext(conn!!, i, REFERENCE_HORIZ_RES, REFERENCE_VERT_RES, streamContainer!!, prefConfig)
}
else
{
touchContextMap[i] = TrackpadContext(conn!!, i)
}
}

 // Always exit zoom mode if mouse mode has changed
        isZoomModeEnabled = false
updateZoomButtonAppearance()
}
fun isNovaHudShowing():Boolean {
return novaHud?.isShowing == true
}

fun toggleNovaHud() {
if (isNovaHudShowing())
{
setNovaHudPreference(false)
dismissNovaHud()
}
else
{
setNovaHudPreference(true)
showNovaHud()
}
}

private fun setNovaHudPreference(enabled:Boolean) {
PreferenceManager.getDefaultSharedPreferences(this)
.edit()
.putBoolean("nova_polaris_hud", enabled)
.apply()
}

fun dismissNovaHud() {
novaHud?.dismiss()
novaHud = null
syncPerfTextWanted()
}

private fun syncPerfTextWanted() {
decoderRenderer?.setPerfTextWanted(
(::prefConfig.isInitialized && prefConfig!!.enablePerfOverlay) || novaHud?.isShowing == true
)
}

fun copyNovaHudDiagnostics() {
val diagnosticText:String = novaHud?.getDiagnosticSummaryText()
    ?: "Nova stream diagnostics\nNo active Nova HUD sample yet. Enable Nova HUD during a stream and try again."
val clipboard:ClipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
clipboard.setPrimaryClip(ClipData.newPlainText("Nova HUD diagnostics", diagnosticText))
Toast.makeText(this, R.string.nova_quick_menu_hud_diagnostics_copied, Toast.LENGTH_SHORT).show()
}

fun showNovaHud():com.papi.nova.ui.NovaStreamHud {
if (::prefConfig.isInitialized && prefConfig!!.enablePerfOverlay)
{
toggleHUD()
}

var hud:com.papi.nova.ui.NovaStreamHud? = novaHud
if (hud != null && hud!!.isShowing)
{
configureNovaHud(hud!!)
return hud!!
}

hud = com.papi.nova.ui.NovaStreamHud(this@Game) {
showGameMenu(null)
}
novaHud = hud
hud!!.show()
syncPerfTextWanted()
configureNovaHud(hud!!)
return hud!!
}

private fun configureNovaHud(hud:com.papi.nova.ui.NovaStreamHud) {
hud.setTargetFps(configuredHudTargetFps.toDouble())
hud.setTargetBitrateKbps(configuredStreamBitrateKbps)
if (lastPolarisSessionStatus != null)
{
hud.applySessionStatus(lastPolarisSessionStatus)
}

	val streamHost:String? = host
	val streamHttpsPort:Int = httpsPort
	val streamServerCert:X509Certificate? = serverCert
	// Wire proactive bitrate adjustment through runtime tasks so repeated slider moves coalesce.
	hud.onBitrateAdjust = { newBitrate:Int ->
	launchReplacingRuntimeIo("NovaBitrateAdjust") { try
{
val client:com.papi.nova.api.PolarisApiClient = com.papi.nova.api.PolarisApiClient(this@Game, streamHost ?: "", streamHttpsPort, streamServerCert)
client.setBitrate(newBitrate)
com.papi.nova.LimeLog.info("Nova: Proactive bitrate adjust → " + newBitrate + " kbps")
}
catch (e:kotlinx.coroutines.CancellationException) {
throw e
}
catch (e:Exception) {
com.papi.nova.LimeLog.warning("Nova: Bitrate adjust failed: " + e!!.message)
}
 }
}
schedulePolarisLiveSessionStatusRefresh(true)
}

override fun cycleNovaHudFromController() {
runOnUiThread {
if (isFinishing || isDestroyed)
{
return@runOnUiThread
}
val wasShowing:Boolean = isNovaHudShowing()
val hud:com.papi.nova.ui.NovaStreamHud = showNovaHud()
if (wasShowing)
{
hud.cycleMode()
}
}
}

 fun toggleHUD() {
prefConfig!!.enablePerfOverlay = !prefConfig!!.enablePerfOverlay
if (prefConfig!!.enablePerfOverlay)
{
performanceOverlayView!!.setVisibility(View.VISIBLE)
if (prefConfig!!.enablePerfOverlayLite)
{
performanceOverlayLite!!.setVisibility(View.VISIBLE)
}
else
{
performanceOverlayBig!!.setVisibility(View.VISIBLE)
}
}
else
{
performanceOverlayView!!.setVisibility(View.GONE)
}
}

 //切换触控灵敏度开关
 fun switchTouchSensitivity() {
prefConfig!!.enableTouchSensitivity = !prefConfig!!.enableTouchSensitivity
}
private fun syncDisconnectResumeTimeoutPolicy() {
val preferencesReady:Boolean = ::prefConfig.isInitialized
val keepAlive:Boolean = preferencesReady && prefConfig.keepStreamAlive
if (!BackgroundResumePolicy.shouldSyncDisconnectTimeout(
preferencesReady,
watchOnlyRequested,
keepAlive,
disconnectResumeTimeoutSynced
))
{
return
}
if (disconnectResumeTimeoutSyncInFlight)
{
return
}
val client:com.papi.nova.api.PolarisApiClient = novaApiClient ?: return
val timeoutSeconds:Int = prefConfig.disconnectResumeTimeoutSeconds
disconnectResumeTimeoutSyncInFlight = true
runtimeTasks.launchIo("NovaResumePolicy") { try
{
client.updateClientSettings(disconnectResumeTimeoutSeconds = timeoutSeconds)
disconnectResumeTimeoutSynced = true
LimeLog.info("Nova: Synced disconnect resume timeout: " + timeoutSeconds + "s")
}
catch (e:Exception) {
LimeLog.warning("Nova: Failed to sync disconnect resume timeout: " + e!!.message)
}
finally {
disconnectResumeTimeoutSyncInFlight = false
}
}
}

private fun encodedServerCertificateForResume():ByteArray? {
return try
{
serverCert?.encoded
}
catch (e:Exception) {
LimeLog.warning("Nova: Failed to encode server cert for resume notification: " + e.message)
null
}
}

private fun prepareBackgroundResumeWindow() {
val preferencesReady:Boolean = ::prefConfig.isInitialized
val keepAlive:Boolean = preferencesReady && prefConfig.keepStreamAlive
if (!BackgroundResumePolicy.shouldPrepareResumeWindow(
backgroundResumePrepared,
preferencesReady,
quitOnStop,
watchOnlyRequested,
keepAlive
))
{
return
}
backgroundResumePrepared = true
val timeoutSeconds:Int = prefConfig.disconnectResumeTimeoutSeconds
syncDisconnectResumeTimeoutPolicy()
try
{
com.papi.nova.service.NovaStreamKeepAlive.start(
this,
timeoutSeconds,
appName,
pcName,
host,
port,
httpsPort,
uniqueId,
this@Game.getIntent().getStringExtra(EXTRA_PC_UUID),
serverCmds,
encodedServerCertificateForResume()
)
LimeLog.info("Nova: Background resume window prepared for " + timeoutSeconds + "s")
}
catch (e:Exception) {
LimeLog.warning("Nova: Failed to start background resume service: " + e!!.message)
}
}

private fun stopBackgroundResumeWindow() {
backgroundResumePrepared = false
try
{
com.papi.nova.service.NovaStreamKeepAlive.stop(this)
}
catch (e:Exception) {
LimeLog.warning("Nova: Failed to stop background resume service: " + e!!.message)
}
}

private fun markLocalSessionEnd() {
if (localSessionEndMarked)
{
return
}
localSessionEndMarked = true
NovaSessionEndSignal.mark(
this,
this@Game.getIntent().getStringExtra(EXTRA_PC_UUID),
host ?: this@Game.getIntent().getStringExtra(EXTRA_HOST)
)
}

private fun handlePolarisHostSessionEnded() {
if (hostSessionEnded)
{
return
}
hostSessionEnded = true
markLocalSessionEnd()
LimeLog.info("Nova: Polaris host session ended; returning to library")
runOnUiThread {
if (!isFinishing && !isDestroyed)
{
stopPolarisLiveSessionStatusRefresh()
novaReconnectOverlay?.dismiss()
novaProgressOverlay?.dismiss()
stopBackgroundResumeWindow()
novaResilienceManager?.shutdown()
finish()
}
}
}

 fun disconnect() {
if (!hostSessionEnded)
{
prepareBackgroundResumeWindow()
}
if (prefConfig!!.smartClipboardSync)
{
getClipboard(-1)
}
finish()
}
 fun relaunchStream() {
var relaunchIntent:Intent = Intent(getIntent())
relaunchIntent.setClass(getApplicationContext(), Game::class.java)
relaunchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
if (prefConfig!!.smartClipboardSync)
{
getClipboard(-1)
}
finish()
Handler(Looper.getMainLooper()).postDelayed({ GameDisplayLaunchTrampolineActivity.launchGameOnRequestedDisplay(getApplicationContext(), relaunchIntent)
overridePendingTransition(0, 0) }, 900)
}
 fun quit() {
val companionPresentation:ExternalDisplayControlHost? = externalDisplayControlPresentation
?.takeIf { it.isHostShowing() }
val context:Context = companionPresentation?.companionDialogContext ?: this

val sheet = BottomSheetDialog(context)
if (companionPresentation != null)
{
sheet.window?.setType(companionPresentation.companionDialogWindowType)
sheet.window?.attributes?.token = companionPresentation.companionDialogWindowToken()
}
val container = NovaSheetChrome.createSheetContainer(context)

val title = TextView(context).apply {
setText(R.string.game_dialog_title_quit_confirm)
textSize = 20f
NovaSheetChrome.styleSheetTitle(this)
}
container.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

val message = TextView(context).apply {
setText(R.string.game_dialog_message_quit_confirm)
textSize = 15f
setPadding(0, UiHelper.dpToPx(context, 10f).toInt(), 0, UiHelper.dpToPx(context, 18f).toInt())
setTextColor(com.papi.nova.ui.NovaThemeManager.getTextSecondaryColor(context))
}
container.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

val stay = TextView(context).apply {
text = getString(R.string.game_dialog_action_stay_in_game)
gravity = Gravity.CENTER
NovaSheetChrome.styleSheetAction(this)
setOnClickListener { sheet.dismiss() }
}
container.addView(stay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiHelper.dpToPx(context, 48f).toInt()))

val endSession = TextView(context).apply {
text = getString(R.string.game_dialog_action_end_session)
gravity = Gravity.CENTER
NovaSheetChrome.styleSheetAction(this, destructive = true)
setOnClickListener {
quitOnStop = true
markLocalSessionEnd()
sheet.dismiss()
finish()
}
}
container.addView(endSession, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiHelper.dpToPx(context, 48f).toInt()).apply {
topMargin = UiHelper.dpToPx(context, 10f).toInt()
})

sheet.setContentView(container)
sheet.setOnShowListener { NovaSheetChrome.applyBottomSheetChrome(sheet, container) }
sheet.show()
NovaSheetChrome.applyBottomSheetChrome(sheet, container)
}
override fun showGameMenu(device:GameInputDevice?) {
showGameMenuFromDisplay(INVALID_DISPLAY_ID, device)
}

fun showGameMenuFromDisplay(originDisplayId:Int, device:GameInputDevice?) {
val companionPresentation = externalDisplayControlPresentation
val presentation = companionPresentation?.takeIf { it.isCompanionDisplayAvailable() }
val companionDisplayId = if (presentation != null && companionControlDisplayId != INVALID_DISPLAY_ID)
{
companionControlDisplayId
}
else
{
null
}
val origin = originDisplayId.takeIf { it != INVALID_DISPLAY_ID }
origin?.let(::recordQuickMenuInteraction)
val lastInteraction = lastQuickMenuInteractionDisplayId.takeIf { it != INVALID_DISPLAY_ID }
val requestedDestination = DualScreenQuickMenuPolicy.resolve(
prefConfig?.quickMenuDisplayPolicy,
origin,
lastInteraction,
streamingDisplayId,
companionDisplayId,
)
val actualDestination = DualScreenQuickMenuPolicy.openWithFallback(
requestedDestination,
showStream = {
companionPresentation?.hideGameMenu()
gameMenuCallbacks?.showMenu(device)
},
showCompanion = {
gameMenuCallbacks?.hideMenu()
presentation?.showGameMenuOnCompanion(device) == true
},
)
LimeLog.info(
"Nova: Android quick menu policy=${prefConfig?.quickMenuDisplayPolicy} " +
"origin_display_id=${origin ?: "none"} last_interaction_display_id=${lastInteraction ?: "none"} " +
"stream_display_id=$streamingDisplayId companion_display_id=${companionDisplayId ?: "none"} " +
"requested_destination=${requestedDestination.name.lowercase()} " +
"destination=${actualDestination.name.lowercase()}"
)
}

private fun isAnyGameMenuOpen():Boolean {
return gameMenuCallbacks?.isMenuOpen() == true ||
externalDisplayControlPresentation?.isGameMenuOpen() == true
}

 fun hideGameMenu() {
gameMenuCallbacks?.hideMenu()
externalDisplayControlPresentation?.hideGameMenu()
}

private fun updateFloatingButtonVisibility(show:Boolean) {
floatingMenuButton!!.setVisibility(if (show) View.VISIBLE else View.GONE)
}
 fun toggleFloatingButtonVisibility() {
if (floatingMenuButton != null)
{
updateFloatingButtonVisibility(floatingMenuButton!!.getVisibility() == View.GONE)
}
}


 // 设置surfaceView的圆角 setSurfaceviewCorner(UiHelper.dpToPx(this,24));
    private fun setSurfaceviewCorner(radius:Float) {

streamContainer!!.setOutlineProvider(object : ViewOutlineProvider() {
override fun getOutline(view:View?, outline:Outline?) {
var rect:Rect = Rect()
view!!.getGlobalVisibleRect(rect)
var leftMargin:Int = 0
var topMargin:Int = 0
var selfRect:Rect = Rect(leftMargin, topMargin, rect.right - rect.left - leftMargin, rect.bottom - rect.top - topMargin)
outline!!.setRoundRect(selfRect, radius)
}
})
streamContainer!!.setClipToOutline(true)
}
override fun handleCommitText(text:CharSequence):Boolean {
if (!prefConfig!!.enableCommitText || conn == null)
{
return false
}
enqueueCommitText(text!!.toString())
return true
}
override fun handleDeleteSurroundingText(beforeLength:Int, afterLength:Int):Boolean {
if (!prefConfig!!.enableCommitText || conn == null)
{
return false
}
 // Send backspace events for deleted preceding characters
        if (beforeLength > 0)
{
var backspaceCode:Short = keyboardTranslator!!.translate(KeyEvent.KEYCODE_DEL, 0, -1)
for (i:Int in 0 until beforeLength)
{
conn!!.sendKeyboardInput(backspaceCode, com.papi.nova.nvstream.input.KeyboardPacket.KEY_DOWN, 0.toByte(), 0.toByte())
conn!!.sendKeyboardInput(backspaceCode, com.papi.nova.nvstream.input.KeyboardPacket.KEY_UP, 0.toByte(), 0.toByte())
}
}
return true
}

private fun enqueueCommitText(text:String?) {
if (text == null || text!!.isEmpty())
{
return
}
var utf8:ByteArray = text!!.toByteArray(StandardCharsets.UTF_8)
var offset:Int = 0
while (offset < utf8.size)
{
var end:Int = Math.min(offset + UTF8_CHUNK_SIZE, utf8.size)
 // Ensure we don't cut inside a multi-byte sequence
            while (end < utf8.size && (utf8[end].toInt() and 0xC0) == 0x80)
{
end-- // step back until we are at start of code point
}
var chunk:String = String(utf8, offset, end - offset, StandardCharsets.UTF_8)
commitTextQueue.add(chunk)
offset = end
}
 // Kick off flushing if not already scheduled
        if (commitTextQueue.size == 1)
{
commitTextHandler.post(flushCommitTextQueue)
}
}
// Converted JavaDoc marker retained as a line comment.
    private fun findFirstSurfaceViewFrom(v:View?):SurfaceView? {
if (v is SurfaceView) return v as SurfaceView?
if (v is ViewGroup)
{
var g:ViewGroup? = v as ViewGroup?
for (i:Int in 0 until g!!.getChildCount())
{
var found:SurfaceView? = findFirstSurfaceViewFrom(g!!.getChildAt(i))
if (found != null) return found
}
}
return null
}

companion object {
 @JvmField var instance:Game? = null
 @JvmField @Volatile var isStreamActive:Boolean = false

 private const val REFERENCE_HORIZ_RES:Int = 1280
 private const val REFERENCE_VERT_RES:Int = 720

 private const val STYLUS_DOWN_DEAD_ZONE_DELAY:Int = 100
 private const val STYLUS_DOWN_DEAD_ZONE_RADIUS:Int = 20

 private const val STYLUS_UP_DEAD_ZONE_DELAY:Int = 150
 private const val STYLUS_UP_DEAD_ZONE_RADIUS:Int = 50

 private const val THREE_FINGER_TAP_THRESHOLD:Int = 300
 private const val FOUR_FINGER_TAP_THRESHOLD:Int = 300
 private const val FIVE_FINGER_TAP_THRESHOLD:Int = 300
 private const val POLARIS_SESSION_STATUS_REFRESH_MS:Long = 15000L
 private const val NOVA_PROGRESS_READY_DISMISS_DELAY_MS:Long = 350L
 private const val INVALID_DISPLAY_ID:Int = -1

 const val EXTRA_HOST:String = "Host"
 const val EXTRA_PORT:String = "Port"
 const val EXTRA_HTTPS_PORT:String = "HttpsPort"
 const val EXTRA_APP_NAME:String = "AppName"
 const val EXTRA_APP_UUID:String = "AppUUID"
 const val EXTRA_APP_ID:String = "AppId"
 const val EXTRA_UNIQUEID:String = "UniqueId"
 const val EXTRA_PC_UUID:String = "UUID"
 const val EXTRA_PC_NAME:String = "PcName"
 const val EXTRA_APP_HDR:String = "HDR"
 const val EXTRA_SERVER_CERT:String = "ServerCert"
 const val EXTRA_VDISPLAY:String = "VirtualDisplay"
 const val EXTRA_DISPLAY_MODE_EXPLICIT:String = "DisplayModeExplicit"
 const val EXTRA_MIRROR_DESKTOP:String = "MirrorDesktop"
const val EXTRA_FORCE_PRIVATE_AFTER_STEAM_CLOSE:String = "ForcePrivateAfterSteamClose"
 const val EXTRA_WATCH_ONLY:String = "WatchOnly"
 const val EXTRA_STREAM_WIDTH:String = "StreamWidth"
 const val EXTRA_STREAM_HEIGHT:String = "StreamHeight"
 const val EXTRA_STREAM_FPS:String = "StreamFps"
 const val EXTRA_AI_PROFILE_PREFERENCE:String = "AiProfilePreference"
 const val EXTRA_LAUNCH_OPTIMIZATION:String = "LaunchOptimization"
 const val EXTRA_SERVER_COMMANDS:String = "ServerCommands"
 const val EXTRA_DISPLAY_ID:String = "DisplayID"

 const val CLIPBOARD_IDENTIFIER:String = "ArtemisStreaming"
 private const val CLICK_ACTION_THRESHOLD:Float = 5f

 // Queue for batching commitText payloads
 private const val UTF8_CHUNK_SIZE:Int = 512

 @JvmStatic fun displaySupportsHdr10(hdrCapabilities:Display.HdrCapabilities?):Boolean {
if (hdrCapabilities == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
{
return false
}

for (hdrType:Int in hdrCapabilities!!.getSupportedHdrTypes())
{
if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10)
{
return true
}
}

return false
}

 @JvmStatic fun shouldRequestHdrStream(prefEnableHdr:Boolean,
onExternalDisplay:Boolean,
sdkInt:Int,
displaySupportsHdr10:Boolean):Boolean {
if (!prefEnableHdr)
{
return false
}

if (onExternalDisplay)
{
return true
}

if (sdkInt < Build.VERSION_CODES.N)
{
return false
}

if (displaySupportsHdr10)
{
return true
}

 // Explicit HDR opt-in still requests a 10-bit stream on SDR panels.
        return true
}

 @JvmStatic fun shouldShowSdr10BitOptInToast(prefEnableHdr:Boolean,
onExternalDisplay:Boolean,
sdkInt:Int,
displaySupportsHdr10:Boolean):Boolean {
return (prefEnableHdr &&
!onExternalDisplay &&
sdkInt >= Build.VERSION_CODES.N &&
!displaySupportsHdr10)
}

 @JvmStatic fun shouldShowHdrRequiresAndroidNToast(prefEnableHdr:Boolean,
onExternalDisplay:Boolean,
sdkInt:Int):Boolean {
return (prefEnableHdr &&
!onExternalDisplay &&
sdkInt < Build.VERSION_CODES.N)
}

 @JvmStatic fun shouldShowPolarisLockOverlay(screenLocked:Boolean, cageRunning:Boolean):Boolean {
return screenLocked && !cageRunning
}

private fun isDisconnectIntent(intent:Intent?):Boolean {
return (intent != null && com.papi.nova.service.NovaQsTile.NOVA_DISCONNECT_ACTION.equals(intent!!.getAction()))
}

 @JvmStatic fun formatCurrentTime(currentTimeMillis:Long):String? {
var dateFormat:SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")
var date:Date = Date(currentTimeMillis)
return dateFormat.format(date)
}

@NonNull private fun cloneClipData(clipDescription:ClipDescription?, item:ClipData.Item?):ClipData? {
var clonedDescription:ClipDescription = ClipDescription(clipDescription)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
{
var extras:PersistableBundle? = clipDescription!!.getExtras()
if (extras == null)
{
extras = PersistableBundle()
}
extras!!.putBoolean(CLIPBOARD_IDENTIFIER, true)
clonedDescription.setExtras(extras)
}

return ClipData(clonedDescription, item)
}

private fun normalizeValueInRange(value:Float, range:InputDevice.MotionRange?):Float {
return (value - range!!.getMin()) / range!!.getRange()
}

private fun getPressureOrDistance(event:MotionEvent?, pointerIndex:Int):Float {
var dev:InputDevice? = event!!.getDevice()
when (event!!.getActionMasked()) {
MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> {
 // Hover events report distance
                if (dev != null)
{
var distanceRange:InputDevice.MotionRange? = dev!!.getMotionRange(MotionEvent.AXIS_DISTANCE, event!!.getSource())
if (distanceRange != null)
{
return normalizeValueInRange(event!!.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex), distanceRange)
}
}
return 0.0f
}

else ->
 // Other events report pressure
                return event!!.getPressure(pointerIndex)
}
}

private fun getRotationDegrees(event:MotionEvent?, pointerIndex:Int):Short {
var dev:InputDevice? = event!!.getDevice()
if (dev != null)
{
if (dev!!.getMotionRange(MotionEvent.AXIS_ORIENTATION, event!!.getSource()) != null)
{
var rotationDegrees:Int = Math.toDegrees(event!!.getOrientation(pointerIndex).toDouble()).toInt()
if (rotationDegrees < 0)
{
rotationDegrees += 360
}
return rotationDegrees.toShort()
}
}
return MoonBridge.LI_ROT_UNKNOWN
}

private fun polarToCartesian(r:Float, theta:Float):FloatArray? {
return floatArrayOf((r * Math.cos(theta.toDouble())).toFloat(), (r * Math.sin(theta.toDouble())).toFloat())
}

private fun cartesianToR(point:FloatArray?):Float {
return Math.sqrt(Math.pow(point!![0].toDouble(), 2.0) + Math.pow(point!![1].toDouble(), 2.0)).toFloat()
}

private fun convertToolTypeToStylusToolType(event:MotionEvent?, pointerIndex:Int):Byte {
when (event!!.getToolType(pointerIndex)) {
MotionEvent.TOOL_TYPE_ERASER -> return MoonBridge.LI_TOOL_TYPE_ERASER
MotionEvent.TOOL_TYPE_STYLUS -> return MoonBridge.LI_TOOL_TYPE_PEN
else -> return MoonBridge.LI_TOOL_TYPE_UNKNOWN
}
}

private fun getSummaryDouble(summary:Map<String, Any>?, key:String, fallback:Double):Double {
var value:Any? = summary?.get(key)
return if (value is Number) (value as Number).toDouble() else fallback
}

private fun getSummaryInt(summary:Map<String, Any>?, key:String, fallback:Int):Int {
var value:Any? = summary?.get(key)
return if (value is Number) (value as Number).toInt() else fallback
}

private fun getSummaryString(summary:Map<String, Any>?, key:String):String {
var value:Any? = summary?.get(key)
return if (value is String) value else ""
}

@Suppress("UNCHECKED_CAST")
private fun getSummaryStringList(summary:Map<String, Any>?, key:String):List<String> {
var value:Any? = summary?.get(key)
if (value is java.util.List<*>)
{
var result:java.util.ArrayList<String> = java.util.ArrayList()
for (item:Any? in (value as java.util.List<*>?)!!)
{
if (item is String)
{
result.add(item)
}
}
return result
}
return emptyList()
}

private fun getSummaryBoolean(summary:Map<String, Any>?, key:String):Boolean? {
var value:Any? = summary?.get(key)
return if (value is Boolean) value else null
}
}

}// Actual invert logic is handled within the touch context
