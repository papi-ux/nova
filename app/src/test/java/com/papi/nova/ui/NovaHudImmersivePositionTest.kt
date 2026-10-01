package com.papi.nova.ui

import android.graphics.Insets
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.preference.PreferenceManager
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercise the real HUD's placement, not a second implementation of its bounds arithmetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHudImmersivePositionTest {
    private val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
    private val activity = controller.get()
    private val content = activity.findViewById<ViewGroup>(android.R.id.content).apply {
        setViewTreeLifecycleOwner(activity)
        setViewTreeViewModelStoreOwner(activity)
        setViewTreeSavedStateRegistryOwner(activity)
    }
    private val hud = NovaStreamHud(activity)

    private inner class InsetRoot(var windowInsets: WindowInsets) : FrameLayout(activity) {
        var windowX = 0
        var windowY = 0
        override fun getRootWindowInsets() = windowInsets
        override fun getLocationInWindow(outLocation: IntArray) {
            outLocation[0] = windowX
            outLocation[1] = windowY
        }
    }

    @After fun close() { hud.dismiss(); controller.pause().stop().destroy() }

    private fun insets(barsVisible: Boolean, cutout: Insets = Insets.NONE): WindowInsets =
        WindowInsets.Builder()
            .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), Insets.of(0, 30, 0, 0))
            .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.of(40, 0, 0, 25))
            .setInsets(WindowInsets.Type.statusBars(), if (barsVisible) Insets.of(0, 30, 0, 0) else Insets.NONE)
            .setInsets(WindowInsets.Type.navigationBars(), if (barsVisible) Insets.of(40, 0, 0, 25) else Insets.NONE)
            .setVisible(WindowInsets.Type.systemBars(), barsVisible)
            .setInsetsIgnoringVisibility(WindowInsets.Type.displayCutout(), cutout)
            .setVisible(WindowInsets.Type.displayCutout(), false)
            .build()

    private fun start(windowInsets: WindowInsets): Pair<View, InsetRoot> {
        PreferenceManager.getDefaultSharedPreferences(activity).edit().clear().commit()
        hud.show()
        val view = NovaStreamHud::class.java.getDeclaredField("hudView")
            .apply { isAccessible = true }.get(hud) as View
        activity.window.decorView.layout(0, 0, 1000, 600)
        content.layout(0, 0, 1000, 600)
        view.layout(0, 0, 100, 100)
        val root = InsetRoot(windowInsets).apply { layout(0, 0, 1000, 600) }
        return view to root
    }

    private fun restore(view: View, root: ViewGroup, corner: NovaHudCorner) {
        hud.setPosition(corner)
        NovaStreamHud::class.java.getDeclaredMethod("restoreHudPosition", View::class.java,
            ViewGroup::class.java, Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(hud, view, root, 12f)
    }

    private fun assertPosition(view: View, x: Float, y: Float) {
        assertEquals("HUD x", x, view.x, 0.01f)
        assertEquals("HUD y", y, view.y, 0.01f)
    }

    @Test fun hiddenBarsDoNotDisplaceAnyCorner() {
        val (view, root) = start(insets(barsVisible = false))
        for ((corner, expected) in listOf(
            NovaHudCorner.TOP_LEFT to (12f to 12f),
            NovaHudCorner.TOP_RIGHT to (888f to 12f),
            NovaHudCorner.BOTTOM_LEFT to (12f to 488f),
            NovaHudCorner.BOTTOM_RIGHT to (888f to 488f),
        )) {
            restore(view, root, corner)
            assertPosition(view, expected.first, expected.second)
        }
        val params = view.layoutParams as FrameLayout.LayoutParams
        assertEquals(12, params.leftMargin)
        assertEquals(12, params.topMargin)
        assertEquals(12, params.bottomMargin)
    }

    @Test fun visibleBarsStillProtectTheHudAtBothEdges() {
        val (view, root) = start(insets(barsVisible = true))
        restore(view, root, NovaHudCorner.TOP_LEFT)
        assertPosition(view, 52f, 42f)
        restore(view, root, NovaHudCorner.BOTTOM_RIGHT)
        assertPosition(view, 888f, 463f)
    }

    @Test fun cutoutProtectionSurvivesHiddenBarsOnEveryEdge() {
        val (view, root) = start(insets(barsVisible = false, cutout = Insets.of(80, 45, 70, 35)))
        restore(view, root, NovaHudCorner.TOP_LEFT)
        assertPosition(view, 92f, 57f)
        restore(view, root, NovaHudCorner.BOTTOM_RIGHT)
        assertPosition(view, 818f, 453f)
    }

    @Test fun contentAlreadyInsideVisibleBarsDoesNotCountThemTwice() {
        val (view, root) = start(insets(barsVisible = true))
        root.windowX = 40
        root.windowY = 30
        root.layout(0, 0, 960, 545)
        restore(view, root, NovaHudCorner.TOP_LEFT)
        assertPosition(view, 12f, 12f)
        restore(view, root, NovaHudCorner.BOTTOM_RIGHT)
        assertPosition(view, 848f, 433f)
    }

    @Test fun visibilityChangeKeepsTheChosenCornerAndResizesTheSafeArea() {
        val (view, root) = start(insets(barsVisible = true))
        restore(view, root, NovaHudCorner.BOTTOM_RIGHT)
        assertPosition(view, 888f, 463f)
        root.windowInsets = insets(barsVisible = false)
        restore(view, root, NovaHudCorner.BOTTOM_RIGHT)
        assertPosition(view, 888f, 488f)
        assertEquals(NovaHudCorner.BOTTOM_RIGHT, hud.positionCorner)
        // Dismissal still cancels the freshness timer after placement work.
        hud.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
