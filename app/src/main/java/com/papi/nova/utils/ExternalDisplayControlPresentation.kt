package com.papi.nova.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.papi.nova.Game
import com.papi.nova.GameMenu
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.StartExternalDisplayControlReceiver
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardLayoutController
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.ExternalControllerView

/**
 * Game-owned controller surface rendered on the derived companion display without creating
 * another Activity/task that can become Android's top-resumed audio owner.
 */
class ExternalDisplayControlPresentation(
    private val game: Game,
    display: Display,
) : Presentation(game, display, R.style.ExternalDisplayControllerTheme),
    View.OnKeyListener,
    KeyBoardLayoutController.ViewCallbacks {

    private lateinit var prefConfig: PreferenceConfiguration

    private lateinit var rootLayout: ExternalControllerView
    private lateinit var zoomButton: ImageButton
    private var keyBoardLayoutController: KeyBoardLayoutController? = null

    private var isKeyboardVisible = false

    private val handler = Handler(Looper.getMainLooper())
    private var dimScreenRunnable = Runnable {}
    private var originalBrightness = -1f // -1 = use system default

    private var gameMenu: GameMenu? = null
    private val presentationWindow: Window
        get() = requireNotNull(window)

    val companionDialogContext: Context by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            game.createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
                null,
            )
        } else {
            context
        }
    }

    val companionDialogWindowType: Int
        get() = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG

    fun companionDialogWindowToken(): IBinder? = presentationWindow.decorView.windowToken

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefConfig = PreferenceConfiguration.readPreferences(context)
        initViews()
    }

    private fun initViews() {
        val windowInsetsController = WindowCompat.getInsetsController(presentationWindow, presentationWindow.decorView)

        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(presentationWindow, false)
            ViewCompat.setOnApplyWindowInsetsListener(presentationWindow.decorView) { view, insets ->
                val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                updateKeyboardVisibility(
                    imeVisible ||
                        (keyBoardLayoutController != null && keyBoardLayoutController!!.isKeyboardVisible()),
                )
                ViewCompat.onApplyWindowInsets(view, insets)
            }
        }

        initializeComponents()
        createProgrammaticUI()
        initTouchEventHandling()
        setupInactivityTimeoutForBrightness()
        StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initTouchEventHandling() {
        rootLayout.setOnTouchListener { view, event ->
            handleUserActivity()
            game.handleMotionEvent(view, event)
            true
        }
    }

    override fun onStart() {
        super.onStart()
        if (game.isFinishing) {
            dismissAfterCurrentCallback()
        }
    }

    fun dismissAfterCurrentCallback() {
        handler.post {
            if (isShowing) {
                dismiss()
            }
        }
    }

    override fun cancel() {
        handler.post {
            cancelNow()
        }
    }

    private fun cancelNow() {
        if (isShowing) {
            super.cancel()
        }
    }

    private fun disposeTransientState() {
        handler.removeCallbacksAndMessages(null)
        gameMenu?.hideMenu()
        restoreBrightnessIfNeeded()
    }

    fun disposeAfterFailedShow() {
        disposeTransientState()
    }

    override fun onStop() {
        disposeTransientState()
        super.onStop()
    }

    override fun onKeyboardControllerVisibilityChange(visible: Boolean) {
        updateKeyboardVisibility(visible)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInactivityTimeoutForBrightness() {
        originalBrightness = presentationWindow.attributes.screenBrightness

        dimScreenRunnable = Runnable {
            val layout = presentationWindow.attributes
            layout.screenBrightness = 0.0f
            presentationWindow.attributes = layout
        }

        resetInactivityTimer()
    }

    private fun updateKeyboardVisibility(visible: Boolean) {
        if (isKeyboardVisible != visible) {
            isKeyboardVisible = visible
            if (isKeyboardVisible) {
                handler.removeCallbacks(dimScreenRunnable)
                restoreBrightnessIfNeeded()
            } else {
                resetInactivityTimer()
            }
        }
    }

    private fun restoreBrightnessIfNeeded() {
        val layout = presentationWindow.attributes
        if (layout.screenBrightness == 0.0f) {
            layout.screenBrightness = originalBrightness
            presentationWindow.attributes = layout
        }
    }

    private fun handleUserActivity() {
        restoreBrightnessIfNeeded()
        resetInactivityTimer()
    }

    private fun resetInactivityTimer() {
        handler.removeCallbacks(dimScreenRunnable)
        if (!isKeyboardVisible) {
            handler.postDelayed(dimScreenRunnable, INACTIVITY_TIMEOUT_MS.toLong())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        game.logCompanionDisplayFocus(display.displayId, hasFocus)
        if (game.isFinishing) {
            dismissAfterCurrentCallback()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (game.isKeyboardLayoutVisible) {
            toggleFullKeyboard()
        } else if (gameMenu != null && gameMenu?.isMenuOpen() == false) {
            game.onBackPressed()
        } else {
            cancel()
        }
    }

    private fun initializeComponents() {
        gameMenu = GameMenu(game, companionDialogContext, companionDialogWindowType, ::companionDialogWindowToken)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
        return game.onGenericMotionEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onKey(view: View, keyCode: Int, keyEvent: KeyEvent): Boolean {
        if (keyEvent.deviceId >= 0) {
            StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
        }
        return when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> game.handleKeyDown(keyEvent)
            KeyEvent.ACTION_UP -> game.handleKeyUp(keyEvent)
            KeyEvent.ACTION_MULTIPLE -> game.handleKeyMultiple(keyEvent)
            else -> false
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.deviceId >= 0) {
            StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
        }
        return game.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (event.deviceId >= 0) {
            StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
        }
        return game.onKeyUp(keyCode, event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (event.deviceId >= 0) {
            StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
        }
        return game.onKeyMultiple(keyCode, repeatCount, event)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createProgrammaticUI() {
        rootLayout = ExternalControllerView(context)
        rootLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        rootLayout.isFocusable = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            rootLayout.isFocusedByDefault = true
        }

        rootLayout.setInputCallbacks(game)
        rootLayout.setCommitTextEnabled(prefConfig.enableCommitText)

        setContentView(rootLayout)

        val topLeftButtons = createButtonContainer(Gravity.TOP or Gravity.START)
        topLeftButtons.isFocusable = false
        zoomButton = createImageButton(R.drawable.ic_zoom_toggle) { toggleZoomMode(true) }
        if (game.isZoomModeEnabled) {
            zoomButton.alpha = 1.0f
        } else {
            zoomButton.alpha = 0.5f
        }
        topLeftButtons.addView(zoomButton)
        rootLayout.addView(topLeftButtons)

        val topRightButtons = createButtonContainer(Gravity.TOP or Gravity.END)
        topRightButtons.isFocusable = false
        topRightButtons.addView(createImageButton(R.drawable.ic_menu_external) { showGameMenu() })
        topRightButtons.addView(createImageButton(R.drawable.ic_close_external) { dismissAfterCurrentCallback() })
        rootLayout.addView(topRightButtons)

        val bottomLeftButton = createButtonContainer(Gravity.BOTTOM or Gravity.START)
        bottomLeftButton.isFocusable = false
        bottomLeftButton.addView(createImageButton(R.drawable.ic_android_keyboard) { _toggleKeyboard() })
        rootLayout.addView(bottomLeftButton)

        val bottomRightButton = createButtonContainer(Gravity.BOTTOM or Gravity.END)
        bottomRightButton.isFocusable = false
        bottomRightButton.addView(createImageButton(R.drawable.ic_fullscreen_keyboard) { _toggleFullKeyboard() })
        rootLayout.addView(bottomRightButton)
    }

    @Suppress("DEPRECATION")
    private fun _toggleKeyboard() {
        LimeLog.info("Toggling keyboard overlay on ExternalDisplayControlPresentation")
        val inputManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.toggleSoftInput(0, 0)
    }

    private fun initFullKeyboard(prefConfig: PreferenceConfiguration) {
        keyBoardLayoutController = KeyBoardLayoutController(rootLayout, context, prefConfig).also {
            it.setViewCallbacks(this)
            it.refreshLayout()
            it.show()
        }
    }

    private fun _toggleFullKeyboard() {
        val controller = keyBoardLayoutController
        if (controller == null) {
            initFullKeyboard(prefConfig)
            return
        }
        controller.toggleVisibility()
    }

    fun toggleZoomMode(callGame: Boolean) {
        if (callGame) {
            game.toggleZoomMode()
        } else {
            zoomButton.alpha = if (game.isZoomModeEnabled) 1.0f else 0.5f
        }
    }

    fun showGameMenu() {
        gameMenu?.showMenu(null)
    }

    private fun createButtonContainer(gravity: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(gravity)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                gravity,
            )
        }
    }

    private fun createImageButton(imageResourceId: Int, listener: View.OnClickListener): ImageButton {
        return ImageButton(context).apply {
            setImageResource(imageResourceId)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener(listener)
            layoutParams = LinearLayout.LayoutParams(dpToPx(56), dpToPx(56))
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    fun toggleKeyboard() {
        _toggleKeyboard()
    }

    fun toggleFullKeyboard() {
        _toggleFullKeyboard()
    }

    fun toggleGameMenu() {
        showGameMenu()
    }

    companion object {
        private const val INACTIVITY_TIMEOUT_MS = 10_000
        private const val NOTIFICATION_CHANNEL_ID = "secondary_screen_active_channel_id"

        const val SECONDARY_SCREEN_NOTIFICATION_ID = 1
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001

        @JvmStatic
        fun ensureCompanionControlsNotification(game: Game) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(game, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    game,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE,
                )
                return
            }
            postCompanionControlsNotification(game)
        }

        @JvmStatic
        fun onCompanionNotificationPermissionResult(
            game: Game,
            granted: Boolean,
            shouldPost: Boolean,
        ) {
            if (granted && shouldPost) {
                postCompanionControlsNotification(game)
            } else if (!granted) {
                Toast.makeText(game, game.getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
            }
        }

        private fun postCompanionControlsNotification(context: Context) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
                channel.setShowBadge(false)
                notificationManager.createNotificationChannel(channel)
            }

            val broadcastIntent = Intent(context, StartExternalDisplayControlReceiver::class.java)
                .setAction(StartExternalDisplayControlReceiver.ACTION_START_EXTERNAL_DISPLAY_CONTROL)
                .setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notificationBuilder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_title))
                .setContentText(context.getString(R.string.notification_text))
                .setSmallIcon(R.drawable.app_icon)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)

            val notification: Notification = notificationBuilder.build()
            notificationManager.notify(SECONDARY_SCREEN_NOTIFICATION_ID, notification)
        }
    }
}
