package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Status labels are names, so they are title case.
 */
class AutoQualityLabelCasingTest {

    @Test
    fun everyStatusLabelIsTitleCase() {
        val source = File("src/main/java/com/papi/nova/ui/AutoQualityUiState.kt").readText()
        val offenders = mutableListOf<String>()

        for (match in LABEL.findAll(source)) {
            val label = match.groupValues[1]
            val words = label.split(" ").filter { it.isNotBlank() }
            words.forEachIndexed { i, word ->
                val head = word.first()
                if (!head.isLetter()) return@forEachIndexed
                // Articles and short prepositions stay lowercase inside a phrase, never first.
                if (i > 0 && word.lowercase() in MINOR) return@forEachIndexed
                if (head.isLowerCase()) {
                    offenders += "\"" + label + "\"  (" + word + ")"
                }
            }
        }

        assertEquals(
            "AutoQualityUiState status chips are names and must not drift into sentence casing. " +
                "These are chip labels, so they take " +
                "title case, with articles and short prepositions lowercase inside a phrase.",
            emptyList<String>(),
            offenders
        )
    }

    private companion object {
        val LABEL = Regex("""\blabel = "([^"]+)"""")

        val MINOR = setOf(
            "a", "an", "and", "as", "at", "but", "by", "for", "in", "nor", "of", "on",
            "or", "per", "the", "to", "vs", "with"
        )
    }
}
