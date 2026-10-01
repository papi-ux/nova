package com.papi.nova.utils

import android.app.Activity
import android.app.GameManager
import android.app.GameState
import android.app.LocaleManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Insets
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.NovaSystemBars
import com.papi.nova.ui.NovaCameraViewAvoidance
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaSurfaces
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

object UiHelper {
    // The television title-safe area, as the panels keep it: 15dp left the host list and Settings
    // under a television's overscan.
    private const val TV_VERTICAL_PADDING_DP = 27
    private const val TV_HORIZONTAL_PADDING_DP = 48
    private val confirmSerial = AtomicLong()
    private val confirmationLinkStyles = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))

    @JvmStatic
    fun isTvDevice(context: Context): Boolean {
        val modeType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (modeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }

        val manager = context.packageManager
        return manager != null &&
            (manager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 &&
                    manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)))
    }

    @JvmStatic
    fun applyTvFocusStyle(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.isClickable = true
    }

    @JvmStatic
    fun applyTvFocusStyle(context: Context, view: View) {
        applyTvFocusStyle(view)
    }

    private fun setGameModeStatus(context: Context, streaming: Boolean, interruptible: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val gameManager = context.getSystemService(GameManager::class.java)

            if (gameManager == null) {
                LimeLog.warning("GameManager is null, maybe your system does not support it?")
                return
            }

            if (streaming) {
                gameManager.setGameState(
                    GameState(
                        false,
                        if (interruptible) {
                            GameState.MODE_GAMEPLAY_INTERRUPTIBLE
                        } else {
                            GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE
                        },
                    ),
                )
            } else {
                gameManager.setGameState(GameState(false, GameState.MODE_NONE))
            }
        }
    }

    @JvmStatic
    fun notifyStreamConnecting(context: Context) {
        setGameModeStatus(context, true, true)
    }

    @JvmStatic
    fun notifyStreamConnected(context: Context) {
        setGameModeStatus(context, true, false)
    }

    @JvmStatic
    fun notifyStreamEnteringPiP(context: Context) {
        setGameModeStatus(context, true, true)
    }

    @JvmStatic
    fun notifyStreamExitingPiP(context: Context) {
        setGameModeStatus(context, true, false)
    }

    @JvmStatic
    fun notifyStreamEnded(context: Context) {
        setGameModeStatus(context, false, false)
    }

    @JvmStatic
    fun resolveLocaleForTests(language: String?, systemLocale: Locale): Locale {
        val selectedLanguage = language ?: PreferenceConfiguration.DEFAULT_LANGUAGE
        return if (selectedLanguage == PreferenceConfiguration.DEFAULT_LANGUAGE) {
            systemLocale
        } else {
            Locale.forLanguageTag(selectedLanguage.replace('_', '-'))
        }
    }

    @JvmStatic
    fun setLocale(activity: Activity) {
        val language = PreferenceConfiguration.readPreferences(activity).language
        val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = activity.getSystemService(LocaleManager::class.java)
            val systemLocales = localeManager?.systemLocales
            if (systemLocales != null && !systemLocales.isEmpty) {
                systemLocales[0]
            } else {
                Locale.getDefault()
            }
        } else {
            Locale.getDefault()
        }
        val config = Configuration(activity.resources.configuration)
        config.setLocale(resolveLocaleForTests(language, systemLocale))

        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
    }

    @JvmStatic
    fun applyStatusBarPadding(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // This applies the padding omitted in notifyNewRootView() on Q.
            view.setOnApplyWindowInsetsListener { targetView: View, windowInsets: WindowInsets ->
                targetView.setPadding(
                    targetView.paddingLeft,
                    targetView.paddingTop,
                    targetView.paddingRight,
                    windowInsets.tappableElementInsets.bottom,
                )
                windowInsets
            }
            view.requestApplyInsets()
        }
    }

    /**
     * Sets up a legacy screen's root view. [insetTarget] is the view padded clear of
     * the system bars; a screen whose background should run under them passes its
     * content layout, so only the controls move.
     */
    @JvmStatic
    @JvmOverloads
    fun notifyNewRootView(
        activity: Activity,
        insetTarget: View = activity.findViewById(android.R.id.content),
        localizeCamera: Boolean = false,
        padSystemBars: Boolean = true,
    ) {
        val rootView = activity.findViewById<View>(android.R.id.content)
        val modeMgr = activity.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager

        setGameModeStatus(activity, false, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window.attributes = activity.window.attributes.apply {
                layoutInDisplayCutoutMode = if (localizeCamera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        if (!padSystemBars) {
            // Compose keeps bars/IME/TV safety inside its background and handles cameras locally.
            insetTarget.setOnApplyWindowInsetsListener(null)
            insetTarget.setPadding(0, 0, 0, 0)
        } else if (modeMgr.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            val scale = activity.resources.displayMetrics.density
            val verticalPaddingPixels = (TV_VERTICAL_PADDING_DP * scale + 0.5f).toInt()
            val horizontalPaddingPixels = (TV_HORIZONTAL_PADDING_DP * scale + 0.5f).toInt()

            // The controls keep clear of the edge; a background under them still reaches it.
            insetTarget.setPadding(
                horizontalPaddingPixels,
                verticalPaddingPixels,
                horizontalPaddingPixels,
                verticalPaddingPixels,
            )
        } else if (localizeCamera) {
            ViewCompat.setOnApplyWindowInsetsListener(insetTarget) { view, insets ->
                val hidden = NovaSystemBars.isManaged(activity) && NovaSystemBars.isHidden(activity)
                val types = (if (hidden) WindowInsetsCompat.Type.captionBar() else WindowInsetsCompat.Type.systemBars()) or
                    WindowInsetsCompat.Type.ime()
                val bars = insets.getInsets(types)
                val waterfall = insets.displayCutout?.waterfallInsets ?: androidx.core.graphics.Insets.NONE
                view.setPadding(maxOf(bars.left, waterfall.left), maxOf(bars.top, waterfall.top),
                    maxOf(bars.right, waterfall.right), maxOf(bars.bottom, waterfall.bottom))
                insets
            }
            NovaCameraViewAvoidance.install(insetTarget)
            ViewCompat.requestApplyInsets(insetTarget)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insetTarget.setOnApplyWindowInsetsListener {
                    view: View,
                    windowInsets: WindowInsets,
                ->
                val tappableInsets: Insets = rootInsets(activity, windowInsets)
                view.setPadding(
                    tappableInsets.left,
                    tappableInsets.top,
                    tappableInsets.right,
                    tappableInsets.bottom,
                )

                if (tappableInsets.bottom != 0) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                }

                windowInsets
            }

            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            // Assigning the flags clears the ones Hide System Bars set, so take it again.
            if (NovaSystemBars.isManaged(activity)) NovaSystemBars.apply(activity)
        } else {
            // Below Android 10 nothing padded this content, and the surface-colored bars
            // hid what sat under them. The bars are transparent now, so keep it clear.
            padForSystemBars(insetTarget)
        }
    }

    /** Background at the edge; the Compose screen owns its bar/IME/TV and per-control camera safety. */
    @JvmStatic
    fun notifyEdgeToEdgeComposeRoot(activity: Activity) {
        notifyNewRootView(activity, localizeCamera = true, padSystemBars = false)
    }

    /**
     * What a screen's root keeps clear of.
     *
     * The tappable insets are reported as if the bars were showing even when Hide System Bars has
     * taken them away, so every screen built on this kept an empty band the height of the status
     * bar along its top: 24dp of a handheld's 468, above a rail that then had to scroll. With the
     * bars hidden the root keeps clear of whatever is really on screen, the bars if the system
     * refused to hide them and a cutout if there is one, and of nothing otherwise. A swipe brings
     * the bars back over the content for a moment, which moves nothing.
     */
    private fun rootInsets(activity: Activity, windowInsets: WindowInsets): Insets {
        val barsHidden = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            NovaSystemBars.isManaged(activity) && NovaSystemBars.isHidden(activity)
        return if (barsHidden) {
            windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        } else {
            windowInsets.tappableElementInsets
        }
    }

    /**
     * Pads a plain content view clear of the status and navigation bars and any
     * cutout. Nova draws behind the bars on every API level, so a screen without its
     * own inset handling would otherwise put its first line under the clock.
     */
    @JvmStatic
    fun padContentForSystemBars(activity: Activity) {
        padForSystemBars(activity.findViewById(android.R.id.content) ?: return)
    }

    /** Pads [target] clear of the status and navigation bars and any cutout. */
    @JvmStatic
    fun padForSystemBars(target: View) {
        ViewCompat.setOnApplyWindowInsetsListener(target) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(target)
    }

    /**
     * Offer the crash Nova recorded last time it went down.
     *
     * Separate from the decoder tombstone below, which only ever counted
     * MediaCodec failures. An ordinary uncaught exception left the user with the
     * system's "app has stopped" dialog and left Nova with nothing to report.
     *
     * The marker is consumed whether or not the user sends it, so a single crash
     * is offered once rather than on every launch.
     */
    @JvmStatic
    fun showCrashReportDialog(activity: Activity) {
        val crash = com.papi.nova.diagnostics.NovaDiagnostics.previousCrash(activity) ?: return
        com.papi.nova.diagnostics.NovaDiagnostics.clearPreviousCrash(activity)

        Dialog.displayDialog(
            activity,
            activity.resources.getString(R.string.title_crash_report),
            activity.resources.getString(R.string.message_crash_report, crash.occurredAt),
            false,
            activity.resources.getString(R.string.action_send_crash_report),
            Runnable {
                com.papi.nova.diagnostics.SupportReportSharing.share(
                    activity,
                    com.papi.nova.BuildConfig.VERSION_NAME,
                )
            },
        )
    }

    @JvmStatic
    fun showDecoderCrashDialog(activity: Activity) {
        val prefs = activity.getSharedPreferences("DecoderTombstone", 0)
        val crashCount = prefs.getInt("CrashCount", 0)
        val lastNotifiedCrashCount = prefs.getInt("LastNotifiedCrashCount", 0)

        if (crashCount != 0 && crashCount != lastNotifiedCrashCount) {
            val markAcknowledged = Runnable {
                prefs.edit().putInt("LastNotifiedCrashCount", crashCount).apply()
            }
            if (crashCount % 3 == 0) {
                PreferenceConfiguration.resetStreamingSettings(activity) { saved ->
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        if (saved) {
                            Dialog.displayDialog(activity,
                                activity.getString(R.string.title_decoding_reset),
                                activity.getString(R.string.message_decoding_reset), markAcknowledged)
                        } else {
                            // Leave the crash unacknowledged until a reset has actually saved.
                            Dialog.displayDialog(activity,
                                activity.getString(R.string.title_decoding_reset_failed),
                                activity.getString(R.string.message_decoding_reset_failed), false,
                                activity.getString(R.string.nova_space_launch_issue_retry),
                                Runnable { showDecoderCrashDialog(activity) })
                        }
                    }
                }
            } else {
                Dialog.displayDialog(
                    activity,
                    activity.resources.getString(R.string.title_decoding_error),
                    activity.resources.getString(R.string.message_decoding_error),
                    markAcknowledged,
                )
            }
        }
    }

    /**
     * Asks before going on, as a Confirm page in the right-edge panel (pushed onto a panel that is
     * already open). Stay is focused and runs [onNo], as B does; the action runs [onYes]. [message]
     * is HTML, so a link in it can be followed by touch.
     */
    @JvmStatic
    fun displayConfirmationDialog(
        parent: Activity,
        title: String?,
        message: String,
        btnYesText: String?,
        btnNoText: String?,
        onYes: Runnable?,
        onNo: Runnable?,
    ) {
        presentConfirmation(
            parent,
            title = title ?: parent.getString(R.string.nova_panel_confirm_title),
            message = AnnotatedString.fromHtml(message, linkStyles = confirmationLinkStyles),
            stayLabel = btnNoText ?: parent.getString(R.string.nova_panel_cancel),
            actionLabel = btnYesText ?: parent.getString(android.R.string.ok),
            destructive = false,
            onYes = onYes,
            onNo = onNo,
        )
    }

    private fun presentConfirmation(
        parent: Activity,
        title: String,
        message: AnnotatedString,
        stayLabel: String,
        actionLabel: String,
        destructive: Boolean,
        onYes: Runnable?,
        onNo: Runnable?,
    ) {
        NovaSurfaces.of(parent).present(
            NovaCommonPage.Confirm(
                key = "nova-legacy-confirm-" + confirmSerial.incrementAndGet(),
                title = title,
                message = message,
                stayLabel = stayLabel,
                actionLabel = actionLabel,
                destructive = destructive,
                onConfirm = { onYes?.run() },
                onStay = { onNo?.run() },
            ),
        )
    }

    @JvmStatic
    fun displayVdisplayConfirmationDialog(
        parent: Activity,
        computer: ComputerDetails,
        onYes: Runnable?,
        onNo: Runnable?,
    ) {
        val message = if (computer.vDisplaySupported) {
            parent.resources.getString(R.string.vdisplay_not_ready)
        } else {
            parent.resources.getString(R.string.vdisplay_not_supported)
        }
        displayConfirmationDialog(
            parent,
            parent.resources.getString(R.string.nova_panel_vdisplay_title),
            message,
            parent.resources.getString(R.string.proceed),
            parent.resources.getString(R.string.cancel),
            onYes,
            onNo,
        )
    }

    /** Ends the running session only on a deliberate second step: Stay is focused, and B stays too. */
    @JvmStatic
    fun displayQuitConfirmationDialog(parent: Activity, onYes: Runnable?, onNo: Runnable?) {
        presentConfirmation(
            parent,
            title = parent.getString(R.string.game_dialog_title_quit_confirm),
            message = AnnotatedString(parent.getString(R.string.nova_panel_end_session_message)),
            stayLabel = parent.getString(R.string.nova_panel_stay),
            actionLabel = parent.getString(R.string.game_dialog_action_end_session),
            destructive = true,
            onYes = onYes,
            onNo = onNo,
        )
    }

    @JvmStatic
    fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
    }
}
