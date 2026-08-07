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
    }
}
