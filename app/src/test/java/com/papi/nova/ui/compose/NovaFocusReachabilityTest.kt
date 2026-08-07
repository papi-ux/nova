package com.papi.nova.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A control that tracks focus but cannot receive it.
 *
 * `clickable` makes something tappable. In this codebase it is not on its own enough to make it
 * a d-pad target — `combinedClickable` is, which is why several components use that and are
 * guarded against adding `focusable()` on top. So a composable that watches `onFocusChanged`
 * while offering only `clickable` has written down an intention it cannot act on: the callback
 * never fires, the focus branch is dead, and a controller walks straight past the control.
 *
 * That is not cosmetic. It shipped in the Polaris Sync sheet, where it made the entire stream
 * display selector — four modes — reachable by touch alone, on a handheld.
 */
class NovaFocusReachabilityTest {

    @Test
    fun nothingTracksFocusWithoutBeingAbleToReceiveIt() {
        val offenders = mutableListOf<String>()

        for (file in sourceFiles()) {
            val text = withoutComments(file.readText())
            // A file that wires focus anywhere is focus-aware, and its composables may be
            // handed a focusRequester by their callers rather than owning one. Checking at
            // file scope rather than per-composable costs precision and buys the guard the
            // right to be trusted: NovaGameDetailAction takes its requester from the caller's
            // modifier, and it is reachable on device.
            if (FOCUS_WIRING.containsMatchIn(text)) continue

            for (match in COMPOSABLE.findAll(text)) {
                val body = balancedBody(text, match.range.last) ?: continue
                if (body.contains("onFocusChanged") && body.contains(".clickable(")) {
                    offenders += file.name + "  " + match.groupValues[1]
                }
            }
        }

        assertEquals(
            "These watch onFocusChanged and are clickable, but nothing makes them a focus " +
                "target — so the callback never fires and a d-pad skips the control " +
                "entirely. Add .focusable(), or use a shared component that already owns " +
                "focus. Verified on a Retroid: before this guard existed, the Polaris Sync " +
                "stream display selector could only be changed by touching the screen.",
            emptyList<String>(),
            offenders
        )
    }

    private fun withoutComments(body: String): String =
        body.split("\n")
            .filterNot { it.trimStart().let { l -> l.startsWith("//") || l.startsWith("*") } }
            .joinToString("\n")

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
        val COMPOSABLE = Regex("""fun ([A-Za-z][A-Za-z0-9_]*)\s*\(""")

        /** Any sign that this file makes something a focus target on purpose. */
        val FOCUS_WIRING = Regex(
            """\.focusable\(|combinedClickable|\.selectable\(|focusRequester\(|novaHoldsFirstFocus"""
        )
    }
}
