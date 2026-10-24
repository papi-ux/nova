package com.papi.nova.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The strip's fit as the strip measures it, on the RP6 (XR3): a refused End's reason is the one set
 * of words the fit never leaves out, it keeps the lines the strip has room for, and the title gives
 * way to it before it would be cut. Robolectric measures every glyph one pixel wide, so the strip
 * never ran short there; here its words are measured at a device's glyph widths, calibrated on
 * papi's RP6 screenshot of 2026-09-16 (5.56 dp a character for "RESUME YOUR STREAM" and 6.5 dp for
 * "Animal Well" at font scale 0.85), and the fit is the one the strip draws with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaLibraryStripMeasuredFitTest {
    /** Glyph widths and line heights of a device's text, in pixels at [density]. */
    private class DeviceText(private val density: Density) : NovaTopBarTextMeasure {
        private fun em(style: TextStyle): Float = with(density) { style.fontSize.toPx() }

        private fun tracking(style: TextStyle): Float =
            if (style.letterSpacing.type == TextUnitType.Em) style.letterSpacing.value else 0f

        private fun glyphs(text: String, style: TextStyle): Float = text.sumOf { c ->
            when {
                c == ' ' -> 0.28
                c.isUpperCase() -> 0.68
                else -> 0.55
            } + tracking(style)
        }.toFloat() * em(style)

        override fun width(text: String, style: TextStyle): Int = ceil(glyphs(text, style)).toInt()

        override fun height(text: String, style: TextStyle): Int = with(density) {
            if (style.lineHeight.type == TextUnitType.Sp) style.lineHeight.toPx() else style.fontSize.toPx() * 1.4f
        }.roundToInt()

        override fun lines(text: String, style: TextStyle, maxWidthPx: Int): Int {
            var lines = 1
            var line = ""
            for (word in text.split(' ')) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && glyphs(next, style) > maxWidthPx) {
                    lines++
                    line = word
                } else {
                    line = next
                }
            }
            return lines
        }
    }

    private val reason = "Another device started this session. End it there or on the host."

    /** The RP6's landscape strip, 795 dp inside its padding, a Space playing, End refused. */
    private fun fit(fontScale: Float, refusal: Boolean = true): NovaTopBarFit {
        val density = Density(density = 369f / 160f, fontScale = fontScale)
        return novaLibraryTopBarMeasuredFit(
            measure = DeviceText(density),
            density = density,
            available = 795.dp,
            largeText = fontScale >= 1.5f,
            hostLabel = "test-pc.lan",
            hostStatus = "Polaris ready",
            environment = NovaEnvironmentStrings(caption = "Your Space", name = "papi - steam", status = "Playing"),
            continueCard = NovaTopBarContinue(
                eyebrow = if (refusal) reason else "Resume your stream",
                title = "Control Ultimate Edition",
                actionLabel = "Resume Stream",
                // Another device's session: End is gone, and no Try Again takes its place.
                secondaryActionLabel = if (refusal) null else "End Session",
                hasCover = true,
                refusal = refusal,
            ),
            base = TextStyle.Default,
            buttonStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            splitStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            optionsLabel = "Options",
            systemLabel = "System",
        )
    }

    @Test
    fun aRefusedEndsReasonIsNeverLeftOutOnTheRp6() {
        for (fontScale in listOf(1f, 1.3f)) {
            assertTrue("the reason stays at $fontScale: ${fit(fontScale)}", fit(fontScale).showContinueText)
        }
    }

    // The reason is a sentence: cut to one line it lost its end, with nothing to say it was cut.
    @Test
    fun theReasonKeepsTheLinesTheStripHasForIt() {
        for (fontScale in listOf(1f, 1.3f)) {
            val fit = fit(fontScale)
            assertTrue("two lines or more at $fontScale: $fit", fit.continueEyebrowLines >= 2)
        }
    }

    // At 1.3 the strip has one line for the reason over the title, where the whole sentence does
    // not fit: the title gives way, and the reason has the strip's height alone.
    @Test
    fun atLargerTextTheTitleGivesWayToTheReason() {
        val fit = fit(1.3f)
        assertFalse("the title gives way: $fit", fit.showContinueEyebrow)
        assertEquals("the reason alone has three lines of the strip", 3, fit.continueEyebrowLines)
    }

    @Test
    fun aCardWithNoRefusalKeepsItsTitleAndOneEyebrowLine() {
        val fit = fit(1f, refusal = false)
        assertTrue(fit.showContinueText)
        assertEquals(1, fit.continueEyebrowLines)
    }

    private val recapWords = NovaTopBarRecap(
        label = "LAST STREAM",
        summary = "Video surface lost · 10:56 PM",
        viewLabel = "View",
        dismissLabel = "Dismiss",
    )

    /**
     * The RP6's strip with a Space that is ready, a live session's card when [live], and the Last
     * Stream recap's words in the handheld panel type its card draws them in.
     */
    private fun recapFit(fontScale: Float, live: Boolean = false, recap: NovaTopBarRecap? = recapWords): NovaTopBarFit {
        val density = Density(density = 369f / 160f, fontScale = fontScale)
        return novaLibraryTopBarMeasuredFit(
            measure = DeviceText(density),
            density = density,
            available = 795.dp,
            largeText = fontScale >= 1.5f,
            hostLabel = "test-pc.lan",
            hostStatus = "Polaris ready",
            environment = NovaEnvironmentStrings(caption = "Your Space", name = "papi - steam", status = null),
            continueCard = if (!live) null else NovaTopBarContinue(
                eyebrow = "Resume your stream",
                title = "Control Ultimate Edition",
                actionLabel = "Resume Stream",
                secondaryActionLabel = "End Session",
                hasCover = true,
            ),
            base = TextStyle.Default,
            buttonStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            splitStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            optionsLabel = "Options",
            systemLabel = "System",
            recap = recap,
            recapLabelStyle = novaStreamRecapSolid(com.papi.nova.ui.panel.NovaPanelType.Handheld.sectionLabel),
            recapSummaryStyle = novaStreamRecapSolid(com.papi.nova.ui.panel.NovaPanelType.Handheld.caption),
        )
    }

    // The strip's middle is about 409 dp of nothing at 1.0 on the RP6, and the recap wants about
    // 343: it goes there, on a line it has room for, and nothing else gives way for it.
    @Test
    fun theRp6sIdleStripHoldsTheRecapInItsMiddle() {
        val fit = recapFit(1f)
        assertTrue("the recap fits the idle middle: $fit", fit.showRecap)
        assertTrue("its summary has a line: $fit", fit.recapSummaryLines >= 1)
        assertEquals(recapFit(1f, recap = null), fit.copy(showRecap = false, recapSummaryLines = 1))
    }

    // At 1.3 the words outgrow what the row leaves over: the recap takes the bar's second line
    // rather than the host's status, the Space's caption or a menu's width.
    @Test
    fun atLargerTextTheRecapTakesTheBarsSecondLineAndCostsNothing() {
        val fit = recapFit(1.3f)
        assertFalse("the second line at 1.3: $fit", fit.showRecap)
        assertEquals(recapFit(1.3f, recap = null), fit.copy(recapSummaryLines = 1))
    }

    @Test
    fun aLiveSessionsCardKeepsTheMiddleFromTheRecap() {
        for (fontScale in listOf(0.85f, 1f, 1.3f)) {
            val fit = recapFit(fontScale, live = true)
            assertFalse("Resume and End keep the middle at $fontScale: $fit", fit.showRecap)
            assertEquals(recapFit(fontScale, live = true, recap = null), fit.copy(recapSummaryLines = 1))
        }
    }
}
