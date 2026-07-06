package com.papi.nova.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatActivity
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
 * A standalone Activity providing a full-screen touchpad controller for the secondary display.
 * It creates its own UI programmatically and hosts the GameMenu for in-game options.
 */
class ExternalDisplayControlActivity :
    AppCompatActivity(),
    View.OnKeyListener,
    KeyBoardLayoutController.ViewCallbacks {

    private lateinit var prefConfig: PreferenceConfiguration

    private lateinit var rootLayout: ExternalControllerView
    private lateinit var zoomButton: ImageButton
    private var keyBoardLayoutController: KeyBoardLayoutController? = null

    private var isKeyboardVisible = false

    private val handler = Handler(Looper.getMainLooper())
    private var failCount = 0
    private var dimScreenRunnable = Runnable {}
    private var originalBrightness = -1f // -1 = use system default

    private var gameMenu: GameMenu? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        instance = this
        prefConfig = PreferenceConfiguration.readPreferences(this)

        if (!isGameInstanceAvailable()) {
            @Suppress("DEPRECATION")
            val gameIntent = intent.getParcelableExtra<Intent>(EXTRA_LAUNCH_INTENT)
            if (gameIntent == null) {
                finish()
            } else {
                val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val targetDisplayId = gameIntent.getIntExtra(Game.EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
                val targetDisplay = displayManager.getDisplay(targetDisplayId)
                if (targetDisplay != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val options = ActivityOptions.makeBasic()
                    options.setLaunchDisplayId(targetDisplay.displayId)
                    if (targetDisplay.displayId != Display.DEFAULT_DISPLAY) {
                        Toast.makeText(
                            this,
                            getString(
                                R.string.external_display_info,
                                targetDisplay.mode.physicalWidth,
                                targetDisplay.mode.physicalHeight,
                                targetDisplay.mode.refreshRate,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }

                    startActivity(gameIntent, options.toBundle())
                } else {
                    LimeLog.warning(getString(R.string.no_external_display))
                    startActivity(gameIntent)
                    finish()
                }
            }
        }

        initViews()
    }

    private fun initViews() {
        if (Game.instance == null) {
            if (failCount > 10) {
                Toast.makeText(this, getString(R.string.no_game_instance), Toast.LENGTH_LONG).show()
                finish()
            }
            handler.postDelayed({ initViews() }, 500)
            failCount++
            return
        }

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
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
        checkNotificationPermission()
        initTouchEventHandling()
        setupInactivityTimeoutForBrightness()
        StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initTouchEventHandling() {
        rootLayout.setOnTouchListener { view, event ->
            handleUserActivity()
            Game.instance?.handleMotionEvent(view, event)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isGameInstanceAvailable() && gameMenu != null) {
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isGameInstanceAvailable()) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onKeyboardControllerVisibilityChange(visible: Boolean) {
        updateKeyboardVisibility(visible)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInactivityTimeoutForBrightness() {
        originalBrightness = window.attributes.screenBrightness

        dimScreenRunnable = Runnable {
            val layout = window.attributes
            layout.screenBrightness = 0.0f
            window.attributes = layout
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
        val layout = window.attributes
        if (layout.screenBrightness == 0.0f) {
            layout.screenBrightness = originalBrightness
            window.attributes = layout
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
        val game = Game.instance
        if (game != null) {
            game.handleFocusChange(hasFocus)
        } else {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val game = Game.instance
        if (game != null && game.isKeyboardLayoutVisible) {
            toggleFullKeyboard()
        } else if (gameMenu != null && gameMenu?.isMenuOpen() == false && game != null) {
            game.onBackPressed()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun isGameInstanceAvailable(): Boolean {
        return Game.instance != null
    }

    private fun initializeComponents() {
        gameMenu = GameMenu(Game.instance ?: return, this)
    }

    override fun onConfigurationChanged(@NonNull newConfig: Configuration) {
        Game.instance?.onConfigurationChanged(newConfig)
        super.onConfigurationChanged(newConfig)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val game = Game.instance
        if (game != null) {
            StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
            return game.onGenericMotionEvent(event)
        }
        return false
    }

    override fun onKey(view: View, keyCode: Int, keyEvent: KeyEvent): Boolean {
        val game = Game.instance
        if (game != null) {
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

        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val game = Game.instance
        if (game != null) {
            if (event.deviceId >= 0) {
                StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
            }
            return game.onKeyDown(keyCode, event)
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val game = Game.instance
        if (game != null) {
            if (event.deviceId >= 0) {
                StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
            }
            return game.onKeyUp(keyCode, event)
        }
        return false
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val game = Game.instance
        if (game != null) {
            if (event.deviceId >= 0) {
                StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
            }
            return game.onKeyMultiple(keyCode, repeatCount, event)
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createProgrammaticUI() {
        rootLayout = ExternalControllerView(this)
        rootLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        rootLayout.isFocusable = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            rootLayout.isFocusedByDefault = true
        }

        rootLayout.setInputCallbacks(Game.instance)
        rootLayout.setCommitTextEnabled(prefConfig.enableCommitText)

        setContentView(rootLayout)

        val topLeftButtons = createButtonContainer(Gravity.TOP or Gravity.START)
        topLeftButtons.isFocusable = false
        zoomButton = createImageButton(R.drawable.ic_zoom_toggle) { toggleZoomMode(true) }
        if (Game.instance?.isZoomModeEnabled == true) {
            zoomButton.alpha = 1.0f
        } else {
            zoomButton.alpha = 0.5f
        }
        topLeftButtons.addView(zoomButton)
        rootLayout.addView(topLeftButtons)

        val topRightButtons = createButtonContainer(Gravity.TOP or Gravity.END)
        topRightButtons.isFocusable = false
        topRightButtons.addView(createImageButton(R.drawable.ic_menu_external) { showGameMenu() })
        topRightButtons.addView(createImageButton(R.drawable.ic_close_external) { finish() })
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

    private fun _toggleKeyboard() {
        LimeLog.info("Toggling keyboard overlay on ExternalDisplayControlActivity")
        val inputManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.toggleSoftInput(0, 0)
    }

    private fun initFullKeyboard(prefConfig: PreferenceConfiguration) {
        keyBoardLayoutController = KeyBoardLayoutController(rootLayout, this, prefConfig).also {
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
        val game = Game.instance
        if (game != null) {
            if (callGame) {
                game.toggleZoomMode()
            } else {
                zoomButton.alpha = if (game.isZoomModeEnabled) 1.0f else 0.5f
            }
        }
    }

    fun showGameMenu() {
        gameMenu?.showMenu(null)
    }

    private fun createButtonContainer(gravity: Int): LinearLayout {
        return LinearLayout(this).apply {
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
        return ImageButton(this).apply {
            setImageResource(imageResourceId)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener(listener)
            layoutParams = LinearLayout.LayoutParams(dpToPx(56), dpToPx(56))
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_CODE,
                )
                return
            }
        }
        showStickyNotification()
    }

    private fun showStickyNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.setShowBadge(false)
            notificationManager.createNotificationChannel(channel)
        }

        val broadcastIntent = Intent(this, StartExternalDisplayControlReceiver::class.java)
            .setAction(StartExternalDisplayControlReceiver.ACTION_START_EXTERNAL_DISPLAY_CONTROL)
            .setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            broadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.app_icon)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)

        val notification: Notification = notificationBuilder.build()

        notificationManager.notify(SECONDARY_SCREEN_NOTIFICATION_ID, notification)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showStickyNotification()
            } else {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        @JvmField
        var EXTRA_LAUNCH_INTENT: String = "launchIntent"

        @SuppressLint("StaticFieldLeak")
        @JvmField
        var instance: ExternalDisplayControlActivity? = null

        private const val INACTIVITY_TIMEOUT_MS = 10_000
        private const val NOTIFICATION_CHANNEL_ID = "secondary_screen_active_channel_id"

        const val SECONDARY_SCREEN_NOTIFICATION_ID = 1
        private const val PERMISSION_REQUEST_CODE = 1001

        @JvmStatic
        fun closeExternalDisplayControl() {
            instance?.finish()
        }

        @JvmStatic
        fun toggleKeyboard() {
            instance?._toggleKeyboard()
        }

        @JvmStatic
        fun toggleFullKeyboard() {
            instance?._toggleFullKeyboard()
        }

        @JvmStatic
        fun toggleGameMenu() {
            instance?.showGameMenu()
        }
    }
}
