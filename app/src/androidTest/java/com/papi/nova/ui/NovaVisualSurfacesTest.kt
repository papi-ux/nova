package com.papi.nova.ui

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import org.junit.Assume
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisArtworkChoice
import com.papi.nova.api.PolarisArtworkMatchCandidate
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaFormFactor
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.panel.NovaPanelState
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The surfaces this pass changed, drawn on a real display at its real size and font scale, walked
 * for text that is cut, ellipsized or pushed past its box (R13), and saved as screenshots for the
 * two reviewers of the visual gate (spec 9.4). Run it once per emulator and font scale; pass
 * `-e novaVisualTag <name>` to name the run's folder under the app's files, nova-visual/<name>.
 *
 * Every finding is written beside the screenshots. The test fails on text that overflows or ends
 * in an ellipsis; text a scroll or the screen cuts at rest is reported for the reviewers, because
 * a list that scrolls may rightly run past its edge below the row in view.
 */
@RunWith(AndroidJUnit4::class)
class NovaVisualSurfacesTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val findings = mutableListOf<String>()

    private val plan = NovaPlaySetupPlan(
        mode = "Private Stream on a Virtual Display",
        lines = listOf(
            "2560x1440 at 120 FPS, HEVC Main 10, 80 Mbps",
            "Nothing outside this game changes: the host's own screens stay as they are.",
        ),
        facts = listOf(
            NovaPlaySetupFact(key = "Last session", value = "Smooth, 119 FPS held for 2 hr 14 min", tone = NovaPlaySetupTone.GOOD),
            NovaPlaySetupFact(
                key = "Limited by",
                value = "Host Render",
                detail = "Frames left the GPU late in 6% of the last session; the network and the decoder were clear.",
                tone = NovaPlaySetupTone.WARN,
            ),
            NovaPlaySetupFact(key = "Asked / granted", value = "2560x1440 at 120", detail = "Granted: 2560x1440 at 120 · Held by History Safe Profile"),
            NovaPlaySetupFact(key = "Host default", value = "Virtual Display", detail = "This game follows the host's Default Display."),
        ),
    )

    private val resolutions = listOf(
        NovaPlaySetupOption("1280x720", "Lightest on the network and the battery; soft on a large screen.", onSelect = {}),
        NovaPlaySetupOption("1920x1080", "Sharp on this screen at about 40 Mbps.", current = true, onSelect = {}),
        NovaPlaySetupOption("2560x1440", "Sharpest; the host renders more, so the frame rate may fall back.", onSelect = {}),
    )

    private val modes = listOf(
        "Headless", "Virtual Display", "Mirror Desktop", "Primary Display", "KMS Capture", "Private Space", "Desktop",
    ).mapIndexed { index, label ->
        NovaPlaySetupOption(
            label = label,
            consequence = "What $label does to the host's screens, said whole in one sentence of ordinary length.",
            current = index == 1,
            onSelect = {},
        )
    }

    private fun row(row: NovaPlaySetupRow, label: String, caption: String, options: List<NovaPlaySetupOption>, perRow: Int = Int.MAX_VALUE) =
        NovaPlaySetupRowState(
            row = row,
            label = label,
            caption = caption,
            value = options.firstOrNull { it.current }?.label.orEmpty(),
            stripTitle = "If you changed $label",
            options = options,
            optionsPerRow = perRow,
        )

    private val rows = listOf(
        row(NovaPlaySetupRow.RESOLUTION, "Resolution", "Chosen here", resolutions),
        row(NovaPlaySetupRow.FRAME_RATE, "Frame Rate", "Chosen here", listOf("30", "60", "120").mapIndexed { i, fps ->
            NovaPlaySetupOption("$fps FPS", "Paced to $fps frames a second.", current = i == 2, onSelect = {})
        }),
        row(NovaPlaySetupRow.VIDEO_CODEC, "Video Codec", "Set for this game", listOf("H.264", "HEVC", "AV1", "PyroWave").mapIndexed { i, codec ->
            NovaPlaySetupOption(codec, "Decoded by this device's $codec path.", current = i == 1, onSelect = {})
        }),
        row(NovaPlaySetupRow.FACE_BUTTONS, "Face Buttons", "As printed", listOf(
            NovaPlaySetupOption("As printed", "A is A.", current = true, onSelect = {}),
            NovaPlaySetupOption("Swapped", "A and B trade places, for Nintendo layouts.", onSelect = {}),
        )),
    )

    private val hostRows = listOf(
        row(NovaPlaySetupRow.HOST_DEFAULT_DISPLAY, "Default Display", "Every game without its own choice", modes, perRow = 3),
        row(NovaPlaySetupRow.HOST_SCREEN_SCALE, "Screen Scale", "Matches this device", listOf("100%", "125%", "150%").mapIndexed { i, s ->
            NovaPlaySetupOption(s, "The added screen at $s.", current = i == 0, onSelect = {})
        }),
    )

    private val places = listOf(
        NovaPlaySetupOption("Desktop", "Uses this computer's usual games and settings.", current = true, onSelect = {}),
        NovaPlaySetupOption("Living Room Television", "Not installed there yet. Change Space to install it.", onSelect = {}),
        NovaPlaySetupOption("Handheld", "Installed. Opens in its own session.", onSelect = {}),
    )

    @Test
    fun playSetupThisGame() {
        playSetup("play-setup-game", rows, places)
    }

    @Test
    fun playSetupEveryGame() {
        playSetup("play-setup-host", hostRows)
    }

    @Test
    fun playSetupPlanPage() {
        val panel = NovaPanelState()
        panel.open(PlaySetupPage.Plan("What will happen", plan))
        compose.setContent {
            Screen {
                NovaPlaySetupPanel(panel = panel, onClose = {}) { page ->
                    if (page is PlaySetupPage.Plan) NovaPlaySetupPlanPage(page)
                }
            }
        }
        check("play-setup-plan")
    }

    private fun playSetup(surface: String, shown: List<NovaPlaySetupRowState>, destinations: List<NovaPlaySetupOption> = emptyList()) {
        val panel = NovaPanelState()
        panel.open(PlaySetupPage.Root("Play Setup"))
        compose.setContent {
            Screen {
                var explained by remember { mutableStateOf(shown.first().row) }
                NovaPlaySetupPanel(panel = panel, onClose = {}) { page ->
                    when (page) {
                        is PlaySetupPage.Plan -> NovaPlaySetupPlanPage(page)
                        else -> Column(Modifier.fillMaxWidth()) {
                            NovaPlaySetupScopeRow(scope = NovaPlaySetupScope.THIS_GAME, onSelected = {})
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val bodyHeight = maxHeight
                                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    NovaPlaySetupBody(
                                        plan = plan,
                                        fitHeight = bodyHeight,
                                        onOpenPlan = { panel.push(PlaySetupPage.Plan("What will happen", it)) },
                                        rows = {
                                            if (destinations.isNotEmpty()) {
                                                NovaPlaySetupDestinations(title = "Where this game opens", status = "", options = destinations)
                                            }
                                            shown.forEachIndexed { index, state ->
                                                NovaPlaySetupSettingRow(
                                                    state = state,
                                                    onExplain = { explained = it },
                                                    onAdvance = {},
                                                    modifier = if (index == 0) Modifier.novaInitialFocus() else Modifier,
                                                )
                                            }
                                        },
                                        comparison = { form ->
                                            val state = shown.first { it.row == explained }
                                            NovaPlaySetupComparison(state.stripTitle, state.options, form, state.optionsPerRow)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        check(surface)
    }

    @Test
    fun artworkStudio() {
        val candidate = PolarisArtworkMatchCandidate(
            provider = "steamgriddb",
            providerGameId = "1",
            title = "Control Ultimate Edition: The Foundation and AWE Expansions",
            releaseYear = 2020,
        )
        val choices = (1..7).map { PolarisArtworkChoice(NovaArtworkKinds.POSTER, "token-$it", "", 0L) }
        val state = NovaArtworkStudioState(
            selectedCandidate = candidate,
            currentMatchTitle = "Control Ultimate Edition: The Foundation and AWE Expansions",
            currentMatchSource = "SteamGridDB",
            activeKind = NovaArtworkKinds.POSTER,
            choicesByKind = mapOf(NovaArtworkKinds.POSTER to choices),
            loadedKinds = setOf(NovaArtworkKinds.POSTER),
            selections = mapOf(NovaArtworkKinds.POSTER to choices[1]),
        )
        compose.setContent {
            Screen {
                BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    val height = maxHeight
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NovaArtworkStudio(
                            state = state,
                            initialQuery = "Control",
                            onRefresh = {},
                            onSearch = {},
                            onIdentitySelected = {},
                            onChangeIdentity = {},
                            onKindSelected = {},
                            onChoiceSelected = {},
                            onReset = {},
                            onApply = { _, _ -> },
                            onCancel = {},
                            onClear = {},
                            onTransform = { _, _, _ -> },
                            candidatePreviewLoader = { _, _ -> },
                            choicePreviewLoader = { _, _ -> },
                            currentArtworkPresentationKey = { "" },
                            currentArtworkLoader = { _, _ -> },
                            initiallyExpanded = true,
                            fillsDestination = true,
                            fitHeight = height,
                        )
                    }
                }
            }
        }
        check("artwork-studio")
    }

    private val hero = NovaLibraryHeroState(
        game = null,
        title = "Control Ultimate Edition",
        subtitle = "Running on pc-papi in a Virtual Display",
        caption = "Resume this stream, or end it if the host game is stale.",
        eyebrow = "Resume your stream",
        actionLabel = "Resume Stream",
        badges = listOf("HDR", "120 FPS", "Virtual Display", "HEVC"),
        reason = NovaLibraryHeroReason.ACTIVE_SESSION,
        primaryAction = NovaLibraryHeroPrimaryAction.RESUME,
        supportingLine = "",
        artworkFallbackTitle = "Control Ultimate Edition",
        artworkFallbackSubtitle = "Remedy",
        secondaryActionLabel = "End Session",
        secondaryAction = NovaLibraryHeroSecondaryAction.END_SESSION,
    )

    @Test
    fun libraryHero() {
        compose.setContent {
            Screen {
                // The home hero stands above the grid in portrait; on a landscape display this is how
                // a tablet in portrait would draw it, and on a phone how the phone does.
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    NovaLibraryHeroCard(hero = hero, compact = false, apiClient = PolarisApiClient(context, ""), onPrimaryAction = {}, onSecondaryAction = {}, onGameFocused = {})
                }
            }
        }
        check("library-hero")
    }

    @Test
    fun libraryStrip() {
        // The strip is the landscape library's; a portrait library draws the home hero instead.
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        if (bounds.width() < bounds.height()) return
        compose.setContent {
            Screen {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    NovaLibraryLandscapeShowcaseStripContent(
                        hostLabel = "pc-papi.lan",
                        polarisReady = true,
                        onOpenOptions = {},
                        onOpenSystemMenu = {},
                        continueCard = hero.topBarContinue(),
                        continueSlot = { fit ->
                            NovaLibraryStripContinue(hero = hero, apiClient = PolarisApiClient(context, ""), fit = fit, onPrimaryAction = {}, onSecondaryAction = {})
                        },
                    )
                    val short = hero.copy(title = "Animal Well")
                    Box(Modifier.padding(top = 12.dp)) {
                        NovaLibraryLandscapeShowcaseStripContent(
                            hostLabel = "pc-papi.lan",
                            polarisReady = true,
                            onOpenOptions = {},
                            onOpenSystemMenu = {},
                            continueCard = short.topBarContinue(),
                            continueSlot = { fit ->
                                NovaLibraryStripContinue(hero = short, apiClient = PolarisApiClient(context, ""), fit = fit, onPrimaryAction = {}, onSecondaryAction = {})
                            },
                        )
                    }
                }
            }
        }
        check("library-strip")
    }

    @Test
    fun companionDeck() {
        compose.setContent {
            Screen {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        NovaCompanionCommandDeckView(context) { }.apply {
                            render(
                                NovaCompanionCommandDeckState.from(
                                    hud = NovaHudUiState.preview(NovaHudMode.DEBUG),
                                    sessionState = "streaming",
                                    displayRole = "Companion",
                                    unavailableLabel = "Unavailable",
                                ).withActionSelections(
                                    androidKeyboardVisible = true,
                                    novaKeyboardVisible = false,
                                    novaHudVisible = true,
                                    zoomPanEnabled = false,
                                ),
                            )
                        }
                    },
                )
            }
        }
        // The deck is Views; the screenshot is what the reviewers read.
        check("companion-deck")
    }

    /**
     * The deck where it lives, on a second display: the Thor's lower screen, or an emulator given
     * one. Skipped where there is no second display. The shot is the second display's own,
     * taken with screencap into /data/local/tmp for the host to pull.
     */
    @Test
    fun companionDeckOnTheSecondDisplay() {
        val second = context.getSystemService(DisplayManager::class.java).displays
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        Assume.assumeTrue("no second display", second != null)
        val intent = Intent(context, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(second!!.displayId).toBundle()
        ActivityScenario.launch<ComponentActivity>(intent, options).use { scenario ->
            scenario.onActivity { activity ->
                val deck = NovaCompanionCommandDeckView(activity, composeOwner = activity) { }
                activity.setContentView(deck)
                deck.render(
                    NovaCompanionCommandDeckState.from(
                        hud = NovaHudUiState.preview(NovaHudMode.DEBUG),
                        sessionState = "streaming",
                        displayRole = "Companion",
                        unavailableLabel = "Unavailable",
                    ).withActionSelections(
                        androidKeyboardVisible = true,
                        novaKeyboardVisible = false,
                        novaHudVisible = true,
                        zoomPanEnabled = false,
                    ),
                )
            }
            compose.waitForIdle()
            Thread.sleep(SCREENSHOT_SETTLE_MS)
            val surface = "companion-deck-display2"
            walk(surface)
            val physical = secondPhysicalDisplay()
            val tag = folder().name
            if (physical != null) shell("screencap -d $physical -p /data/local/tmp/nova-visual-$tag-$surface.png")
            // Held on screen a while on request, for a host that shoots the display from outside.
            InstrumentationRegistry.getArguments().getString("novaHoldSeconds")?.toLongOrNull()?.let { Thread.sleep(it * 1_000) }
            report(surface)
            val broken = findings.filter { "overflows" in it || "ellipsized" in it || "inside a word" in it }
            assertTrue("text cut on $surface:\n" + broken.joinToString("\n"), broken.isEmpty())
        }
    }

    /** SurfaceFlinger's id for the second display, which screencap takes: the second one it lists. */
    private fun secondPhysicalDisplay(): String? =
        Regex("""Display (\d+)""").findAll(shell("dumpsys SurfaceFlinger --display-id")).map { it.groupValues[1] }.drop(1).firstOrNull()

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    /**
     * The window's ground under a surface, as a screen draws it. `-e novaFormFactor tv` draws it
     * for a television, for an emulator whose display is sized as one but is not one.
     */
    @Composable
    private fun Screen(content: @Composable () -> Unit) {
        val television = InstrumentationRegistry.getArguments().getString("novaFormFactor") == "tv"
        NovaComposeTheme {
            CompositionLocalProvider(
                LocalNovaFormFactor provides if (television) NovaFormFactor.Television else LocalNovaFormFactor.current,
            ) {
                Box(Modifier.fillMaxSize().background(LocalNovaComposeColors.current.window)) { content() }
            }
        }
    }

    private fun check(surface: String) {
        compose.waitForIdle()
        // Let a panel's slide and a split's motion land before anything is read.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        walk(surface)
        save(surface)
        report(surface)
        val broken = findings.filter { "overflows" in it || "ellipsized" in it || "inside a word" in it }
        assertTrue("text cut on $surface:\n" + broken.joinToString("\n"), broken.isEmpty())
    }

    private fun walk(surface: String) {
        // A View screen can hold more than one Compose root, as the deck's End tile is.
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        fun visit(node: SemanticsNode) {
            if (node.layoutInfo.isPlaced) {
                val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                val layout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
                if (!text.isNullOrBlank() && layout != null) {
                    val results = mutableListOf<TextLayoutResult>()
                    layout.action?.invoke(results)
                    results.forEach { result ->
                        // Cut to a count of lines, or taller than the box it was given.
                        if (result.multiParagraph.didExceedMaxLines || result.multiParagraph.height > result.size.height + 1f) {
                            findings += "$surface: \"$text\" overflows its box"
                        }
                        val laid = result.layoutInput.text.text
                        for (line in 0 until result.lineCount) {
                            if (result.isLineEllipsized(line)) findings += "$surface: \"$text\" is ellipsized"
                            // A line that ends between two letters broke a word in two.
                            val end = result.getLineEnd(line)
                            if (line < result.lineCount - 1 && end in 1 until laid.length &&
                                laid[end - 1].isLetterOrDigit() && laid[end].isLetterOrDigit()
                            ) {
                                findings += "$surface: \"$text\" breaks inside a word"
                            }
                        }
                    }
                    val shown = node.boundsInRoot
                    val whole = Rect(node.positionInRoot, androidx.compose.ui.geometry.Size(node.size.width.toFloat(), node.size.height.toFloat()))
                    val shownArea = shown.width * shown.height
                    val wholeArea = whole.width * whole.height
                    if (shownArea > 0f && shownArea < wholeArea - 2f) {
                        findings += "$surface: \"$text\" is partly cut at rest (shown $shown of $whole)"
                    }
                }
            }
            node.children.forEach(::visit)
        }
        roots.forEach(::visit)
    }

    private fun folder(): File {
        val arguments = InstrumentationRegistry.getArguments()
        val configuration = context.resources.configuration
        val tag = arguments.getString("novaVisualTag")
            ?: "${configuration.screenWidthDp}x${configuration.screenHeightDp}dp-fs${configuration.fontScale}"
        return File(context.getExternalFilesDir(null), "nova-visual/$tag").apply { mkdirs() }
    }

    private fun save(surface: String) {
        // The whole display, as a person sees it, Views and every Compose root together, once the
        // window's own entrance has finished on the real clock.
        Thread.sleep(SCREENSHOT_SETTLE_MS)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: compose.onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap()
        File(folder(), "$surface.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val SCREENSHOT_SETTLE_MS = 1_200L
    }

    private fun report(surface: String) {
        File(folder(), "$surface.txt").writeText(
            findings.filter { it.startsWith("$surface:") }.joinToString("\n").ifEmpty { "$surface: nothing cut" } + "\n",
        )
    }
}
