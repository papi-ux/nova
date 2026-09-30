package com.papi.nova.grid

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.PcViewModel
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.NovaControlSize
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual inflated/bound cards: stored Control Size previously never reached this View path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h480dp-land")
class NovaHostControlSizeViewTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.AppTheme)
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    @After fun clear() { preferences.edit().clear().commit() }

    private data class Card(val adapter: PcGridAdapter, val holder: GenericGridAdapter.ViewHolder,
        val computer: PcViewModel.ComputerObject)

    private fun card(size: String): Card {
        preferences.edit().putString("nova_control_size", size).commit()
        val adapter = PcGridAdapter(context, PreferenceConfiguration())
        val computer = PcViewModel.ComputerObject(ComputerDetails().apply {
            uuid = "size-test-host"; name = "Living room computer"; state = ComputerDetails.State.ONLINE
        })
        adapter.setItems(listOf(computer))
        val parent = RecyclerView(context)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        layout(holder.itemView)
        return Card(adapter, holder, computer)
    }

    private fun layout(view: View) {
        val width = (700 * context.resources.displayMetrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun allThreeSizesReachCardPaddingAndActionSpacingWithoutScalingTextOrDensity() {
        val standard = card("standard").holder.itemView
        val compact = card("compact").holder.itemView
        val large = card("large").holder.itemView
        val standardBody = standard.findViewById<View>(R.id.server_card_body)
        val compactBody = compact.findViewById<View>(R.id.server_card_body)
        val largeBody = large.findViewById<View>(R.id.server_card_body)
        assertTrue("Compact card content spacing must shrink", compactBody.paddingLeft < standardBody.paddingLeft)
        assertTrue("Large card content spacing must grow", largeBody.paddingLeft > standardBody.paddingLeft)
        val gap: (View) -> Int = { (it.findViewById<View>(R.id.server_actions_button).layoutParams as LinearLayout.LayoutParams).marginStart }
        assertTrue(gap(compact) < gap(standard))
        assertTrue(gap(large) > gap(standard))
        val text = standard.findViewById<TextView>(R.id.grid_text).textSize
        assertEquals(text, compact.findViewById<TextView>(R.id.grid_text).textSize, 0f)
        assertEquals(text, large.findViewById<TextView>(R.id.grid_text).textSize, 0f)
        assertEquals(standard.resources.displayMetrics.density, large.resources.displayMetrics.density, 0f)
    }

    @Test fun rebindingTheSameHostAndSwitchingSizesUsesImmutableBaselinesAndStableActions() {
        val card = card("standard")
        val view = card.holder.itemView
        val body = view.findViewById<View>(R.id.server_card_body)
        val standard = body.paddingLeft
        val stableId = card.adapter.getItemId(0)
        preferences.edit().putString("nova_control_size", "large").commit()
        card.adapter.onBindViewHolder(card.holder, 0)
        layout(view)
        val large = body.paddingLeft
        assertTrue(large > standard)
        repeat(3) { card.adapter.onBindViewHolder(card.holder, 0); layout(view) }
        assertEquals(large, body.paddingLeft)
        preferences.edit().putString("nova_control_size", "compact").commit()
        card.adapter.onBindViewHolder(card.holder, 0)
        assertTrue(body.paddingLeft < standard)
        preferences.edit().putString("nova_control_size", "standard").commit()
        card.adapter.setItems(listOf(card.computer))
        card.adapter.onBindViewHolder(card.holder, 0)
        assertEquals(standard, body.paddingLeft)
        assertEquals(stableId, card.adapter.getItemId(0))
        var primary = false
        var manage = false
        card.adapter.setOnItemClickListener { primary = true }
        card.adapter.setOnServerActionListener { manage = true }
        card.adapter.onBindViewHolder(card.holder, 0)
        view.findViewById<View>(R.id.server_actions_button).performClick()
        assertTrue(manage)
        assertFalse(primary)
        view.performClick()
        assertTrue(primary)
    }

    @Test fun compactManageRetainsASeparate48dpTargetAndRegularUsesItsImmutableResourceTokens() {
        val standard = card("standard").holder.itemView
        assertEquals((context.resources.getDimensionPixelSize(R.dimen.nova_spacing_lg) * NovaControlSize.Standard.layoutScale).roundToInt(),
            standard.findViewById<View>(R.id.server_card_body).paddingLeft)
        val density = context.resources.displayMetrics.density
        assertEquals((14 * density * NovaControlSize.Standard.layoutScale).roundToInt(), standard.findViewById<View>(R.id.server_card_body).paddingTop)
        listOf("compact", "standard", "large").forEach { size ->
            val view = card(size).holder.itemView
            val manage = view.findViewById<View>(R.id.server_actions_button)
            assertTrue("$size Manage target width", manage.width >= 48 * density)
            assertTrue("$size Manage target height", manage.height >= 48 * density)
            assertTrue(manage.isClickable && manage.isFocusable)
            assertFalse("The primary label uses the full row action", view.findViewById<View>(R.id.primary_action_text).isClickable)
        }
    }

    companion object {
        @JvmStatic @BeforeClass fun suppressLogs() { TestLogSuppressor.install() }
    }
}
