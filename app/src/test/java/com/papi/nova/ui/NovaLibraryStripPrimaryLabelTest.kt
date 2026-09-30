package com.papi.nova.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The library strip is one fixed height, and its primary button is only as tall as the strip. A
 * label that wrapped there took a second line the strip had no room for, and the line was cut
 * through (C25). The strip keeps the label on one line, whole at the width the strip's fit measured
 * for it, and when a large font or a long word still overruns that line, it reveals the rest while
 * the button has focus instead of cutting it.
 */
class NovaLibraryStripPrimaryLabelTest {
    @Test
    fun theStripsPrimaryKeepsOneLineAndRevealsTheRestUnderFocus() {
        val hero = File("src/main/java/com/papi/nova/ui/NovaLibraryHero.kt").readText()
        val strip = hero.substringAfter("internal fun RowScope.NovaLibraryStripContinue(")
            .substringBefore("internal fun novaLibraryStripButtonStyle(")
        val primary = strip.substringAfter("} else if (!endArmed) ").substringBefore("val secondaryLabel")
        assertTrue("the primary's label is a one-line reveal", primary.contains("NovaRevealingText(") && primary.contains("maxLines = 1"))
        assertTrue("revealed while the button holds focus", primary.contains("highlighted = focused"))
        assertFalse("not the wrapping shared button, whose second line the strip cut", primary.contains("NovaActionButton("))
    }
}
