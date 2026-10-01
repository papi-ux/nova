package com.papi.nova.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.google.android.material.card.MaterialCardView
import com.papi.nova.R
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.utils.UiHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowGameManager::class])
class NovaCameraViewPolicyTest {
    private fun bars(hidden: Boolean, ime: Int, tv: Boolean = false, caption: Int = 0) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            NovaSystemBars.markManaged(activity)
            PreferenceManager.getDefaultSharedPreferences(activity).edit()
                .putBoolean(NovaSystemBars.KEY_HIDE_SYSTEM_BARS, hidden).commit()
            if (tv) shadowOf(activity.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
                .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
            val target = LinearLayout(activity)
            activity.setContentView(target)
            UiHelper.notifyNewRootView(activity, target, localizeCamera = true)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(12, 24, 14, 18))
                .setVisible(WindowInsetsCompat.Type.systemBars(), true)
                .setInsets(WindowInsetsCompat.Type.captionBar(), Insets.of(0, caption, 0, 0))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                .build()
            target.dispatchApplyWindowInsets(insets.toWindowInsets()!!)
            if (tv) {
                val scale = activity.resources.displayMetrics.density
                assertEquals((48 * scale + 0.5f).toInt(), target.paddingLeft)
                assertEquals((27 * scale + 0.5f).toInt(), target.paddingTop)
            } else {
                assertEquals(if (hidden) 0 else 12, target.paddingLeft)
                assertEquals(if (hidden) caption else maxOf(24, caption), target.paddingTop)
                assertEquals(if (hidden) ime else maxOf(18, ime), target.paddingBottom)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun visibleBarsRemainSafetyInsets() = bars(hidden = false, ime = 0)
    @Test fun hiddenTransientBarsDoNotMoveTheControls() = bars(hidden = true, ime = 0)
    @Test fun imeRemainsSafetyWhileBarsAreHidden() = bars(hidden = true, ime = 240)
    @Test fun hiddenBarsStillPreserveDesktopCaptionChrome() = bars(hidden = true, ime = 0, caption = 36)
    @Test fun televisionTitleSafePaddingIsPreserved() = bars(hidden = false, ime = 0, tv = true)

    private fun layout(root: LinearLayout) {
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 600, 600)
        root.viewTreeObserver.dispatchOnGlobalLayout()
    }

    @Test fun nativeRailInsertsOneLocalSlotAndRestoresItWithoutShrinkingTargets() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            var calls = 0
            val buttons = (0..3).map { index -> TextView(activity).apply {
                text = "Action $index"
                setOnClickListener { calls++ }
                root.addView(this, LinearLayout.LayoutParams(240, 64))
            } }
            activity.setContentView(root)
            layout(root)
            val at = IntArray(2).also { buttons[2].getLocationInWindow(it) }
            var cameras = listOf(Rect(at[0], at[1] + 2, at[0] + 32, at[1] + 22))
            NovaCameraViewAvoidance.install(root) { cameras }
            repeat(4) { layout(root) }
            assertEquals(22, (buttons[2].layoutParams as ViewGroup.MarginLayoutParams).topMargin)
            assertEquals(0, (buttons[0].layoutParams as ViewGroup.MarginLayoutParams).topMargin)
            assertEquals(0, (buttons[3].layoutParams as ViewGroup.MarginLayoutParams).topMargin)
            assertEquals(240, buttons[2].width)
            assertEquals(64, buttons[2].height)
            buttons[2].performClick()
            assertEquals(1, calls)
            cameras = emptyList()
            repeat(3) { layout(root) }
            assertEquals(0, (buttons[2].layoutParams as ViewGroup.MarginLayoutParams).topMargin)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun actualHostsManageProtectsItsWholeTargetWithoutMovingTheList() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            activity.setTheme(R.style.AppTheme)
            val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val list = object : RecyclerView(activity) {
                override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) { }
            }.apply { layoutManager = LinearLayoutManager(activity) }
            val item = LayoutInflater.from(activity).inflate(R.layout.pc_grid_item, list, false)
            val manage = item.findViewById<View>(R.id.server_actions_button)
            var calls = 0
            manage.setOnClickListener { calls++ }
            list.addView(item)
            root.addView(list, LinearLayout.LayoutParams(600, 500))
            activity.setContentView(root)
            layout(root)
            // Real RecyclerView item layout is driven by its manager; retain the actual inflated
            // card/Manage tree and lay out this single item deterministically for camera geometry.
            fun cardLayout() {
                layout(root)
                item.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.AT_MOST))
                item.layout(0, 0, 600, item.measuredHeight)
                root.viewTreeObserver.dispatchOnGlobalLayout()
            }
            cardLayout()
            val before = IntArray(2).also { manage.getLocationInWindow(it) }
            val height = manage.height
            val camera = Rect(before[0], before[1], before[0] + 2, before[1] + 3)
            NovaCameraViewAvoidance.install(root) { listOf(camera) }
            repeat(5) { cardLayout() }
            val after = IntArray(2).also { manage.getLocationInWindow(it) }
            assertTrue("the 48dp target edge, outside the centered glyph, clears the camera", after[1] >= camera.bottom)
            assertEquals("the camera cannot insert a gap before the entire RecyclerView", 0,
                (list.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
            assertEquals("Manage keeps its full target height", height, manage.height)
            manage.performClick()
            assertEquals(1, calls)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun nativeCardHeaderProtectsHorizontalMenuAndVerticalTitleOnce() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            activity.setTheme(R.style.AppTheme)
            val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val card = MaterialCardView(activity)
            val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            var calls = 0
            val menu = TextView(activity).apply { text = "Menu"; setOnClickListener { calls++ } }
            header.addView(menu, LinearLayout.LayoutParams(100, 64))
            val titleColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(activity).apply { text = "Nova" }
            titleColumn.addView(title, LinearLayout.LayoutParams(100, 64))
            header.addView(titleColumn, LinearLayout.LayoutParams(100, 64))
            card.addView(header)
            root.addView(card, LinearLayout.LayoutParams(240, ViewGroup.LayoutParams.WRAP_CONTENT))
            activity.setContentView(root)
            layout(root)
            val at = IntArray(2).also { menu.getLocationInWindow(it) }
            val camera = Rect(0, at[1], 600, at[1] + 80)
            NovaCameraViewAvoidance.install(root) { listOf(camera) }
            repeat(5) { layout(root) }
            assertEquals("the shared card gets one notch slot", 80, (card.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
            assertEquals("the title does not add the camera twice", 0, (title.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
            val menuAt = IntArray(2).also { menu.getLocationInWindow(it) }
            assertTrue(menuAt[1] >= camera.bottom)
            assertEquals(64, menu.height)
            menu.performClick()
            assertEquals(1, calls)
        } finally { controller.pause().stop().destroy() }
    }
}
