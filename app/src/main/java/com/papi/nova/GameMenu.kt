package com.papi.nova

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.papi.nova.binding.input.GameInputDevice
import com.papi.nova.binding.input.KeyboardTranslator
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.utils.KeyConfigHelper
import com.papi.nova.ui.NovaSheetChrome
import com.papi.nova.utils.KeyMapper
import java.util.concurrent.atomic.AtomicInteger

class GameMenu @JvmOverloads constructor(
    private val game: Game,
    private val dialogScreenContext: Context,
    private val dialogWindowType: Int? = null,
    private val dialogWindowTokenProvider: (() -> IBinder?)? = null,
) : Game.GameMenuCallbacks {
    constructor(game: Game) : this(game, game)

    class MenuOption(
        val label: String?,
        val withGameFocus: Boolean,
        val runnable: Runnable?,
    ) {
        constructor(label: String?, runnable: Runnable?) : this(label, false, runnable)
    }

    private var currentSheet: BottomSheetDialog? = null

    private fun applyDialogWindowType(dialog: Dialog) {
        val window = dialog.window ?: return
        dialogWindowType?.let { windowType ->
            window.setType(windowType)
        }
        dialogWindowTokenProvider?.invoke()?.let { token ->
            window.attributes.token = token
        }
    }

    private fun getString(id: Int): String = game.resources.getString(id)

    private fun sendKeys(keys: ShortArray) {
        game.sendKeys(keys)
    }

    private fun runWithGameFocus(runnable: Runnable) {
        if (game.isFinishing) {
            return
        }

        if (!game.hasWindowFocus() && dialogScreenContext is Game) {
            Handler(Looper.getMainLooper()).postDelayed(
                { runWithGameFocus(runnable) },
                TEST_GAME_FOCUS_DELAY,
            )
            return
        }

        runnable.run()
    }

    private fun run(option: MenuOption) {
        val runnable = option.runnable ?: return

        if (option.withGameFocus) {
            runWithGameFocus(runnable)
        } else {
            runnable.run()
        }
    }

    private fun showMenuDialog(title: String, options: Array<MenuOption>) {
        val themeResId = game.applicationInfo.theme
        val themedContext = ContextThemeWrapper(dialogScreenContext, themeResId)
        val sheet = BottomSheetDialog(themedContext)
        val scrollView = ScrollView(themedContext)
        val container = NovaSheetChrome.createSheetContainer(themedContext)

        val titleView = TextView(themedContext).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            setPadding(0, 0, 0, dp(themedContext, 12))
            NovaSheetChrome.styleSheetTitle(this)
        }
        container.addView(
            titleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        var firstActionRow: TextView? = null
        options.forEach { option ->
            val label = option.label ?: return@forEach
            val row = TextView(themedContext).apply {
                text = label
                contentDescription = label
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(themedContext, GAME_MENU_ROW_HEIGHT_DP)
                setPadding(dp(themedContext, 16), 0, dp(themedContext, 16), 0)
                NovaSheetChrome.styleSheetAction(
                    this,
                    destructive = label == getString(R.string.game_menu_quit_session)
                )
                setOnClickListener {
                    hideMenu()
                    run(option)
                }
            }
            if (firstActionRow == null) {
                firstActionRow = row
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(themedContext, GAME_MENU_ROW_HEIGHT_DP)
                ).apply {
                    topMargin = dp(themedContext, 6)
                }
            )
        }

        scrollView.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        currentSheet?.dismiss()
        currentSheet = sheet
        sheet.setContentView(scrollView)
        sheet.setOnCancelListener {
            if (currentSheet == sheet) {
                currentSheet = null
            }
        }
        sheet.setOnDismissListener {
            if (currentSheet == sheet) {
                currentSheet = null
            }
        }
        applyDialogWindowType(sheet)
        sheet.show()
        NovaSheetChrome.applyBottomSheetChrome(
            sheet,
            scrollView,
            minLandscapeWidthDp = 520,
            maxLandscapeWidthDp = 820
        )
        firstActionRow?.let { row -> row.post { row.requestFocus() } }
    }
    private fun showSpecialKeysMenu() {
        val options = ArrayList<MenuOption>()

        if (!PreferenceConfiguration.readPreferences(game).disableDefaultExtraKeys) {
            options.add(MenuOption(getString(R.string.game_menu_send_keys_esc), Runnable {
                sendKeys(shortArrayOf(KeyboardTranslator.VK_ESCAPE.toShort()))
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_f11), Runnable {
                sendKeys(shortArrayOf(KeyboardTranslator.VK_F11.toShort()))
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_insert), Runnable {
                sendKeys(shortArrayOf(KeyboardTranslator.VK_INSERT.toShort()))
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_alt_f4), Runnable {
                sendKeys(shortArrayOf(KeyboardTranslator.VK_LMENU.toShort(), KeyboardTranslator.VK_F4.toShort()))
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_alt_enter), Runnable {
                sendKeys(shortArrayOf(KeyboardTranslator.VK_LMENU.toShort(), KeyboardTranslator.VK_RETURN.toShort()))
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_ctrl_v), Runnable {
                sendKeys(shortArrayOf(KeyboardTranslator.VK_LCONTROL.toShort(), KeyboardTranslator.VK_V.toShort()))
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_win), Runnable {
                sendKeys(shortArrayOf(KeyboardTranslator.VK_LWIN.toShort()))
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_win_d), Runnable {
                sendKeys(shortArrayOf(KeyboardTranslator.VK_LWIN.toShort(), KeyboardTranslator.VK_D.toShort()))
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_win_g), Runnable {
                sendKeys(shortArrayOf(KeyboardTranslator.VK_LWIN.toShort(), KeyboardTranslator.VK_G.toShort()))
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_ctrl_alt_tab), Runnable {
                sendKeys(
                    shortArrayOf(
                        KeyboardTranslator.VK_LCONTROL.toShort(),
                        KeyboardTranslator.VK_LMENU.toShort(),
                        KeyboardTranslator.VK_TAB.toShort(),
                    ),
                )
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_shift_tab), Runnable {
                sendKeys(shortArrayOf(KeyboardTranslator.VK_LSHIFT.toShort(), KeyboardTranslator.VK_TAB.toShort()))
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_win_shift_left), Runnable {
                sendKeys(
                    shortArrayOf(
                        KeyboardTranslator.VK_LWIN.toShort(),
                        KeyboardTranslator.VK_LSHIFT.toShort(),
                        KeyboardTranslator.VK_LEFT.toShort(),
                    ),
                )
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_ctrl_alt_shift_f1), Runnable {
                sendKeys(
                    shortArrayOf(
                        KeyboardTranslator.VK_LCONTROL.toShort(),
                        KeyboardTranslator.VK_LMENU.toShort(),
                        KeyboardTranslator.VK_LSHIFT.toShort(),
                        KeyboardTranslator.VK_F1.toShort(),
                    ),
                )
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_ctrl_alt_shift_f12), Runnable {
                sendKeys(
                    shortArrayOf(
                        KeyboardTranslator.VK_LCONTROL.toShort(),
                        KeyboardTranslator.VK_LMENU.toShort(),
                        KeyboardTranslator.VK_LSHIFT.toShort(),
                        KeyboardTranslator.VK_F12.toShort(),
                    ),
                )
            }))
            options.add(MenuOption(getString(R.string.game_menu_send_keys_alt_b), Runnable {
                sendKeys(
                    shortArrayOf(
                        KeyboardTranslator.VK_LWIN.toShort(),
                        KeyboardTranslator.VK_LMENU.toShort(),
                        KeyboardTranslator.VK_B.toShort(),
                    ),
                )
            }))
        }

        val preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE)
        val value = preferences.getString(KEY_NAME, "")

        if (!value.isNullOrEmpty()) {
            try {
                val shortcutFile = KeyConfigHelper.parseShortcutFile(value)
                if (shortcutFile.data.isNotEmpty()) {
                    for (shortcut in shortcutFile.data) {
                        val keys = shortcut.keys
                        val keyCodes = ShortArray(keys.size)

                        for (i in keys.indices) {
                            val code = keys[i]
                            val keycode = when {
                                code.startsWith("0x") -> Integer.parseInt(code.substring(2), 16)
                                code.startsWith("VK_") -> {
                                    val field = KeyMapper::class.java.getDeclaredField(code)
                                    field.getInt(null)
                                }
                                else -> throw IllegalArgumentException("Unknown key code: $code")
                            }
                            keyCodes[i] = keycode.toShort()
                        }

                        options.add(MenuOption(shortcut.name, Runnable { sendKeys(keyCodes) }))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(game, getString(R.string.wrong_import_format), Toast.LENGTH_SHORT).show()
            }
        }

        options.add(MenuOption(getString(R.string.game_menu_cancel), null))
        showMenuDialog(getString(R.string.game_menu_send_keys), options.toTypedArray())
    }

    private fun showAdvancedMenu(device: GameInputDevice?) {
        val options = ArrayList<MenuOption>()
        if (game.allowChangeMouseMode) {
            options.add(MenuOption(getString(R.string.game_menu_select_mouse_mode), true, Runnable {
                game.selectMouseMode(dialogScreenContext, dialogWindowType, dialogWindowTokenProvider?.invoke())
            }))
        }

        options.add(MenuOption(getString(R.string.game_menu_toggle_hud), true, Runnable { game.toggleHUD() }))
        options.add(MenuOption(getString(R.string.game_menu_toggle_floating_button), true, Runnable {
            game.toggleFloatingButtonVisibility()
        }))
        options.add(MenuOption(getString(R.string.game_menu_toggle_keyboard_model), true, Runnable {
            game.toggleKeyboardController()
        }))
        if (!game.isOnExternalDisplay) {
            options.add(MenuOption(getString(R.string.game_menu_toggle_virtual_model), true, Runnable {
                game.toggleVirtualController()
            }))
        }
        options.add(MenuOption(getString(R.string.game_menu_toggle_virtual_keyboard_model), true, Runnable {
            game.toggleFullKeyboard()
        }))
        options.add(MenuOption(getString(R.string.game_menu_task_manager), true, Runnable {
            sendKeys(
                shortArrayOf(
                    KeyboardTranslator.VK_LCONTROL.toShort(),
                    KeyboardTranslator.VK_LSHIFT.toShort(),
                    KeyboardTranslator.VK_ESCAPE.toShort(),
                ),
            )
        }))

        options.add(MenuOption(getString(R.string.game_menu_send_keys), Runnable {
            hideMenu()
            showSpecialKeysMenu()
        }))

        options.add(MenuOption(getString(R.string.game_menu_switch_touch_sensitivity_model), true, Runnable {
            game.switchTouchSensitivity()
        }))
        if (device != null) {
            options.addAll(device.getGameMenuOptions())
        }
        options.add(MenuOption(getString(R.string.game_menu_cancel), null))
        showMenuDialog(getString(R.string.game_menu_advanced), options.toTypedArray())
    }

    private fun showServerCmd(serverCmds: ArrayList<String>) {
        val options = ArrayList<MenuOption>()
        val index = AtomicInteger(0)
        for (command in serverCmds) {
            val commandIndex = index.getAndIncrement()
            options.add(MenuOption("> $command", true, Runnable {
                game.sendExecServerCmd(commandIndex)
            }))
        }

        options.add(MenuOption(getString(R.string.game_menu_cancel), null))
        showMenuDialog(getString(R.string.game_menu_server_cmd), options.toTypedArray())
    }

    override fun showMenu(device: GameInputDevice?) {
        val options = ArrayList<MenuOption>()

        options.add(MenuOption(getString(R.string.game_menu_disconnect), Runnable { game.disconnect() }))
        options.add(MenuOption(getString(R.string.game_menu_quit_session), Runnable { game.quit() }))
        options.add(MenuOption(getString(R.string.game_menu_upload_clipboard), true, Runnable {
            game.sendClipboard(true)
        }))
        options.add(MenuOption(getString(R.string.game_menu_fetch_clipboard), true, Runnable {
            game.getClipboard(0)
        }))
        options.add(MenuOption(getString(R.string.game_menu_server_cmd), true, Runnable {
            val serverCmds = game.serverCmds
            if (serverCmds.isEmpty()) {
                val themeResId = game.applicationInfo.theme
                val themedContext = ContextThemeWrapper(dialogScreenContext, themeResId)
                val serverCommandDialog = AlertDialog.Builder(themedContext)
                    .setTitle(R.string.game_dialog_title_server_cmd_empty)
                    .setMessage(R.string.game_dialog_message_server_cmd_empty)
                    .create()
                applyDialogWindowType(serverCommandDialog)
                NovaSheetChrome.applyAlertDialogChrome(serverCommandDialog)
                serverCommandDialog.show()
            } else {
                hideMenu()
                showServerCmd(serverCmds)
            }
        }))
        options.add(MenuOption(getString(R.string.game_menu_toggle_keyboard), true, Runnable {
            game.toggleKeyboard()
        }))
        options.add(MenuOption(
            getString(
                if (game.isZoomModeEnabled) {
                    R.string.game_menu_disable_zoom_mode
                } else {
                    R.string.game_menu_enable_zoom_mode
                },
            ),
            true,
            Runnable { game.toggleZoomMode() },
        ))

        if (dialogScreenContext == game) {
            options.add(MenuOption(getString(R.string.game_menu_rotate_screen), true, Runnable {
                game.rotateScreen()
            }))
        }

        options.add(MenuOption(getString(R.string.game_menu_advanced), true, Runnable {
            showAdvancedMenu(device)
        }))
        options.add(MenuOption(getString(R.string.game_menu_cancel), null))
        showMenuDialog(getString(R.string.quick_menu_title), options.toTypedArray())
    }

    override fun hideMenu() {
        currentSheet?.dismiss()
        currentSheet = null
    }

    override fun isMenuOpen(): Boolean {
        return currentSheet?.isShowing == true
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        const val KEY_UP_DELAY: Long = 25
        private const val TEST_GAME_FOCUS_DELAY: Long = 10
        private const val GAME_MENU_ROW_HEIGHT_DP: Int = 52
        const val PREF_NAME: String = "specialPrefs"
        const val KEY_NAME: String = "special_key"
    }
}
