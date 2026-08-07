package com.papi.nova.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corner scale had no guard, which is how it got to thirteen values.
 */
class NovaRadiusScaleTest {

    @Test
    fun everyComposeCornerComesFromTheSharedScale() {
        val offenders = mutableListOf<String>()

        for (file in sourceFiles()) {
            val text = file.readText()
            var at = text.indexOf(CALL)
            while (at >= 0) {
                val args = balancedArgs(text, at + CALL.length)
                if (args != null && BARE_DP.containsMatchIn(args)) {
                    val line = text.take(at).count { it == '\n' } + 1
                    offenders += "${file.name}:$line  RoundedCornerShape($args)"
                }
                at = text.indexOf(CALL, at + CALL.length)
            }
        }

        assertEquals(
            "Corners come from NovaRadius, not from a number at the call site. Nova reached " +
                "thirteen distinct values across 73 sites before the scale existed — each one " +
                "individually defensible, which is exactly how that happens. Sheets are the " +
                "deliberate exception and go through NovaSheetChrome.SHEET_CORNER_RADIUS_DP, " +
                "which is a named constant and so does not trip this.\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun theSharedScaleHasReaders() {
        // NovaFocusableCard and NovaChromeType.label() were both added as shared components
        // and then called from nowhere. A scale nobody imports fails the same way, and it
        // fails quietly, because unused code compiles.
        val readers = sourceFiles().count { it.readText().contains("NovaRadius.") }

        assertTrue(
            "the shared scale is imported by $readers files; it is meant to be the app's",
            readers >= 10
        )
    }

    @Test
    fun cornerArgumentsAndDefaultsAlsoComeFromTheScale() {
        // The first version of this guard parsed only RoundedCornerShape(...), and so reported
        // the sweep finished while twenty-two corner values were still sitting in parameter
        // defaults and in `cornerRadius = 12.dp` arguments to NovaActionButton, HudPanel and
        // the focus-halo modifier. A guard that pins one spelling of a decision only moves the
        // decision to a different spelling.
        val offenders = mutableListOf<String>()

        for (file in sourceFiles()) {
            file.readText().split("\n").forEachIndexed { i, line ->
                if (CORNER_LITERAL.containsMatchIn(line)) {
                    offenders += file.name + ":" + (i + 1) + "  " + line.trim()
                }
            }
        }

        assertEquals(
            "Corner radii passed as arguments or declared as parameter defaults come from " +
                "NovaRadius too, not only the ones written inside RoundedCornerShape(...).\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun theScaleIsOrderedAndSmall() {
        // Three steps and a pill. The ordering is the part worth pinning: chip < row < hero
        // is what lets a call site pick by role rather than by measuring.
        assertTrue("chip is the tightest step", NovaRadius.chip < NovaRadius.row)
        assertTrue("row sits between chip and hero", NovaRadius.row < NovaRadius.hero)
        assertTrue("the pill is fully round", NovaRadius.hero < NovaRadius.pill)
    }

    private fun sourceFiles(): List<File> =
        File("src/main/java/com/papi/nova").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** The argument text of a call opening at [from], or null if the parentheses do not close. */
    private fun balancedArgs(text: String, from: Int): String? {
        var depth = 1
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(from, i)
            }
            i++
        }
        return null
    }

    private companion object {
        const val CALL = "RoundedCornerShape("

        /**
         * A dp literal that is a bare number: `14.dp` matches, `NovaRadius.row` has no `.dp`
         * at all, and `SHEET_CORNER_RADIUS_DP.dp` is preceded by a word character so the
         * lookbehind rejects it.
         */
        val BARE_DP = Regex("""(?<![\w.])\d+(?:\.\d+)?\.dp""")

        /**
         * A corner value spelled as an argument or a parameter default rather than inside a
         * RoundedCornerShape: `cornerRadius = 12.dp`, `cornerRadius: Dp = 14.dp`, and
         * `val NovaPosterCornerRadius = 6.dp` all match.
         *
         * The View-layer GradientDrawable API also has a `cornerRadius`, but it takes a float
         * of pixels rather than a Dp, so it does not match and is not in scope here.
         */
        val CORNER_LITERAL = Regex(
            """(?i)corner[a-z]*\s*(?::\s*Dp\s*)?=\s*\d+(?:\.\d+)?\.dp"""
        )
    }
}
