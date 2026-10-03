package com.papi.nova.ui.panel

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** The common window must be loadable before Android 13's back-dispatcher types exist. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 30, 32])
class NovaPanelLegacyWindowTest {
    private lateinit var controller: ActivityController<ComponentActivity>

    @After fun destroy() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        idle()
    }

    @Test fun theLegacyWindowDeclaresNoModernPlatformBackTypes() {
        // ART rejected this class on the Shield because its overridden method returned an
        // unresolved OnBackInvokedDispatcher. A method-level API annotation cannot prevent that.
        val methods = NovaPanelWindow::class.java.declaredMethods
        assertFalse(methods.any { method ->
            (method.parameterTypes.toList() + method.returnType).any { it.name.startsWith("android.window.") }
        })
    }

    @Test fun openingAndClosingAHostPanelKeepsTheLegacyWindowLifecycle() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val surfaces = NovaSurfaces.of(controller.get())
        surfaces.open(
            NovaCommonPage.Menu(
                key = "host",
                title = "Living Room",
                items = listOf(NovaMenuItem.Action("wake", "Wake", onClick = {})),
            ),
        )
        idle()
        val window = ShadowDialog.getLatestDialog() as ComponentDialog
        assertTrue(window.isShowing)
        surfaces.panel.close()
        surfaces.onWindowIdle()
        idle()
        assertFalse(window.isShowing)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
}
