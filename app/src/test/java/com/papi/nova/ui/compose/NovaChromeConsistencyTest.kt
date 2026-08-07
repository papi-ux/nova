package com.papi.nova.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two things that drift silently because nothing reads the whole app at once: what the
 * product is called, and which components are allowed to draw the focus signals.
 */
class NovaChromeConsistencyTest {

    @Test
    fun theHudHasOneName() {
        val strings = File("src/main/res/values/strings.xml").readText()

        // Three strings said "Nova HUD" and three said "NovaHUD", including the Command
        // Center entry and the companion deck label, which a user sees two taps apart.
        assertEquals(
            "the HUD is called \"Nova HUD\"; the closed-up spelling had drifted into half " +
                "the strings that mention it",
            0,
            Regex("""NovaHUD""").findAll(strings).count()
        )
        assertTrue(
            "and the spaced spelling is the one that survived",
            strings.contains("Nova HUD")
        )
    }

    @Test
    fun onlyFocusableChromeDrawsTheFocusSignals() {
        // focusRing and selectedControl mean "the d-pad is here". A component that cannot take
        // focus has no business drawing either: NovaSettingOverrideBadge is a static label and
        // it wore both, which no focus-versus-selection check could catch, because there is no
        // `focused` or `selected` anywhere in it to compare.
        val offenders = mutableListOf<String>()

        for (file in sourceFiles()) {
            val text = file.readText()
            for (match in BADGE_FUN.findAll(text)) {
                val body = balancedBody(text, match.range.last)?.let(::withoutComments) ?: continue
                // NovaSettingPill is named like a badge and behaves like a button: it calls
                // .focusable() and tracks onFocusChanged. Something that really can hold focus
                // is entitled to draw the focus signals, so the test is about whether the
                // component takes focus, not about what it is called.
                val takesFocus = body.contains(".focusable()") || body.contains("onFocusChanged")
                if (!takesFocus && (body.contains("focusRing") || body.contains("selectedControl"))) {
                    offenders += file.name + "  " + match.groupValues[1]
                }
            }
        }

        assertEquals(
            "Badges and pills are static chrome. Drawing them in focusRing or " +
                "selectedControl makes the screen claim focus is somewhere it is not.\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    /**
     * The body with comment lines removed.
     *
     * A comment explaining why a component stopped using selectedControl contains the word
     * selectedControl, so scanning comments makes this fail on the fix it is asking for.
     */
    private fun withoutComments(body: String): String =
        body.split("\n")
            .filterNot { it.trimStart().let { l -> l.startsWith("//") || l.startsWith("*") } }
            .joinToString("\n")

    /** The body of the function whose signature ends at [from], by brace matching. */
    private fun balancedBody(text: String, from: Int): String? {
        val open = text.indexOf('{', from)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i)
            }
            i++
        }
        return null
    }

    private fun sourceFiles(): List<File> =
        File("src/main/java/com/papi/nova").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private companion object {
        /**
         * A composable whose name ends in Badge or Pill — static chrome by naming convention.
         * Chip is deliberately excluded: chips are selectable and several are focusable.
         */
        val BADGE_FUN = Regex("""fun (Nova[A-Za-z]*(?:Badge|Pill))\s*\(""")
    }
}
