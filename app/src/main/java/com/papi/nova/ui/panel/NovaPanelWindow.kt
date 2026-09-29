package com.papi.nova.ui.panel

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.annotation.RequiresApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import com.papi.nova.Game
import com.papi.nova.R
import com.papi.nova.ui.NovaDialogWindows
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.utils.ExternalDisplayControlHost

/** Where a [NovaPanelWindow] opens, which decides its window setup and what it restores on close. */
sealed interface NovaWindowPlacement {
    /** Over any ComponentActivity screen, with the backdrop blur. */
    data class Screen(val activity: ComponentActivity) : NovaWindowPlacement

    /** Over the stream; closing hands controller input back to the stream. */
    data class Stream(val game: Game) : NovaWindowPlacement

    /** On a companion display; [onClosed] restores the command deck's focus. */
    data class Companion(val host: ExternalDisplayControlHost, val onClosed: () -> Unit) : NovaWindowPlacement
}

internal val NovaWindowPlacement.context: Context
    get() = when (this) {
        is NovaWindowPlacement.Screen -> activity
        is NovaWindowPlacement.Stream -> game
        is NovaWindowPlacement.Companion -> host.companionDialogContext
    }

/** The view whose window the panel opens over. */
internal val NovaWindowPlacement.hostView: View
    get() = when (this) {
        is NovaWindowPlacement.Screen -> activity.window.decorView
        is NovaWindowPlacement.Stream -> game.window.decorView
        is NovaWindowPlacement.Companion -> host.hostWindow.decorView
    }

internal val NovaWindowPlacement.scrim: NovaScrim
    get() = if (this is NovaWindowPlacement.Screen) NovaScrim.Screen else NovaScrim.Stream

/** Whether a window may be shown now; a finishing activity or a hidden deck has no room for one. */
internal val NovaWindowPlacement.canShow: Boolean
    get() = when (this) {
        is NovaWindowPlacement.Screen -> !activity.isFinishing && !activity.isDestroyed
        is NovaWindowPlacement.Stream -> !game.isFinishing && !game.isDestroyed
        is NovaWindowPlacement.Companion -> host.isHostShowing()
    }

/**
 * The one window Nova opens for its own UI: panel plus state pages, over a screen, the stream or
 * a companion display.
 *
 * The window is transparent, undimmed and full size ([R.style.NovaPanelWindowTheme] keeps it from
 * floating), drawn behind the bars and into a short-edge cutout, resized by the keyboard. Its keys
 * go through [NovaKeyGate], its platform Back through [NovaBackStartGate], and its touches feed
 * [NovaSplitConfirmRegistry] first. [ComponentDialog] supplies the lifecycle, saved-state and back
 * dispatcher owners for any context, including the stream and a companion display.
 *
 * It is not cancelable: only [NovaSurfaces] closes it, through [closeNow]. A cancelable dialog
 * dismisses itself on a Back that no handler takes, which happens during a panel's exit motion and
 * before a Busy page shows, and that would skip handing input back to the stream or the deck.
 */
internal class NovaPanelWindow(
    private val placement: NovaWindowPlacement,
    private val surfaces: NovaSurfaces,
) : ComponentDialog(placement.context, R.style.NovaPanelWindowTheme) {
    private val keyGate = NovaKeyGate()
    private val backStartGate = NovaBackStartGate()
    private var startGatedBack: Any? = null

    init {
        setCancelable(false)
    }

    /**
     * The window's back dispatcher behind [backStartGate]. ComponentDialog hands it to this window's
     * OnBackPressedDispatcher in onCreate, so on API 34 and later a Back held while this window
     * appears cannot close it on release.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun getOnBackInvokedDispatcher(): OnBackInvokedDispatcher =
        startGatedBack as? NovaStartGatedBackDispatcher
            ?: NovaStartGatedBackDispatcher(super.getOnBackInvokedDispatcher(), backStartGate).also { startGatedBack = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        val content = ComposeView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setContent {
                NovaComposeTheme {
                    CompositionLocalProvider(
                        LocalNovaHostView provides placement.hostView,
                        LocalNovaPrepareKeyboard provides prepareKeyboard(),
                    ) {
                        val states by surfaces.states.collectAsState()
                        NovaSurfacesLayer(
                            panel = surfaces.panel,
                            states = states,
                            scrim = placement.scrim,
                            pageContent = surfaces.pageContent,
                            onIdle = surfaces::onWindowIdle,
                            hints = surfaces.pageHints,
                            onShoulder = surfaces.pageShoulder,
                            isPosted = { key -> surfaces.states.value.any { it.key == key } },
                            // A press that began on one surface, the panel or a state page, never
                            // finishes on the other.
                            onActiveSurfaceChange = { forgetPresses() },
                        )
                    }
                }
            }
        }
        setContentView(content)
        val window = window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.setDimAmount(0f)
        // The frame animates itself; a window animation on top would play twice.
        window.setWindowAnimations(0)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.setPadding(0, 0, 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (placement !is NovaWindowPlacement.Companion) NovaDialogWindows.adopt(placement.context, window)
        prepareControllerWindow(window, content)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        keyGate.dispatch(event, onBack = onBackPressedDispatcher::onBackPressed, deliver = { super.dispatchKeyEvent(it) })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        window?.decorView?.let { NovaSplitConfirmRegistry.onTouch(it, ev) }
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) forgetPresses()
    }

    /** Forgets A, B and Back presses in progress, whose releases then do nothing. */
    private fun forgetPresses() {
        keyGate.reset()
        backStartGate.reset()
    }

    /** Shows the window, reading a companion display's window token at show time. */
    fun showNow() {
        if (placement is NovaWindowPlacement.Companion) {
            window?.let { window ->
                window.setType(placement.host.companionDialogWindowType)
                window.attributes = window.attributes.apply { token = placement.host.companionDialogWindowToken() }
            }
            placement.host.prepareForCommandDeckFocus()
        }
        show()
    }

    /**
     * Gives up input first (some TV window managers otherwise keep a dismissed full-screen dialog as
     * the input target), dismisses, then restores what the placement needs.
     */
    fun closeNow() {
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        if (isShowing) dismiss()
        restorePlacement()
    }

    /** Hands back what the window took: stream input over the stream, deck focus on a companion display. */
    fun restorePlacement() {
        when (placement) {
            is NovaWindowPlacement.Stream -> placement.game.restoreStreamInputAfterModalDismissal()
            is NovaWindowPlacement.Companion -> placement.onClosed()
            is NovaWindowPlacement.Screen -> Unit
        }
    }

    private fun prepareKeyboard(): () -> Unit = when (placement) {
        is NovaWindowPlacement.Companion -> placement.host::prepareForSoftKeyboard
        else -> NoKeyboardPreparation
    }
}

private val NoKeyboardPreparation: () -> Unit = {}
