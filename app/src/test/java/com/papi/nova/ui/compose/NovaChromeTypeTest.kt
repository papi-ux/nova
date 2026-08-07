package com.papi.nova.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brand chrome face had no guard, which is how sixteen labels drifted off it.
 */
class NovaChromeTypeTest {

    @Test
    fun theBrandFaceIsReachedOnlyThroughTheSharedLabelStyle() {
        val offenders = sourceFiles()
            .filter { it.name != "NovaBrandType.kt" && it.readText().contains("NovaChromeFamily") }
            .map { it.name }
            .sorted()

        assertEquals(
            "Uppercase chrome labels take NovaChromeType.label(). Sixteen of them used to " +
                "spell out their own face, weight and tracking — eleven trackings across three " +
                "units, four family/weight pairings, and seven labels with no fontFamily at " +
                "all, so they rendered in Roboto beside Space Grotesk. Reaching for the family " +
                "directly at a call site is how that comes back; a new role belongs in " +
                "NovaBrandType next to label().",
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun theSharedLabelStyleHasReaders() {
        // label() shipped with the game detail window and was then called from nowhere at all,
        // for as long as the labels it was written for kept hand-rolling themselves. Unused
        // code compiles, so nothing said so.
        val readers = sourceFiles().count { it.readText().contains("NovaChromeType.label(") }

        assertTrue("the shared label style is read by $readers files", readers >= 5)
    }

    private fun sourceFiles(): List<File> =
        File("src/main/java/com/papi/nova").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
}
