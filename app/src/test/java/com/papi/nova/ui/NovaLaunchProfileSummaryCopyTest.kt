package com.papi.nova.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What Launch and the plan say comes from string resources, English and German, not from English
 * written into the summary builder (N22). "Launch at %s FPS" was the last line added that way.
 */
class NovaLaunchProfileSummaryCopyTest {
    @Test
    fun theSummaryBuilderWritesNoEnglishOfItsOwn() {
        val source = File("src/main/java/com/papi/nova/ui/NovaLaunchProfileSummary.kt").readText()
        val phrases = listOf(
            "\"Launch at ",
            "\"Launch \$",
            "\"Requested: ",
            "\"Selected: ",
            "\"Resolved: ",
            "\"Limited by: ",
            "\"Reason: ",
            "\"Performance: ",
            "\"Held by ",
            "\"Resolved for this launch\"",
            "\"Launch preset\"",
            "\"Space stream\"",
            "\"Heads up\"",
            "\"Last stream: ",
            "\"Try High FPS once\"",
            " (client choice)\"",
            "\"SDR (",
            "\"Recovery active from last session",
            "\"Next launch: ",
            "\"No recovery adjustment is needed.\"",
            "\"just now\"",
        )
        val found = phrases.filter { source.contains(it) }
        assertTrue("English written in the builder: $found", found.isEmpty())
    }
}
