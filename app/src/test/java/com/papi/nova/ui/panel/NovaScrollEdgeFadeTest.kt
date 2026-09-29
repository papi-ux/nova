package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The RP6 walk found rows cut mid line at the edges of scrolling panels: Library Options' Layout
 * row half drawn above the hint bar, the Command Center's Stream card, System's About Nova, the
 * host panel's Open Library row sliding under the title, and the Settings rail running into the
 * screen. Every scrolling panel list, and the rail, now fades an edge while more lies past it,
 * so a partial row reads as more content. The fade is drawn, which Robolectric cannot capture
 * without native graphics, so the lists are checked where they are built; the rail's scrolling,
 * which keeps the focused row clear of the fade, is checked by moving focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaScrollEdgeFadeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun everyScrollingPanelListFadesItsEdges() {
        val offenders = PANEL_LISTS.flatMap { path ->
            val text = File(MAIN, path).readText()
            val lazy = Regex("""LazyColumn\(""").findAll(text).mapNotNull { args(text, it.range.last + 1) }
                .filterNot { it.contains("novaScrollEdgeFade(") }
                .map { "$path: a LazyColumn without the edge fade" }
            // The fade goes right before the scroll in the modifier chain: on its line, or the one before.
            val lines = text.lines()
            val columns = lines.indices.filter { lines[it].contains(".verticalScroll(") }
                .filterNot { i ->
                    lines[i].contains(".novaScrollEdgeFade(") ||
                        lines.take(i).lastOrNull { it.isNotBlank() }?.contains(".novaScrollEdgeFade(") == true
                }
                .map { "$path:${it + 1}: a scrolling column without the edge fade" }
            lazy.toList() + columns
        }
        assertEquals(
            "a row cut by a list's edge must read as more content: fade the edge (novaScrollEdgeFade)",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun theSettingsRailScrollsWithARowOfContextAsThePaneDoes() {
        val settings = File(MAIN, "preferences/NovaSettingsScreen.kt").readText()
        val rail = settings.substring(
            settings.indexOf("private fun NovaSettingsCategoryRail("),
            settings.indexOf("private fun NovaSettingsCategoryChips("),
        )
        assertTrue(
            "the rail keeps a row of context past the focused category, so the fade never covers it",
            rail.contains("NovaRowContextScrolling {") && rail.contains("novaScrollEdgeFade(focus.railState)"),
        )
    }

    @Test
    fun contextScrollingKeepsTheRowAfterTheFocusedOneInView() {
        val keys = rule.setPanelContent {
            NovaRowContextScrolling {
                LazyColumn(
                    state = rememberLazyListState(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.height(200.dp).testTag("list"),
                ) {
                    items((0 until 12).toList()) { index ->
                        Box(Modifier.fillMaxWidth().requiredHeight(44.dp).testTag("row-$index").focusable())
                    }
                }
            }
        }
        rule.onNodeWithTag("row-0").requestFocus()
        rule.waitForIdle()
        repeat(4) { keys.press(NovaTestKeys.DOWN) }
        rule.waitForIdle()
        rule.onNodeWithTag("row-4").assertIsFocused()

        val list = rule.onNodeWithTag("list").getUnclippedBoundsInRoot()
        val next = rule.onNodeWithTag("row-5").getUnclippedBoundsInRoot()
        assertTrue(
            "the row after the focused one is whole, clear of the edge the fade covers: ${next.bottom} in ${list.bottom}",
            next.bottom <= list.bottom + 0.5.dp,
        )
    }

    private fun args(text: String, from: Int): String? {
        var depth = 1
        for (index in from until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(from, index)
            }
        }
        return null
    }

    private companion object {
        val MAIN = File("src/main/java/com/papi/nova")

        /** Files whose pages are drawn in a panel or the Settings pane. */
        val PANEL_LISTS = listOf(
            "ui/panel/NovaCommonPages.kt",
            "ui/NovaCommandCenterPages.kt",
            "ui/NovaQuickMenuContent.kt",
            "ui/NovaLibraryPanels.kt",
            "ui/NovaPlaySetup.kt",
            "ui/NovaPlaySetupPages.kt",
            "preferences/NovaSettingsScreen.kt",
            "preferences/NovaDisplayRoleComposer.kt",
        )
    }
}
