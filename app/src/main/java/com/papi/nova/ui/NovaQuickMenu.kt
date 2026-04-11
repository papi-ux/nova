package com.papi.nova.ui

import android.view.View
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.papi.nova.Game
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.binding.input.GameInputDevice
import com.papi.nova.binding.input.KeyboardTranslator

/**
 * Nova quick menu — in-stream controls as a landscape-friendly BottomSheet.
 *
 * Three-column layout: Overlays | Controls | Session
 *
 * Overlays:
 *   - Nova HUD (tap to toggle, cycles modes)
 *   - MangoHud (tap to toggle server-side overlay via Polaris API)
 *   - Perf Stats (legacy Moonlight overlay)
 *
 * Controls:
 *   - Mouse mode (Direct/Trackpad/etc.)
 *   - On-screen controller
 *   - Keyboard
 *
 * Session:
 *   - Paste clipboard
 *   - Rotate screen
 *   - Special keys (falls back to legacy menu)
 */
class NovaQuickMenu(private val game: Game) : Game.GameMenuCallbacks {

    private var dialog: BottomSheetDialog? = null
    private var mangoHudEnabled = false

    override fun showMenu(device: GameInputDevice?) {
        if (dialog?.isShowing == true) return

        val sheet = BottomSheetDialog(game, R.style.NovaBottomSheet)
        sheet.setContentView(R.layout.nova_quick_menu)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        // Cap height to 85% of screen so the sheet doesn't cover the game entirely on short screens
        sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
            val displayHeight = game.resources.displayMetrics.heightPixels
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                height = (displayHeight * 0.85).toInt()
            }
        }

        // ── Quick Keys ──
        fun keys(vararg vk: Int) = ShortArray(vk.size) { vk[it].toShort() }
        fun hapticClick(view: View, action: () -> Unit) {
            view.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                action()
            }
        }

        sheet.findViewById<View>(R.id.qk_esc)?.let { hapticClick(it) {
            dismiss(); sendKeysWithFocus(keys(KeyboardTranslator.VK_ESCAPE))
        }}
        sheet.findViewById<View>(R.id.qk_alt_enter)?.let { hapticClick(it) {
            dismiss(); sendKeysWithFocus(keys(KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_RETURN))
        }}
        sheet.findViewById<View>(R.id.qk_alt_f4)?.let { hapticClick(it) {
            dismiss(); sendKeysWithFocus(keys(KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_F4))
        }}
        sheet.findViewById<View>(R.id.qk_f11)?.let { hapticClick(it) {
            dismiss(); sendKeysWithFocus(keys(KeyboardTranslator.VK_F11))
        }}
        sheet.findViewById<View>(R.id.qk_super)?.let { hapticClick(it) {
            dismiss(); sendKeysWithFocus(keys(KeyboardTranslator.VK_LWIN))
        }}
        sheet.findViewById<View>(R.id.qk_ctrl_v)?.let { hapticClick(it) {
            dismiss(); sendKeysWithFocus(keys(KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V))
        }}

        // ── Overlays Column ──
        // Only one client-side HUD at a time (Nova HUD vs Perf Stats).
        // MangoHud is server-side and can coexist with either.

        val hudIndicator = sheet.findViewById<View>(R.id.hud_indicator)
        val perfIndicator = sheet.findViewById<View>(R.id.perf_indicator)

        // Helper: get/set the Game's novaHud field so perf callback feeds stats
        fun getGameHud(): NovaStreamHud? = getNovaHud()
        fun setGameHud(hud: NovaStreamHud?) {
            try {
                val field = game.javaClass.getDeclaredField("novaHud")
                field.isAccessible = true
                field.set(game, hud)
            } catch (_: Exception) {}
        }

        // Helper: update both indicators to reflect current state
        fun refreshOverlayIndicators() {
            updateIndicator(hudIndicator, getGameHud()?.isShowing == true)
            updateIndicator(perfIndicator, game.prefConfig.enablePerfOverlay)
        }
        refreshOverlayIndicators()

        // Nova HUD toggle — disables Perf Stats if enabling
        sheet.findViewById<View>(R.id.toggle_hud)?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            try {
                val existingHud = getGameHud()
                if (existingHud != null && existingHud.isShowing) {
                    // Dismiss existing HUD
                    existingHud.dismiss()
                    setGameHud(null)
                } else {
                    // Disable legacy perf overlay first
                    if (game.prefConfig.enablePerfOverlay) game.toggleHUD()
                    // Create new HUD and register it on Game so onPerfUpdate feeds it
                    val hud = NovaStreamHud(game)
                    setGameHud(hud)
                    hud.show()
                }
            } catch (_: Exception) {}
            refreshOverlayIndicators()
        }

        // MangoHud (server-side — can coexist with client HUDs)
        val mangoIndicator = sheet.findViewById<View>(R.id.mangohud_indicator)
        val mangoHost = getServerAddress()
        val mangoGameUuid = getRunningGameUuid()
        val mangoRow = sheet.findViewById<View>(R.id.toggle_mangohud)

        if (mangoHost == null || mangoGameUuid == null) {
            mangoRow?.alpha = 0.4f
            mangoRow?.isClickable = false
        } else {
            updateIndicator(mangoIndicator, mangoHudEnabled)
            mangoRow?.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                mangoHudEnabled = !mangoHudEnabled
                updateIndicator(mangoIndicator, mangoHudEnabled)

                Thread {
                    try {
                        val client = PolarisApiClient(game.applicationContext, mangoHost, getHttpsPort())
                        client.setMangoHud(mangoGameUuid, mangoHudEnabled)
                    } catch (e: Exception) {
                        com.papi.nova.LimeLog.warning("MangoHud toggle failed: ${e.message}")
                    }
                }.start()
            }
        }

        // Perf Stats (legacy Moonlight overlay) — disables Nova HUD if enabling
        updateIndicator(perfIndicator, game.prefConfig.enablePerfOverlay)
        sheet.findViewById<View>(R.id.toggle_perf_stats)?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            // If enabling legacy overlay, dismiss Nova HUD first
            val existingHud = getGameHud()
            if (!game.prefConfig.enablePerfOverlay && existingHud?.isShowing == true) {
                existingHud.dismiss()
                setGameHud(null)
            }
            game.toggleHUD()
            refreshOverlayIndicators()
        }

        // ── Controls Column ──

        // Mouse Mode
        val mouseRow = sheet.findViewById<View>(R.id.toggle_mouse_mode)
        if (game.allowChangeMouseMode) {
            mouseRow?.setOnClickListener {
                dismiss()
                game.selectMouseMode(game)
            }
        } else {
            mouseRow?.visibility = View.GONE
        }

        // On-Screen Controller
        val oscIndicator = sheet.findViewById<View>(R.id.osc_indicator)
        updateIndicator(oscIndicator, game.prefConfig.onscreenController)
        sheet.findViewById<View>(R.id.toggle_osc)?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dismiss()
            game.toggleVirtualController()
        }

        // Keyboard
        val kbIndicator = sheet.findViewById<View>(R.id.keyboard_indicator)
        updateIndicator(kbIndicator, game.isKeyboardLayoutVisible() == true)
        sheet.findViewById<View>(R.id.toggle_keyboard)?.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dismiss()
            game.toggleKeyboard()
        }

        // ── Session Column ──

        sheet.findViewById<View>(R.id.action_paste_clipboard)?.setOnClickListener {
            dismiss()
            game.sendClipboard(true)
        }

        sheet.findViewById<View>(R.id.action_rotate_screen)?.apply {
            if (game.isOnExternalDisplay) {
                visibility = View.GONE
            } else {
                setOnClickListener {
                    dismiss()
                    game.rotateScreen()
                }
            }
        }

        sheet.findViewById<View>(R.id.action_more_keys)?.setOnClickListener {
            dismiss()
            val legacyMenu = com.papi.nova.GameMenu(game)
            legacyMenu.showMenu(device)
        }

        // ── Exit Actions ──

        sheet.findViewById<View>(R.id.action_disconnect)?.setOnClickListener {
            dismiss()
            game.disconnect()
        }
        sheet.findViewById<View>(R.id.action_quit)?.setOnClickListener {
            dismiss()
            game.quit()
        }

        dialog = sheet
        sheet.show()
    }

    override fun hideMenu() {
        dismiss()
    }

    override fun isMenuOpen(): Boolean {
        return dialog?.isShowing == true
    }

    private fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun updateIndicator(indicator: View?, active: Boolean) {
        indicator?.setBackgroundResource(
            if (active) R.drawable.nova_status_online else R.drawable.nova_status_offline
        )
    }

    private fun sendKeysWithFocus(keys: ShortArray) {
        game.window.decorView.postDelayed({
            if (!game.isFinishing) {
                game.sendKeys(keys)
            }
        }, KEY_UP_DELAY)
    }

    /** Get the NovaStreamHud instance from Game via reflection. */
    private fun getNovaHud(): NovaStreamHud? {
        return try {
            val field = game.javaClass.getDeclaredField("novaHud")
            field.isAccessible = true
            field.get(game) as? NovaStreamHud
        } catch (_: Exception) { null }
    }

    /** Get the currently running game UUID from Game. */
    private fun getRunningGameUuid(): String? {
        return try {
            // The game UUID is stored in the launch intent or can be derived
            game.intent?.getStringExtra("AppUUID")
                ?: game.intent?.getStringExtra("appuuid")
        } catch (_: Exception) { null }
    }

    /** Get the server address from the Game activity. */
    private fun getServerAddress(): String? {
        return try {
            game.intent?.getStringExtra("Host")
                ?: game.intent?.getStringExtra("host")
        } catch (_: Exception) { null }
    }

    /** Get the HTTPS port from the Game activity. */
    private fun getHttpsPort(): Int {
        return game.intent?.getIntExtra("HttpsPort", 47984) ?: 47984
    }

    companion object {
        private const val KEY_UP_DELAY = 25L
    }
}
