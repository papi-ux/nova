package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spaces speak one grammar on every surface.
 *
 * The feature arrived in three vocabularies (Space, environment, profile; Change, Choose,
 * Play In) because nothing read the whole app at once. This does, so the words that left
 * stay gone and the words that stayed are the resources every surface shares.
 */
class NovaSpacesVocabularyTest {
    private val spacesSources = listOf(
        "src/main/java/com/papi/nova/ui/NovaSpaceChooser.kt",
        "src/main/java/com/papi/nova/ui/NovaEnvironmentBar.kt",
        "src/main/java/com/papi/nova/ui/NovaSpaceContent.kt",
        "src/main/java/com/papi/nova/ui/NovaSpacesCopy.kt",
        "src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt",
        "src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt",
        "src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt",
        "src/main/java/com/papi/nova/grid/PcGridAdapter.kt",
        "src/main/java/com/papi/nova/Game.kt",
        // The legacy menu's successor: the Command Center's pages.
        "src/main/java/com/papi/nova/ui/NovaCommandCenterPages.kt",
        "src/main/java/com/papi/nova/ui/NovaQuickMenuUiState.kt",
    )

    @Test
    fun theWordsThatLeftStayGone() {
        val banned = listOf(
            "\"Choose Space", "\"Playing In", "Your Environment", "Changing Environment", "\"Leave Space",
            "\"Space Could Not Start", "\"Spaces Available", "Refresh Spaces", "\"Space In Use", "\"Where This Game Opens",
            "\"Change Space\"", "\"Open Steam Big Picture\"", "Checking Space Status", "Choose Where To Play",
        )
        val offenders = spacesSources.flatMap { path ->
            val text = File(path).readText()
            banned.filter { text.contains(it) }.map { "$path: $it" }
        }
        assertEquals(
            "a Spaces surface says these in a resource of its own, in the shared vocabulary, or not at all:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun spaceStringsUseTheGlossary() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val spaceStrings = Regex("""<string name="(nova_space_[a-z_]+)">(.*?)</string>""")
            .findAll(strings).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue("the Spaces strings live in strings.xml, not in Kotlin", spaceStrings.size >= 80)
        val banned = listOf("profile", "seat", "worker", "environment", "multiseat", "Choose Space", "Play In", "Playing In ")
        val offenders = spaceStrings.filter { (_, text) -> banned.any { text.contains(it, ignoreCase = true) } }
        assertEquals("one noun, Space; one verb, Change Space:\n" + offenders.joinToString("\n"), emptyList<Pair<String, String>>(), offenders)
        assertEquals("one label for the one verb", 1, spaceStrings.count { it.second == "Change Space" })
        for (state in listOf("ready", "starting", "running", "stopping", "in_use", "unavailable", "unknown")) {
            assertTrue("a word for $state", spaceStrings.any { it.first == "nova_space_state_$state" })
        }
    }

    @Test
    fun portraitKeepsItsHeaderAndAddsTheSpaceRow() {
        val source = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
        val screen = source.substring(source.indexOf("private fun NovaLibraryScreen("), source.indexOf("private fun NovaLibraryHomeHero("))
        val portraitStart = screen.indexOf(".padding(bottom = controllerHintBarBottomPadding)")
        val portrait = screen.substring(portraitStart, screen.indexOf("AnimatedVisibility(", portraitStart))
        assertTrue(portrait.contains("NovaLibraryTopHeader("))
        assertTrue("the Space row follows the header as its own row", portrait.indexOf("NovaEnvironmentBar(") > portrait.indexOf("NovaLibraryTopHeader("))
        assertFalse(
            "portrait used to borrow the landscape strip for the Space row and lost Options and System off the right edge",
            portrait.contains("NovaLibraryLandscapeShowcaseStripContent("),
        )
    }

    @Test
    fun landscapeStripShowsTheSpaceRowOnceLeftOfOptions() {
        val strip = landscapeStrip()
        assertEquals(
            "one Space row in the strip; a rerun of a patch once left two and no host name",
            1,
            Regex("NovaEnvironmentBar\\(").findAll(strip).count(),
        )
        assertFalse("the host identity is not the else branch of the Space row", strip.contains("} else NovaLibraryToolbarIdentity("))
        val identity = strip.indexOf("NovaLibraryToolbarIdentity(")
        val middle = strip.indexOf("continueSlot(fit)")
        val space = strip.indexOf("NovaEnvironmentBar(")
        val options = strip.indexOf("NovaLibraryToolbarOptionsAction(")
        val system = strip.indexOf("NovaLibraryToolbarSystemAction(")
        assertTrue(
            "host first and the card in the middle; then Space, Options and System as one right-hand cluster. Beside the " +
                "host the Space control read as floating in the middle of the strip (papi, 2026-09-16)",
            identity in 0 until middle && middle < space && space < options && options < system,
        )
        assertFalse(
            "the result count and layout name read as a stray label beside the Space control; both live in the Options " +
                "sheet (papi, 2026-09-16 21:27: \"can get rid of 2 Shown and Grid\")",
            strip.contains("NovaLibraryResultAndLayoutMeta(") || strip.contains("nova_library_results_format"),
        )
    }

    @Test
    fun theSpaceRowIsOneControlNotALabelBesideAFloatingButton() {
        val bar = File("src/main/java/com/papi/nova/ui/NovaEnvironmentBar.kt").readText()
        assertTrue(
            "the whole Space row is the action that opens the chooser",
            bar.contains("NovaActionSurface(") && bar.contains("onClick = onChoose"),
        )
        assertFalse(
            "a Change Space button of its own sat at the far end of a fixed slot and floated in the middle of the landscape strip",
            bar.contains("NovaActionButton("),
        )
        assertTrue("its words come from the shared rules, not from the composable", bar.contains("NovaSpacesCopy.environmentLabel("))
        assertFalse(
            "drawn as plain text when there was nowhere else to go, the control could not be pressed and said nothing about " +
                "why a newly paired device could not switch (papi, 2026-09-16 21:27)",
            bar.contains("offersChoice"),
        )
        val chooser = File("src/main/java/com/papi/nova/ui/NovaSpaceChooser.kt").readText()
        assertTrue(
            "the chooser always lists Desktop and says why it is off, from the shared rules",
            chooser.contains("NovaSpacesCopy.desktopChoice(snapshot)") && !chooser.contains("if (snapshot.desktopAllowed)"),
        )
        val activity = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
        assertFalse(
            "the Space screen hid Change Space unless there was another place to go, which left nothing to press",
            activity.contains("desktopAllowed == true) (::showSpaceChooser) else null"),
        )
        assertTrue(
            "the strip's card is for something to act on now, never a copy of the grid's selection",
            activity.contains(") && NovaLibraryUiStateMapper.showTopBarCard(model.hero)"),
        )
        val strip = landscapeStrip()
        assertFalse("a fixed 300 dp slot is what pushed the button into the middle", strip.contains("width(300.dp"))
        assertTrue(
            "the control sizes to its content, capped, and takes the width the fit cut its name to",
            strip.contains("widthIn(max = minOf(280f * fontScale, fit.spaceWidth).dp)"),
        )
    }

    @Test
    fun theLandscapeStripNeverScrollsAndMeasuresWhatGivesWay() {
        val strip = landscapeStrip()
        assertFalse(
            "a horizontal scroll with a row forced to 700 or 980 dp times the font scale slid Options and System off an " +
                "815 dp Retroid strip in every Grid or Compact library with Spaces",
            strip.contains("horizontalScroll(") || strip.contains("rowWidth") || strip.contains("980.dp") || strip.contains("700.dp"),
        )
        assertTrue("what gives way is decided by the tested fit, from measured text", strip.contains("rememberNovaLibraryTopBarFit(") && strip.contains("novaLibraryTopBarFit("))
        val hostRow = strip.substringBefore("NovaLibraryToolbarIdentity(").substringAfterLast("Row(")
        assertTrue(
            "the host side stays weighted so menus measure first, and wraps camera clearance vertically",
            hostRow.contains("modifier = Modifier.weight(1f),") && !hostRow.contains("fillMaxHeight()") &&
                strip.contains("heightIn(min = NovaLibraryUiStateMapper.landscapeShowcaseStripHeightDp"),
        )
    }

    private fun landscapeStrip(): String {
        val stage = File("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt").readText()
        return stage.substring(
            stage.indexOf("internal fun NovaLibraryLandscapeShowcaseStripContent("),
            stage.indexOf("private fun NovaLibraryToolbarIdentity("),
        )
    }

    @Test
    fun theChooserIsBuiltFromTheDetailWindowRows() {
        val chooser = File("src/main/java/com/papi/nova/ui/NovaSpaceChooser.kt").readText()
        assertTrue(chooser.contains("NovaSteamChoiceRow(") && chooser.contains("NovaPanelHintBar("))
        assertFalse(
            "focus is claimed once when the chooser opens; re-requesting it on every snapshot moved the cursor on poll blips",
            chooser.contains("LaunchedEffect(snapshot"),
        )
        assertTrue(chooser.contains("LaunchedEffect(Unit)"))
    }

    @Test
    fun theParticleFieldBuildsNoGradientsPerFrameAndStopsWhenUnseen() {
        val view = File("src/main/java/com/papi/nova/ui/SpaceParticleView.kt").readText()
        val onDraw = view.substring(view.indexOf("override fun onDraw("), view.indexOf("override fun onAttachedToWindow("))
        assertFalse("gradients are built once and moved with a matrix", onDraw.contains("RadialGradient(") || onDraw.contains("LinearGradient("))
        assertTrue(view.contains("override fun onWindowVisibilityChanged(") && view.contains("fun setCovered("))
        val library = File("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt").readText()
        assertTrue("the library tells the field when the chooser or the Space screen covers it", library.contains("view.setCovered(chooseSpaceVisible || space != null)"))
    }
}
