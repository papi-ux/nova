package com.papi.nova.utils

import android.app.Activity
import android.app.AlertDialog
import android.app.GameManager
import android.app.GameState
import android.app.LocaleManager
import android.app.UiModeManager
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Insets
import android.os.Build
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.preferences.PreferenceConfiguration
import java.util.Locale

object UiHelper {
    private const val TV_VERTICAL_PADDING_DP = 15
    private const val TV_HORIZONTAL_PADDING_DP = 15

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

    @JvmStatic
    fun notifyNewRootView(activity: Activity) {
        val rootView = activity.findViewById<View>(android.R.id.content)
        val modeMgr = activity.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager

        setGameModeStatus(activity, false, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (modeMgr.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            val scale = activity.resources.displayMetrics.density
            val verticalPaddingPixels = (TV_VERTICAL_PADDING_DP * scale + 0.5f).toInt()
            val horizontalPaddingPixels = (TV_HORIZONTAL_PADDING_DP * scale + 0.5f).toInt()

            rootView.setPadding(
                horizontalPaddingPixels,
                verticalPaddingPixels,
                horizontalPaddingPixels,
                verticalPaddingPixels,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener {
                    view: View,
                    windowInsets: WindowInsets,
                ->
                val tappableInsets: Insets = windowInsets.tappableElementInsets
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
        }
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
                PreferenceConfiguration.resetStreamingSettings(activity)
                Dialog.displayDialog(
                    activity,
                    activity.resources.getString(R.string.title_decoding_reset),
                    activity.resources.getString(R.string.message_decoding_reset),
                    markAcknowledged,
                )
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
        val dialogClickListener = DialogInterface.OnClickListener { _, which ->
            when (which) {
                DialogInterface.BUTTON_POSITIVE -> onYes?.run()
                DialogInterface.BUTTON_NEGATIVE -> onNo?.run()
            }
        }

        val builder = AlertDialog.Builder(parent)
        @Suppress("DEPRECATION")
        builder.setMessage(Html.fromHtml(message))
        if (title != null) {
            builder.setTitle(title)
        }
        if (btnYesText != null) {
            builder.setPositiveButton(btnYesText, dialogClickListener)
        }
        if (btnNoText != null) {
            builder.setNegativeButton(btnNoText, dialogClickListener)
        }
        val dialog = builder.create()
        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)
            ?.movementMethod = LinkMovementMethod.getInstance()
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
            null,
            message,
            parent.resources.getString(R.string.proceed),
            parent.resources.getString(R.string.cancel),
            onYes,
            onNo,
        )
    }

    @JvmStatic
    fun displayQuitConfirmationDialog(parent: Activity, onYes: Runnable?, onNo: Runnable?) {
        displayConfirmationDialog(
            parent,
            null,
            parent.resources.getString(R.string.applist_quit_confirmation),
            parent.resources.getString(R.string.yes),
            parent.resources.getString(R.string.no),
            onYes,
            onNo,
        )
    }

    @JvmStatic
    fun displayDeletePcConfirmationDialog(
        parent: Activity,
        computer: ComputerDetails,
        onYes: Runnable?,
        onNo: Runnable?,
    ) {
        displayConfirmationDialog(
            parent,
            computer.name,
            parent.resources.getString(R.string.delete_pc_msg),
            parent.resources.getString(R.string.yes),
            parent.resources.getString(R.string.no),
            onYes,
            onNo,
        )
    }

    @JvmStatic
    fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
    }
}
