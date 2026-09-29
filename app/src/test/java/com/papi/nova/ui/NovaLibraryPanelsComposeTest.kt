package com.papi.nova.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.R
import com.papi.nova.ui.panel.LocalNovaPanelDensity
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelDensity
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaShoulder
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Library Options and System as panel pages: where focus opens, what Left and Right do in the
 * filter row, and the two peers swapping on the shoulders in one panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaLibraryPanelsComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val state = NovaPanelState()
    private var filter by mutableStateOf(NovaLibraryPrimaryFilter.ALL)
    private var narrowed by mutableStateOf<String?>(null)
    private val filterChanges = mutableListOf<NovaLibraryPrimaryFilter>()
    private val opened = mutableListOf<String>()
    private var closeRequests = 0

    private val options = LibraryPage.Options("Library Options")
    private val system = LibraryPage.System("System")

    private fun optionsUi() = NovaLibraryOptionsUi(
        resultCount = 12,
        searchQuery = "",
        filter = filter,
        filterCaption = "12 in all · 3 recent · 2 HDR",
        narrowedLabel = narrowed,
        sourceValue = narrowed ?: "All Sources",
        moreValue = "None",
        clearable = narrowed != null,
        sortLabel = "Library Order",
        layoutMode = NovaLibraryLayoutMode.GRID,
        layoutCaption = "Broad browsing.",
        showPosterTitles = false,
        artwork = NovaArtworkLibraryUpdateUiState.Idle,
    )

    private val optionsActions = NovaLibraryOptionsActions(
        onFilter = {
            filterChanges += it
            filter = it
            narrowed = null
        },
        searchPage = { TestLibraryPage("search") },
        sourcesPage = { TestLibraryPage("sources") },
        morePage = { TestLibraryPage("more") },
        sortPage = { TestLibraryPage("sort") },
        onClearFilters = {
            narrowed = null
            filter = NovaLibraryPrimaryFilter.ALL
        },
        onLayoutMode = {},
        onPosterTitles = {},
        onRefresh = {},
        onStartArtwork = {},
        onCancelArtwork = {},
        onRetryArtwork = {},
    )

    private val systemActions = NovaLibrarySystemActions(
        onSwitchHost = { opened += "switch" },
        onSettings = { opened += "settings" },
        polarisSyncPage = { TestLibraryPage("polaris") },
        onManageServer = {},
        onHelp = {},
        onAbout = {},
        onMatrix = {},
        onSponsor = {},
    )

    private fun host(compact: Boolean = false): NovaTestKeys = rule.setPanelContent {
        // A landscape handheld's panel when compact.
        val density = if (compact) NovaPanelDensity.Compact else NovaPanelDensity.Regular
        CompositionLocalProvider(LocalNovaPanelDensity provides density) { hostedPages() }
    }

    @Composable
    private fun hostedPages() {
        NovaPageStackHost(
            state = state,
            onCloseRequest = { closeRequests++ },
            onShoulder = { side -> state.swapToLibraryPeer(if (side == NovaShoulder.Left) options else system) },
        ) { page ->
            when (page) {
                is LibraryPage.Options -> NovaLibraryOptionsPage(ui = optionsUi(), actions = optionsActions)
                is LibraryPage.System -> NovaLibrarySystemPage(
                    ui = NovaLibrarySystemUi(hostLabel = "Host: 10.0.0.232", status = "Polaris ready", ready = true, mode = "Headless"),
                    actions = systemActions,
                )
                else -> NovaRow(title = "Page ${page.key}", onClick = {}, modifier = Modifier.novaInitialFocus())
            }
        }
    }

    @Test
    fun clearFiltersHandsFocusToTheFilterRowAsItsOwnRowGoes() {
        // On the RP6 the row left with focus still on it: a grey veil and no ring until B.
        filter = NovaLibraryPrimaryFilter.SOURCES
        narrowed = "Steam"
        state.open(options, options.edge)
        val keys = host()
        val clear = rule.activity.getString(R.string.nova_library_filter_clear_all)
        repeat(3) { keys.press(NovaTestKeys.DOWN) }
        rule.onNode(hasText(clear)).assertIsFocused()

        keys.press(NovaTestKeys.A)
        rule.waitForIdle()

        rule.onNode(hasText(clear)).assertDoesNotExist()
        rule.onNode(hasText("Filter")).assertIsFocused()
    }

    @Test
    fun optionsOpensOnTheFilterRowAndLeftAndRightChangeItInPlace() {
        state.open(options, options.edge)
        val keys = host()
        val row = rule.onNode(hasText("Filter"))
        row.assertIsFocused()

        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.LEFT)

        assertEquals(
            listOf(NovaLibraryPrimaryFilter.RECENT, NovaLibraryPrimaryFilter.HDR, NovaLibraryPrimaryFilter.RECENT),
            filterChanges,
        )
        // Focus never leaves the row sideways, and nothing opened: the old drawer sent Right to System.
        rule.onNode(hasText("Filter")).assertIsFocused()
        assertEquals(options.key, state.top?.key)
        assertEquals(1, state.depth)
    }

    @Test
    fun everyOptionsRowRecordsItsRealPlaceWithOrWithoutTheClearRow() {
        // Clear is there only while something narrows the library. The place a row records is
        // where a pop scrolls to before focusing it, so it has to be the row's own either way.
        fun walkDown(keys: NovaTestKeys) {
            val entry = requireNotNull(state.topEntry)
            val walked = mutableSetOf<Any>()
            repeat(12) {
                keys.press(NovaTestKeys.DOWN)
                val key = entry.focusKey ?: return@repeat
                walked += key
                val place = entry.listState.layoutInfo.visibleItemsInfo.first { it.key == key }.index
                assertEquals("$key records its own place", place, entry.focusIndex)
            }
            assertTrue("the walk reached the rows below View: $walked", walked.containsAll(listOf("sort", "search", "refresh")))
        }
        state.open(options, options.edge)
        val keys = host()
        walkDown(keys)

        filter = NovaLibraryPrimaryFilter.SOURCES
        narrowed = "Steam"
        state.open(options, options.edge)
        rule.waitForIdle()
        walkDown(keys)
    }

    @Test
    fun aSourceSetOnItsPageIsTheFilterRowsCurrentValueAndAStepLeavesIt() {
        filter = NovaLibraryPrimaryFilter.SOURCES
        narrowed = "Steam"
        state.open(options, options.edge)
        val keys = host()
        rule.onNode(hasText("Filter"))
            .assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Steam"))

        keys.press(NovaTestKeys.RIGHT)

        assertEquals("a step from the narrowed value goes back to a quick filter", listOf(NovaLibraryPrimaryFilter.ALL), filterChanges)
    }

    @Test
    fun sourcesPushesItsPageAndBReturnsFocusToTheSourcesRow() {
        state.open(options, options.edge)
        val keys = host()

        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText("Sources").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals("sources", state.top?.key)

        keys.back()

        assertEquals(options.key, state.top?.key)
        rule.onNodeWithText("Sources").assertIsFocused()
    }

    @Test
    fun systemOpensOnItsFirstRowAndPushesPolarisSync() {
        state.open(system, system.edge)
        val keys = host()
        rule.onNodeWithText("Switch Host").assertIsFocused()

        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText("Polaris Sync").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)

        assertEquals("polaris", state.top?.key)
        assertTrue("Polaris Sync is a page of the panel, not a screen of its own", opened.isEmpty())
    }

    @Test
    fun onALandscapeHandheldSystemGoesTwoToALineWithTheSamePageBehindEachTile() {
        state.open(system, system.edge)
        val keys = host(compact = true)
        rule.onNodeWithText("Switch Host").assertIsFocused()
        val switchHost = rule.onNodeWithText("Switch Host").getUnclippedBoundsInRoot()
        val settings = rule.onNodeWithText("Settings").getUnclippedBoundsInRoot()
        val sync = rule.onNodeWithText("Polaris Sync").getUnclippedBoundsInRoot()
        assertEquals("Switch Host and Settings share a line", switchHost.top.value, settings.top.value, 0.5f)
        assertTrue(settings.left > switchHost.right)
        assertEquals("Polaris Sync starts the next", switchHost.left.value, sync.left.value, 0.5f)
        assertTrue(sync.top > switchHost.bottom)

        // Line by line: Right to Settings, Down to Manage Server under it, Left to Polaris Sync.
        keys.press(NovaTestKeys.RIGHT)
        rule.onNodeWithText("Settings").assertIsFocused()
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText("Manage Server").assertIsFocused()
        keys.press(NovaTestKeys.LEFT)
        rule.onNodeWithText("Polaris Sync").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals("polaris", state.top?.key)
        assertTrue(opened.isEmpty())

        keys.back()
        rule.onNodeWithText("Polaris Sync").assertIsFocused()
    }

    @Test
    fun shouldersSwapThePeersInOnePanelAtTheirOwnEdges() {
        state.open(options, options.edge)
        val keys = host()
        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.CENTER)
        assertEquals(2, state.depth)

        keys.press(KeyEvent.KEYCODE_BUTTON_R1)

        assertEquals(system.key, state.top?.key)
        assertEquals("a swap leaves no page of the other peer behind", 1, state.depth)
        assertEquals(NovaEdge.End, state.edge)
        rule.onNodeWithText("Switch Host").assertIsFocused()

        keys.press(KeyEvent.KEYCODE_BUTTON_L1)

        assertEquals(options.key, state.top?.key)
        assertEquals(NovaEdge.Start, state.edge)
        rule.onNode(hasText("Filter")).assertIsFocused()
        assertEquals(0, closeRequests)
    }

    @Test
    fun aPeerAlreadyShowingStaysAsItIs() {
        state.open(options, options.edge)
        assertTrue(state.swapToLibraryPeer(options))
        assertEquals(1, state.depth)
        state.close()
        assertEquals("a closed panel is the caller's to open", false, state.swapToLibraryPeer(system))
    }
}

/** An owner page standing in for a page the library builds. */
private class TestLibraryPage(override val key: String) : NovaPage {
    override val title: String = key
}
