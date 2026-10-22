package com.papi.nova.binding.input

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.InputEvent
import android.view.KeyEvent
import com.papi.nova.binding.input.driver.AbstractController
import com.papi.nova.binding.input.driver.SteamController
import com.papi.nova.binding.input.driver.SteamControllerOwnership
import com.papi.nova.binding.input.driver.UsbDriverListener
import com.papi.nova.binding.input.driver.UsbDriverService
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.ui.panel.NovaSurfaces

/**
 * A Steam Controller's own buttons for the Nova library, while the library is resumed.
 *
 * Outside a stream Android sees the controller as a keyboard and mouse, so the library's gamepad
 * keys (Y's layout, L1 and R1's panels, X, Start) never reached it. While resumed, the library
 * takes the controller's USB channels, wired or Puck, through the stream's own driver service.
 * Each channel's buttons and left stick become the keys an Android gamepad sends: to the open
 * library panel when there is one, otherwise to the library itself.
 *
 * It opens only what Android already allows, so it never asks for USB access, starts Bluetooth
 * or claims an Xbox or Pro Controller. Access is asked for in stream setup; until then the library
 * keeps the controller's keyboard and mouse. Pausing gives the channels back. A game that took the
 * service meanwhile keeps it, since a stale pause cannot stop another owner, and a connection that
 * lands after the pause starts nothing.
 */
internal class NativeControllerLibraryInput(
    private val activity: Activity,
    private val panelOwner: () -> Any? = { NovaSurfaces.existing(activity)?.controllerInputOwner },
    private val dispatchToPanel: (KeyEvent) -> Boolean = { NovaSurfaces.existing(activity)?.dispatchControllerKey(it) == true },
    private val dispatchToScreen: (KeyEvent) -> Boolean = { dispatchToWindow(activity, it) },
    private val newKeys: () -> Keys = { UiKeys(NativeControllerUiKeys()) },
) : UsbDriverListener {
    /** One channel's keys: [NativeControllerUiKeys], or a test's stand-in. */
    internal interface Keys {
        fun update(target: Any, buttonFlags: Int, leftStickX: Float, leftStickY: Float, dispatch: (KeyEvent) -> Unit)
        fun suppress(buttonFlags: Int, leftStickX: Float, leftStickY: Float)
        fun cancel()
    }

    private class UiKeys(private val keys: NativeControllerUiKeys) : Keys {
        override fun update(target: Any, buttonFlags: Int, leftStickX: Float, leftStickY: Float, dispatch: (KeyEvent) -> Unit) {
            keys.update(target, buttonFlags, leftStickX, leftStickY, dispatch)
        }

        override fun suppress(buttonFlags: Int, leftStickX: Float, leftStickY: Float) {
            keys.suppress(buttonFlags, leftStickX, leftStickY)
        }

        override fun cancel() {
            keys.cancel()
        }
    }

    /** [fresh] until the channel's first report, whose held buttons began somewhere else. */
    private class Channel(val keys: Keys) {
        var fresh = true
    }

    // Main thread only, as the service delivers every callback there.
    private var resumed = false
    private var bound = false
    private var config: PreferenceConfiguration? = null
    private var driver: UsbDriverService.UsbDriverBinder? = null
    private val channels = HashMap<Int, Channel>()

    private var bindingGeneration = 0L
    internal var connection: ServiceConnection = newConnection(bindingGeneration)
        private set

    private fun newConnection(generation: Long) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (!resumed || generation != bindingGeneration) return
            val binder = service as? UsbDriverService.UsbDriverBinder ?: return
            driver = binder
            val owned = config
            // A connection that lands after the pause starts nothing.
            if (resumed && owned != null) {
                binder.startOwned(this@NativeControllerLibraryInput, null, owned,
                    promptForPermission = false, steamControllersOnly = true)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (!resumed || generation != bindingGeneration) return
            driver = null
            cancelChannels()
        }
    }

    fun resume() {
        if (resumed) return
        resumed = true
        connection = newConnection(++bindingGeneration)
        // The library reads only its USB switch from the same profile overlay as a stream.
        // Reading the entire stream configuration also initializes native audio/video code.
        val config = libraryConfig(PreferenceConfiguration().apply {
            usbDriver = PreferenceConfiguration.isUsbDriverEnabled(
                ProfilesManager.getInstance().getOverlayingSharedPreferences(activity))
        })
        this.config = config
        if (!config.usbDriver || bound) return
        bound = activity.bindService(Intent(activity, UsbDriverService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun pause() {
        resumed = false
        bindingGeneration++
        cancelChannels()
        // Only this library's own channels stop: a game that took the service since keeps it.
        driver?.stop(this)
        driver = null
        if (bound) {
            bound = false
            activity.unbindService(connection)
        }
    }

    private fun cancelChannels() {
        channels.values.forEach { it.keys.cancel() }
        channels.clear()
    }

    override fun deviceAdded(controller: AbstractController) {
        // The service opens only Steam Controllers here; anything else is not the library's.
        if (!resumed || controller !is SteamController) return
        channels.getOrPut(controller.getControllerId()) { Channel(newKeys()) }
    }

    override fun deviceRemoved(controller: AbstractController) {
        channels.remove(controller.getControllerId())?.keys?.cancel()
    }

    override fun resetControllerState(controllerId: Int) {
        val channel = channels[controllerId] ?: return
        channel.keys.cancel()
        channel.fresh = true
    }

    override fun reportControllerState(
        controllerId: Int,
        buttonFlags: Int,
        leftStickX: Float,
        leftStickY: Float,
        rightStickX: Float,
        rightStickY: Float,
        leftTrigger: Float,
        rightTrigger: Float,
    ) {
        if (!resumed) return
        val channel = channels[controllerId] ?: return
        if (channel.fresh) {
            // A button held as the library gets the channel, such as the one that left the
            // stream, acts only after its release.
            channel.fresh = false
            channel.keys.suppress(buttonFlags, leftStickX, leftStickY)
        }
        // When a panel comes or goes, the keys cancel their presses through the previous update's
        // dispatcher, so each press ends in the window where it began.
        val panel = panelOwner()
        channel.keys.update(panel ?: activity, buttonFlags, leftStickX, leftStickY) { event ->
            if (panel != null) dispatchToPanel(event) else dispatchToScreen(event)
        }
    }

    // The library has no pointer, touch or motion for a controller; its pads and gyro do nothing.
    override fun reportControllerMotion(controllerId: Int, motionType: Byte, motionX: Float, motionY: Float, motionZ: Float) = Unit

    companion object {
        /** The library's driver settings: the player's USB choice, without Bluetooth or a game's mappings. */
        fun libraryConfig(saved: PreferenceConfiguration): PreferenceConfiguration = PreferenceConfiguration().apply {
            usbDriver = saved.usbDriver
        }

        /**
         * Whether [event] came from the keyboard or mouse of a Steam Controller a native driver owns,
         * which only repeats the buttons the library already has. Other keyboards, mice and kernel
         * gamepads are never this.
         */
        fun isOwnedLizard(event: InputEvent): Boolean = SteamControllerOwnership.owns(event.device)

        private val DIRECTIONS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

        /**
         * Delivers [event] to [activity]'s own window as NovaSurfaces does to a panel's: a press only
         * while the window has focus, a cancelled release always. A directional press in touch mode
         * first takes the window out of it, as a hat press does for a screen, and is spent on that
         * when nothing held focus.
         */
        internal fun dispatchToWindow(activity: Activity, event: KeyEvent): Boolean {
            val decor = activity.window?.peekDecorView() ?: return false
            if (!event.isCanceled && !decor.hasWindowFocus()) return true
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in DIRECTIONS && decor.isInTouchMode) {
                val focused = decor.findFocus()
                (focused ?: decor).requestFocusFromTouch()
                if (focused == null) return true
            }
            return activity.dispatchKeyEvent(event)
        }
    }
}
