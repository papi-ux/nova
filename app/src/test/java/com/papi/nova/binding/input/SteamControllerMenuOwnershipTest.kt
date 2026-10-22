package com.papi.nova.binding.input

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.binding.input.driver.SteamController
import com.papi.nova.binding.input.driver.SteamControllerProtocol
import com.papi.nova.binding.input.driver.SteamControllerTransport
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.ui.GameGestures
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaSurfaces
import java.util.concurrent.ScheduledExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowViewRootImpl
import org.robolectric.util.ReflectionHelpers

/** Drive actual native reports through ControllerHandler while a Nova panel owns a real window. */
@Config(sdk = [30], qualifiers = "w800dp-h480dp-land-xhdpi", shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class SteamControllerMenuOwnershipTest {
    // The rule drives every composition made during the test, the panel window's included, on its
    // bounded clock: a panel composed outside one does not advance reliably under Robolectric.
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var connection: NvConnection
    private lateinit var handler: ControllerHandler
    private lateinit var pad: SteamController
    private lateinit var link: Link
    private var firstActions = 0
    private var secondActions = 0

    private class Link : SteamControllerTransport {
        lateinit var observer: SteamControllerTransport.Observer
        override fun start(observer: SteamControllerTransport.Observer): Boolean {
            this.observer = observer
            observer.onReady()
            return true
        }
        override fun writeFeature(report: ByteArray, completion: (Boolean) -> Unit) = completion(true)
        override fun writeRumble(report: ByteArray) = Unit
        override fun close() = Unit
        fun state(buttons: Int) = observer.onReport(ByteArray(46).also { report ->
            report[0] = 0x42
            for (i in 0..3) report[2 + i] = (buttons ushr (8 * i)).toByte()
        })
    }

    @Before fun setup() {
        val activity = rule.activity
        // This JVM has no NVIDIA accessory service. Draining the window looper must not
        // fabricate its onServiceConnected callback with a null binder.
        shadowOf(activity.application).declareComponentUnbindable(
            ComponentName("com.nvidia.blakepairing", "com.nvidia.blakepairing.AccessoryService"))
        connection = mock(NvConnection::class.java)
        rule.runOnUiThread {
            handler = ControllerHandler(activity, connection, mock(GameGestures::class.java),
                PreferenceConfiguration().apply { multiController = true })
            link = Link()
            pad = SteamController(91, handler, SteamControllerProtocol.WIRED_PID, link, "menu-wired",
                scheduler = mock(ScheduledExecutorService::class.java))
            assertTrue(pad.start())
            link.state(1)
            link.state(0)
        }
        rule.waitForIdle()
        clearInvocations(connection)
    }

    @After fun cleanup() {
        rule.runOnUiThread {
            if (::pad.isInitialized) pad.stop()
            if (::handler.isInitialized) handler.destroy()
            NovaSurfaces.existing(rule.activity)?.dispose()
        }
        rule.waitForIdle()
    }

    private fun flags(): List<Int> = mockingDetails(connection).invocations
        .filter { it.method.name == "sendControllerInput" }
        .map { (it.arguments[2] as Number).toInt() }

    /** Whether the menu row showing [text] holds Compose focus. */
    private fun focused(text: String): Boolean =
        rule.onNodeWithText(text).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    /** Native reports arrive on the main thread, as UsbDriverService delivers them. */
    private fun native(vararg buttons: Int) {
        rule.runOnUiThread { buttons.forEach(link::state) }
        rule.waitForIdle()
    }

    /** The production Command Center menu in its own NovaSurfaces window, focused as a device focuses it. */
    private fun panel(): ComponentDialog {
        rule.runOnUiThread {
            NovaSurfaces.of(rule.activity).open(NovaCommonPage.Menu("test-menu", "Command Center", listOf(
                NovaMenuItem.Action("first", "First", closesPanel = false, onClick = { firstActions++ }),
                NovaMenuItem.Action("second", "Second", closesPanel = false, onClick = { secondActions++ }),
            )))
        }
        rule.waitForIdle()
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        val decor = dialog.window!!.decorView
        // A JVM dialog has no window manager to give it focus. Deliver that actual platform
        // transition, rather than bypassing the production focused-window guard.
        val root = ReflectionHelpers.callInstanceMethod<Any>(decor, "getViewRootImpl")
        assertNotNull("the panel window is attached before focus is granted", root)
        rule.runOnUiThread { Shadow.extract<ShadowViewRootImpl>(root).callWindowFocusChanged(true) }
        rule.waitForIdle()
        assertTrue("the panel window is attached and focused", decor.hasWindowFocus())
        return dialog
    }

    @Test fun panelOwnedFaceButtonsNeverReachTheHostGame() {
        panel()
        native(8, 0) // physical Y
        assertTrue("UI-owned Y must not reach the host", flags().all { it == 0 })
    }

    @Test fun nativeDpadAndAcceptNavigateTheActualPanelWindow() {
        panel()
        assertTrue("the menu opens on its first row", focused("First"))
        native(0x400, 0) // down
        assertTrue("the native D-pad must move local focus", focused("Second"))
        assertEquals("moving focus activates nothing", 0, firstActions + secondActions)
        native(1)
        assertEquals("A acts on release", 0, secondActions)
        native(0)
        assertEquals(1, secondActions)
        assertEquals("only the focused row acts", 0, firstActions)
        rule.waitForIdle()
        assertEquals("one release activates once", 1, secondActions)
        assertTrue(flags().all { it == 0 })
    }

    @Test fun closingThePanelDoesNotSendItsHeldAcceptButtonToTheGame() {
        panel()
        native(1)
        rule.runOnUiThread {
            NovaSurfaces.of(rule.activity).panel.close()
            NovaSurfaces.of(rule.activity).onWindowIdle()
        }
        rule.waitForIdle()
        clearInvocations(connection)
        native(1)
        assertTrue("a press begun in UI must not leak across dismissal", flags().all { it == 0 })
        native(0, 1)
        assertTrue("a fresh press must return to the game", flags().any { it and ControllerPacket.A_FLAG != 0 })
    }

    @Test fun nativeTrackpadMouseCannotMoveOrClickTheGameBehindThePanel() {
        panel()
        rule.runOnUiThread { handler.reportControllerMouse(91, 12, 8, ControllerPacket.A_FLAG, 120) }
        rule.waitForIdle()
        assertTrue("native pointer input belongs to UI while its panel is open",
            mockingDetails(connection).invocations.none {
                it.method.name in setOf("sendMouseMove", "sendMouseButtonDown", "sendMouseHighResScroll")
            })
    }
}
