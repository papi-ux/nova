package com.papi.nova.grid

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.panel.NovaViewBridge
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On the RP6 and the Shield a host card's Open Library and Wake were flat accent whether or not
 * the card had focus, while Manage, the Servers chip and the online card rested in accent
 * outlines: two or three things read as focused and the one A would press did not. Only focus
 * may fill or outline (R9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHostCardFocusLookTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun thePrimaryRestsAsATileAndFillsOnlyWhileTheCardHasFocus() {
        val accent = NovaThemeManager.getAccentColor(context)
        val button = NovaViewBridge.primaryButton(context) as StateListDrawable
        fun fillFor(vararg state: Int): Int {
            button.state = state
            return requireNotNull((button.current as GradientDrawable).color).defaultColor
        }
        assertNotEquals("at rest it is a tile, not the accent", accent, fillFor())
        assertEquals(accent, fillFor(android.R.attr.state_selected))
        assertEquals(accent, fillFor(android.R.attr.state_focused))
    }

    @Test
    fun theAdapterSelectsThePrimaryOnlyWithTheCardsFocusAndDropsItWithNothingToWake() {
        val adapter = File("src/main/java/com/papi/nova/grid/PcGridAdapter.kt").readText()
        assertTrue(adapter.contains("primaryAction?.isSelected = hasFocus && primaryAction?.isActivated == true"))
        assertTrue(adapter.contains("primaryAction.background = NovaViewBridge.primaryButton(context)"))
        assertTrue(
            "an offline host with no wake target has no action, not a Refreshing button forever",
            adapter.contains("primaryAction?.visibility = View.GONE") &&
                !adapter.substringAfter("State.OFFLINE").substringBefore("} else {\n            imgView.alpha = 0.6f")
                    .contains("pcview_card_action_refreshing"),
        )
        assertFalse(
            "the online card's hairline is not an accent outline",
            adapter.contains("ColorUtils.blendARGB(NovaThemeManager.getDividerColor(context), accent, 0.35f)"),
        )
    }

    @Test
    fun aChipIsOutlinedInTheAccentOnlyUnderFocus() {
        val chip = File("src/main/res/drawable/nova_chip_default.xml").readText()
        assertEquals("one accent stroke, the focused one", 1, Regex("colorAccent").findAll(chip).count())
        assertTrue(chip.substringBefore("colorAccent").contains("android:state_focused=\"true\""))
        assertFalse(chip.contains("state_activated") || chip.contains("state_selected"))
        val pcView = File("src/main/java/com/papi/nova/PcView.kt").readText()
        assertTrue(
            "the current Browse segment is not outlined at rest",
            pcView.contains("updateModeSegmentStroke(card, focused, accent, divider)") &&
                pcView.contains("updateModeSegmentStroke(card, hasFocus, accent, divider)"),
        )
    }
}
