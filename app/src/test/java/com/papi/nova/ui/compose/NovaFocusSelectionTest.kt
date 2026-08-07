package com.papi.nova.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focus and selection are different states and have to look different.
 *
 * Nothing enforced that, and five places had merged them — two of them by writing
 * `focused || selected` as a single branch, which is the merge stated outright.
 */
class NovaFocusSelectionTest {

    @Test
    fun noBranchTreatsFocusAndSelectionAsOneState() {
        val offenders = mutableListOf<String>()

        for (file in sourceFiles()) {
            file.readText().split("\n").forEachIndexed { i, line ->
                // Comments describe the rule, including the ones written next to these very
                // fixes, so scanning them makes the guard fail on its own explanation.
                if (!isComment(line) && COMBINED.containsMatchIn(line)) {
                    offenders += file.name + ":" + (i + 1) + "  " + line.trim()
                }
            }
        }

        assertEquals(
            "Focus is transient and says where the d-pad is; selection is persistent and " +
                "says what is chosen. A condition that covers both draws them identically, " +
                "so an unfocused selected item reads as focused and two things on screen " +
                "claim to be where you are. Give them separate branches: focus takes the " +
                "fill and the ring, selection takes accentSurface and the accent border.\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun noWhenGivesFocusAndSelectionTheSameColour() {
        val offenders = mutableListOf<String>()

        for (file in sourceFiles()) {
            val lines = file.readText().split("\n")
            lines.forEachIndexed { i, line ->
                val focusArm = FOCUS_ARM.find(line) ?: return@forEachIndexed
                // the selection arm of the same `when` sits within a couple of lines
                for (j in (i + 1)..minOf(i + 3, lines.lastIndex)) {
                    val selectArm = SELECT_ARM.find(lines[j]) ?: continue
                    if (focusArm.groupValues[1].trim() == selectArm.groupValues[1].trim()) {
                        offenders += file.name + ":" + (i + 1) + "  both arms -> " +
                            focusArm.groupValues[1].trim()
                    }
                }
            }
        }

        assertEquals(
            "Two `when` arms, one testing focus and one testing selection, returning the " +
                "same colour is the same merge written out longhand — NovaSelectableChip " +
                "did exactly this, and it backs every filter, sort and layout chip.\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun theActionButtonSelectedFlagIsVisibleAndNotJustSemantic() {
        val source = File("src/main/java/com/papi/nova/ui/compose/NovaFocusComponents.kt").readText()
        val button = source.substringAfter("fun NovaActionButton(")

        // `selected` reached the semantics tree and no colour branch, so it was announced to
        // TalkBack and invisible on screen. Call sites compensated by passing `primary = true`
        // to mean selected, which is how that flag came to carry two meanings.
        assertTrue(
            "NovaActionButton's selected flag has to change what is drawn, not only what is announced",
            button.contains("selected && enabled -> colors.accentSurface")
        )
    }

    private fun isComment(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
    }

    private fun sourceFiles(): List<File> =
        File("src/main/java/com/papi/nova").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private companion object {
        /** `focused || selected`, in either order, anywhere in a condition. */
        val COMBINED = Regex("""(focused\s*\|\|\s*selected|selected\s*\|\|\s*focused)""")

        val FOCUS_ARM = Regex("""^\s*focused\s*->\s*(.+?),?\s*$""")
        val SELECT_ARM = Regex("""^\s*selected\s*->\s*(.+?),?\s*$""")
    }
}
