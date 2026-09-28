package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guards for game detail and Play Setup.
 *
 * Moved verbatim out of NovaComposeSourceGuardTest, so each migration group owns the guards
 * on its own files.
 */
class NovaGameDetailSourceGuardTest {
    @Test
    fun gameDetailRetroidFirstPaintUsesCompactGameIdentityHeader() {
        val detail = readNovaGameDetail()
        val detailsPanel = detail.section(
            "private fun GameDetailsPanel(",
            "@Composable\ninternal fun LaunchProfilePrimaryNotice("
        )
        assertTrue(
            "Retroid landscape first paint should keep either Hero or Poster identity inside the compact launch header ceiling",
            detailsPanel.contains(".heightIn(min = 136.dp)") &&
                detailsPanel.contains("contentPadding = PaddingValues(12.dp)") &&
                detailsPanel.contains(".height(136.dp)") &&
                detailsPanel.contains(".width(108.dp)") &&
                detailsPanel.contains("fontSize = if (compact) 17.sp else 20.sp") &&
                detailsPanel.contains("lineHeight = if (compact) 19.sp else 22.sp") &&
                detailsPanel.contains("maxLines = if (compact) 1 else 2")
        )
        assertFalse(
            "game detail should not keep the old oversized first-paint panel that pushed launch mode choices below the fold",
            detailsPanel.contains(".heightIn(min = 172.dp)") ||
                detailsPanel.contains(".width(126.dp)") ||
                detailsPanel.contains("fontSize = 22.sp")
        )
        assertTrue(
            "the primary launch and the mode choices stay compact enough for Retroid landscape. " +
                "This used to measure the pinned footer, which the window replaced with the " +
                "action rail; the floor now lives on the rail's own action height, and the choice " +
                "rows stand on the panel's own row height rather than a floor of their own",
            detail.contains("internal val NovaGameDetailActionHeight = 48.dp") &&
                detail.contains("heightIn(min = NovaGameDetailActionHeight)") &&
                detail.contains(".heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))") &&
                !detail.contains("NOVA_DETAIL_ROW_MIN_HEIGHT")
        )
    }

    @Test
    fun gameDetailLaunchControlsPrioritizePrimaryPlayFocus() {
        val detail = readNovaGameDetail()
        val overview = detail.section(
            "internal fun NovaGameDetailOverview(",
            "private fun NovaGameDetailTitle("
        )
        val actions = detail.section(
            "private fun NovaGameDetailActions(",
            "private fun NovaGameDetailAction("
        )

        assertTrue(
            "the primary action holds first focus, and nothing scrolls above it",
            detail.contains("val playFocusRequester = remember { FocusRequester() }") &&
                actions.contains(".focusRequester(playFocusRequester)") &&
                actions.contains("primary = activeSession?.watchOnly != true") &&
                !overview.contains("verticalScroll")
        )
        assertTrue(
            "something has to actually ask. This guard used to pin only the wiring -- the " +
                "requester existed and was attached -- while the requestFocus() call sat in a " +
                "composable that had lost its last caller when this window replaced the bottom " +
                "sheet, so nothing requested focus at all and the d-pad started wherever the " +
                "first focusable happened to be. The ask lives beside the button it names.",
            actions.contains("LaunchedEffect(playFocusable)") &&
                actions.contains("runCatching { playFocusRequester.requestFocus() }")
        )
        assertFalse(
            "and the composable that stranded it is gone rather than left to strand another",
            detail.contains("internal fun NovaGameDetailLaunchFooter(")
        )
        assertTrue(
            "landscape keeps one deterministic action row and portrait groups utilities below it",
            actions.contains("Row(") &&
                actions.contains("if (stacked) {") &&
                actions.contains("Column(") &&
                actions.contains("horizontalArrangement = Arrangement.spacedBy(10.dp)")
        )
        assertTrue(
            "every focusable action clears the accessible target floor",
            detail.contains("internal val NovaGameDetailActionHeight = 48.dp") &&
                detail.contains("heightIn(min = NovaGameDetailActionHeight)") &&
                detail.contains("Modifier.size(NovaGameDetailActionHeight)")
        )
        assertTrue(
            "Launch stays primary; Play Setup and Reset retain labels; Pin and Artwork are " +
                "compact utilities rather than peers competing with launch",
            actions.contains("NovaGameDetailDestination.PLAY_SETUP") &&
                actions.contains("NovaGameDetailDestination.ARTWORK") &&
                actions.contains("iconRes = R.drawable.ic_settings") &&
                actions.contains("iconRes = R.drawable.ic_update") &&
                actions.substringAfter("val pinAction:").substringBefore("val artworkAction:")
                    .contains("iconOnly = true") &&
                actions.substringAfter("val artworkAction:").substringBefore("val showEnd")
                    .contains("iconOnly = true") &&
                actions.contains("onRetryHighFps") &&
                actions.contains("onResetProfile")
        )
        assertTrue(
            "pin visibility and enabled state come from Android's exact shortcut state",
            actions.contains("shortcutPinState != GameShortcutPinState.UNSUPPORTED") &&
                actions.contains("shortcutPinState == GameShortcutPinState.PINNED") &&
                actions.contains("shortcutPinState == GameShortcutPinState.AVAILABLE") &&
                actions.contains("!shortcutPinRequestPending")
        )
        assertTrue(
            "icon-only utilities keep their full spoken label on the parent control",
            detail.contains(".semantics { contentDescription = text }") &&
                detail.contains("contentDescription = null") &&
                actions.contains("nova-game-detail-pin-shortcut") &&
                actions.contains("nova-game-detail-artwork")
        )
        assertFalse(
            "reset must not be gated on a review being open: that left it unreachable in the " +
                "ordinary case once Play Setup stopped carrying its own copy",
            actions.contains("if (reviewExpanded) {\n            if (optimizationState.profileSummary?.showRetryHighFps == true) {")
        )
        assertFalse(
            "nothing gates a rail node any more: what showLaunchModeAction used to remove " +
                "is a row inside Play Setup rather than an action beside it",
            actions.contains("if (showLaunchModeAction)")
        )
        assertFalse(
            "the primary launch button must not sit inside a scrolling body",
            actions.contains("verticalScroll")
        )
    }

    @Test
    fun playSetupKeepsModeChoiceInlineAndHoldsTheProfileControlsToo() {
        val detail = readNovaGameDetail()

        val rowDeclaration = detail.indexOf("internal enum class NovaPlaySetupRow {")
        val rowDeclarationBlock = if (rowDeclaration >= 0) {
            detail.substring(rowDeclaration, detail.indexOf('}', rowDeclaration))
        } else {
            ""
        }
        assertTrue(
            "one destination holds the whole decision in a fixed order per scope: " +
                "where it runs, the resolution, the tuning and how Steam starts for this game; " +
                "the host's default display, deterministic profile and Keep in Step for every " +
                "game. Splitting these across drawers is what made each of them carry half of " +
                "the other's subject",
            rowDeclaration >= 0 &&
                listOf(
                    "WHERE_IT_RUNS",
                    "RESOLUTION",
                    "TUNING",
                    "STEAM_LAUNCH",
                    "HOST_DEFAULT_DISPLAY",
                    "HOST_PROFILE",
                    "HOST_KEEP_IN_STEP",
                ).windowed(2).all { (before, after) ->
                    rowDeclarationBlock.indexOf(before) in 0 until rowDeclarationBlock.indexOf(after)
                } &&
                detail.contains("row = NovaPlaySetupRow.WHERE_IT_RUNS,") &&
                detail.contains("row = NovaPlaySetupRow.RESOLUTION,") &&
                detail.contains("row = NovaPlaySetupRow.TUNING,") &&
                detail.contains("row = NovaPlaySetupRow.STEAM_LAUNCH,") &&
                detail.contains("row = NovaPlaySetupRow.HOST_DEFAULT_DISPLAY,") &&
                detail.contains("row = NovaPlaySetupRow.HOST_PROFILE,") &&
                detail.contains("row = NovaPlaySetupRow.HOST_KEEP_IN_STEP,")
        )
        assertFalse(detail.contains("HOST_AUTO_QUALITY"))
        assertTrue(
            "the strip is a legend for whichever row holds focus, not a picker with a state of " +
                "its own. Three picker states ranked by a when is what made Steam Launch work " +
                "from a fresh panel and go dead once either other row had been touched",
            detail.contains("onExplain = onExplainPlaySetupRow,") &&
                detail.contains("val followsFocus = Modifier.onFocusChanged { if (it.hasFocus) explain(row) }") &&
                detail.contains("it.row == explainedPlaySetupRow") &&
                detail.contains("onAdvance = onAdvancePlaySetupRow,")
        )
        assertFalse(
            "no picker state may come back: each one is a rank in a chain, and a chain needs " +
                "every link to clear its siblings for any link to be reliable",
            detail.contains("launchOptionsState") ||
                detail.contains("profileOptionsState") ||
                detail.contains("steamLaunchOptionsState")
        )
        assertFalse(
            "the comparison strip must not be a focus target. It explains the row under the " +
                "cursor, so stopping on it means stopping on the explanation of the thing you " +
                "just stopped on, and it costs two more presses on every trip down the column",
            playSetupComparison().contains(".focusable(")
        )
        assertFalse(
            "LaunchControls served the destination that no longer exists; leaving it behind " +
                "would leave a second way to draw the same choice",
            detail.contains("private fun LaunchControls(")
        )
    }

    @Test
    fun whereAGameOpensIsDrawnOnceAsItsOwnControl() {
        val content = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt")
        val setup = readSource("src/main/java/com/papi/nova/ui/NovaPlaySetup.kt")
        assertTrue(
            "where a game opens is one control: focusable destination cards above the rows. A Change Space " +
                "row that cycled the places and a legend that restated them as cards drew the same choice " +
                "twice, which is what LaunchControls was removed for",
            setup.contains("internal fun NovaPlaySetupDestinations(") &&
                content.contains("NovaPlaySetupDestinations(") &&
                content.contains("val settingRows = playSetupRows.filter { it.row != NovaPlaySetupRow.PLAY_IN }") &&
                content.contains("settingRows.forEachIndexed { index, rowState ->") &&
                content.contains("settingRows.firstOrNull { it.row == explainedPlaySetupRow }") &&
                !content.contains("playSetupRows.forEachIndexed")
        )
        assertFalse(
            "the destination cards are not the legend: the legend stays a description and never a stop",
            playSetupComparison().contains("NovaPlaySetupDestinations")
        )
        assertTrue(
            "while a destination card holds focus the legend describes the place under the cursor and " +
                "nothing else. Falling back to the first row opened Play Setup on \"If you changed where it " +
                "runs\" with the cursor on Desktop, and no legend at all opened it with the drawer empty. " +
                "The cursor is remembered by the card's name, because the host can add or drop a Space " +
                "while it holds focus and a remembered position then points at the place that moved into it",
            content.contains("onExplainPlaySetupRow(NovaPlaySetupRow.PLAY_IN)") &&
                content.contains("focusedDestination = destinationsRow.options.getOrNull(index)?.label") &&
                content.contains("novaPlaySetupPlaceUnderCursor(") &&
                !content.contains("?: settingRows.firstOrNull()")
        )
        val placeLegend = setup.section(
            "internal fun NovaPlaySetupPlaceLegend(",
            "/** What a legend card says to a screen reader: its name, then what choosing it would mean. */",
        )
        assertFalse(
            "the place legend describes; the card above it is the control, so the legend takes neither a " +
                "tap nor focus, and it is the one place under the cursor rather than the cards restated",
            placeLegend.contains(".clickable(") || placeLegend.contains(".focusable(") ||
                placeLegend.contains("forEach")
        )
    }

    /** Just the strip, so a focusable anywhere else in Play Setup cannot satisfy the check. */
    private fun playSetupComparison(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaPlaySetup.kt").section(
            "internal fun NovaPlaySetupComparison(",
            "/** The resolved plan, as one readable statement plus the facts behind it. */",
        )

    @Test
    fun gameDetailKeepsMangoHudOutOfPrimaryLaunchDrawer() {
        val detail = readNovaGameDetail()
        val sheetContent = detail.section(
            "fun NovaGameDetailContent(",
            "@Composable\nprivate fun NovaDetailPanel("
        )

        assertFalse(
            "MangoHUD should not render as a prominent switch card in the main launch drawer",
            sheetContent.contains("MangoHudCard(")
        )
        assertTrue(
            "when MangoHUD is already enabled, Play Setup shows only a passive status, and it " +
                "comes after the choices rather than competing with them",
            sheetContent.contains("if (mangoHudEnabled) {") &&
                sheetContent.contains("MangoHudPassiveStatus(") &&
                sheetContent.indexOf("NovaPlaySetupBody(") in
                0 until sheetContent.indexOf("MangoHudPassiveStatus(")
        )
    }

    @Test
    fun gameDetailCoverLoadingIsKeyedByGameIdentity() {
        val source = readNovaGameDetail()
        val detailsPanel = source.section(
            "private fun GameDetailsPanel(",
            "@Composable\ninternal fun LaunchProfilePrimaryNotice("
        )

        assertTrue(
            "detail sheet cover view should follow artwork revision changes",
            detailsPanel.contains("key(PolarisApiClient.artworkPresentationKey(game, PolarisGame.ARTWORK_KIND_POSTER))") &&
                detailsPanel.contains("coverLoader(this)")
        )
        assertTrue("logo view should follow artwork revision changes", source.contains("key(logoPresentationKey)"))
        assertTrue(
            "successful artwork mutations should refresh detail state before propagation",
            source.contains("currentGame = currentGame.copy(artwork = manifest)\n            refreshUiState()")
        )
    }

    @Test
    fun gameDetailUsesHeroBackdropLogoTransformIconIdentityAndPosterFallback() {
        val source = readNovaGameDetail()
        val overview = source.section(
            "internal fun NovaGameDetailOverview(",
            "private fun NovaGameDetailTitle("
        )
        val title = source.section(
            "private fun NovaGameDetailTitle(",
            "private fun NovaGameDetailStatusLine("
        )

        assertTrue(
            "the hero should be the full-bleed backdrop rather than a 136dp panel thumbnail, reusing the library's own backdrop so hero-to-poster fallback and the theme scrims come with it",
            overview.contains("NovaLibraryCinematicBackdrop(") &&
                overview.contains("strength = 1f") &&
                !overview.contains(".height(136.dp)")
        )
        assertTrue(
            "curated logo artwork should become the title treatment at real size, still keyed by presentation revision",
            title.contains("if (logoAvailable)") &&
                title.contains("key(logoPresentationKey)") &&
                title.contains("logoLoader(this)") &&
                title.contains("maxWidth = 200.dp, maxHeight = 64.dp")
        )
        assertTrue(
            "a game with no curated logo falls back to its name, and the fallback is a title rather than a poster card",
            title.contains("text = game.name") &&
                title.contains("nova-game-detail-title")
        )
    }

    @Test
    fun playSetupPlanDescribesThePlaceChosenForTheGame() {
        val activity = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt")
        val intro = activity.section("private fun buildLaunchIntro(", "private fun lastPlayedText(")
        // Widened in 1.4.13: an entry that does not follow the host default is the same case with
        // nobody choosing, and under a Mirror Desktop headline the host's sentence about creating a
        // virtual output is the opposite of what happens.
        val chosen = intro.indexOf("uiState.hasExplicitOverride || !uiState.followsHostDefault ->")
        val hostReason = intro.indexOf("uiState.launchChoice.hostModeReason.isNotBlank() ->")
        assertTrue(
            "picked Host Virtual for Control, Play Setup still said the game ran in a private labwc compositor: a place chosen for the game speaks before the host's reason for its own default",
            chosen in 0 until hostReason &&
                intro.contains("playSetupModeDetails()[PolarisStreamDisplayMode.normalize(uiState.playMode)]")
        )
    }

    @Test
    fun gamePageNamesThePlaceThisLaunchRuns() {
        val overview = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt")
        val status = overview.section("private fun novaGameDetailStatusText(", "@Composable")
        assertTrue(
            "picked Host Virtual for Control, the game page still read Private Stream: the line names the launch's own mode, not the host's default",
            status.contains("PolarisStreamDisplayMode.labelForMode(uiState.playMode)") &&
                !status.contains("uiState.hostStreamDisplayModeLabel")
        )
    }

    private fun readNovaLibraryActivity(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")

    private fun readNovaGameDetail(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaPlaySetup.kt") +
        readSource("src/main/java/com/papi/nova/ui/NovaPlaySetupHostScope.kt") +
        readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")

    @Test
    fun playSetupLaunchSettingsRowReadsActionableModeState() {
        val detail = readNovaGameDetail()

        // The state this asked of LaunchControls is now asked of the row that replaced
        // it: what the launch settings offer is derived, never assumed enabled.
        assertTrue(detail.contains("uiState.showLaunchOptionsButton"))
        assertFalse(detail.contains("uiState.launchOptionsEnabled"))
    }

    @Test
    fun gameDetailLaunchOptionsAvoidRawAppCompatAlertDialogButtons() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")
        val planner = detail.section(
            "private fun resolutionPlanner(",
            "private fun optionLabel("
        )

        assertTrue(
            "the resolution is chosen without a dialog and without a sheet, for the same " +
                "reason as the tuning: a glass panel over the destination is still a modal on " +
                "the launch path. It is a row with a value now rather than a list of items " +
                "that each started the stream on the way past",
            !planner.contains("AlertDialog.Builder(") &&
                !detail.contains("private fun NovaLaunchOptionsSheet(") &&
                detail.contains("row = NovaPlaySetupRow.RESOLUTION,") &&
                detail.contains("onSelect = { chooseResolution(choice) },")
        )
    }

    @Test
    fun gameDetailProfilePreferenceAvoidsRawAppCompatAlertDialogButtons() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")
        assertTrue(
            "the tuning preference is chosen without a dialog and without a sheet. It used " +
                "to be routed through a Nova glass panel raised over the destination, which " +
                "was better than an AlertDialog and still a modal on the launch path; the " +
                "options are stated as consequences in the comparison strip now",
            !detail.contains("AlertDialog.Builder(") &&
                !detail.contains("private fun NovaProfilePreferenceSheet(") &&
                detail.contains("row = NovaPlaySetupRow.TUNING,") &&
                detail.contains("consequence = getString(novaProfilePreferenceConsequenceRes(value)),")
        )
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing start marker: $startMarker" }
        val end = indexOf(endMarker, start)
        require(end >= 0) { "Missing end marker: $endMarker" }
        return substring(start, end)
    }

    @Test
    fun gameDetailGaugeMeasuresPlayedAgainstTheLongestEstimate() {
        val gauge = readNovaGameDetail().section(
            "private fun NovaGameDetailBeatGauge(",
            "/**\n * Whether two titles are the same game"
        )

        assertTrue(
            "the bar's full width is the completionist figure, falling back to whatever is actually known",
            gauge.contains("beatTime?.longestSeconds?.takeIf { it > 0 }")
        )
        assertTrue(
            "played past the end caps the bar, because a bar cannot say more than full",
            gauge.contains("(playedSeconds.toFloat() / fullWidthSeconds.toFloat()).coerceIn(0f, 1f)")
        )
        assertFalse(
            "the played figure is not clamped with the bar: the hours someone actually spent " +
                "stay true past the end of an estimate",
            gauge.contains("playedSeconds.coerceAtMost")
        )
    }

    @Test
    fun gameDetailGaugeCutsItsNotchesThroughTheBarRatherThanOntoIt() {
        val gauge = readNovaGameDetail().section(
            "private fun NovaGameDetailBeatGauge(",
            "/**\n * Whether two titles are the same game"
        )

        // Overhang top and bottom is the whole difference between a notch in the bar and
        // a mark sitting on it, and it is visible only in the drawing, never in the prose.
        assertTrue(
            "the canvas is taller than the bar so the notches can overhang it",
            gauge.contains(".height(NOVA_GAUGE_BAR + NOVA_GAUGE_NOTCH_OVERHANG * 2)") &&
                gauge.contains("val barTop = NOVA_GAUGE_NOTCH_OVERHANG.toPx()")
        )
        assertTrue(
            "a notch is drawn in the ground colour over a lighter ring, which is what reads as a cut",
            gauge.contains("val notchInk = colors.window") &&
                gauge.contains("val notchRing = colors.textPrimary.copy(alpha = 0.34f)")
        )
        assertTrue(
            "a notch at or past the full width would sit on the end cap and say nothing",
            gauge.contains("it > 0 && it < fullWidthSeconds")
        )
    }

    @Test
    fun gameDetailGaugeSaysWhichEstimateIsWhich() {
        val gauge = readNovaGameDetail().section(
            "private fun NovaGameDetailBeatGauge(",
            "/**\n * Whether two titles are the same game"
        )

        assertTrue(
            "three bare numbers do not say which is the main story and which is everything",
            gauge.contains("R.string.nova_game_detail_beat_main") &&
                gauge.contains("R.string.nova_game_detail_beat_extras") &&
                gauge.contains("R.string.nova_game_detail_beat_complete")
        )
        assertTrue(
            "the played figure leads and the estimates follow it, so the row has a reading order",
            gauge.indexOf("color = colors.textPrimary,") in
                0 until gauge.indexOf("color = colors.textSecondary,")
        )
    }

    @Test
    fun gameDetailGaugeDrawsNothingItCannotBack() {
        val gauge = readNovaGameDetail().section(
            "private fun NovaGameDetailBeatGauge(",
            "/**\n * Whether two titles are the same game"
        )

        assertTrue(
            "with neither a duration nor an estimate the block is absent entirely",
            gauge.contains("if (playedSeconds <= 0L && fullWidthSeconds <= 0L)")
        )
        assertTrue(
            "an estimate with nothing played reads Not started rather than zero hours",
            gauge.contains("R.string.nova_game_detail_not_started")
        )
        assertTrue(
            "the bar only appears once there is something to measure against",
            gauge.contains("if (fullWidthSeconds > 0L) {")
        )
    }

    @Test
    fun gameDetailLandscapeActionsFitA43Handheld() {
        val actions = readNovaGameDetail().section("private fun NovaGameDetailActions(", "/**\n * How long this has been played")
        // The landscape branch is the one whose else sits at the function body indent.
        val landscape = actions.substringAfter("\n    } else {\n")
        val narrow = landscape
            .substringAfter("if (maxWidth < NOVA_GAME_DETAIL_ONE_ROW_MIN_WIDTH) {")
            .substringBefore("\n            } else {\n")
        val artwork = narrow.indexOf("artworkAction(")
        val setup = narrow.indexOf("playSetupAction(")
        assertTrue(
            "on the 4:3 Retroid Pocket Nova one row clipped Artwork past the right edge, and wrapping " +
                "left it alone on a second line; narrow screens keep the quick icons beside Launch and " +
                "move the setup actions to the row below",
            narrow.indexOf("primaryAction(") in 0 until artwork && artwork < setup,
        )
    }

    @Test
    fun gameDetailGaugeHandsDownToLaunchNotWhateverSitsUnderIt() {
        val detail = readNovaGameDetail()
        val gauge = detail.section("private fun NovaGameDetailBeatGauge(", "/**\n * Whether two titles are the same game")
        assertTrue(
            "the estimate chip sits right of Launch, so the default search sent Down to Play Setup " +
                "and Up came back; Down must name the next control",
            gauge.contains("down = if (showCorrection) correctionFocus else exitDown") &&
                gauge.contains(".focusProperties { down = exitDown }"),
        )
        assertFalse(
            "clickable already makes the control a focus target; a nested .focusable() is a second " +
                "target the Down redirect does not cover",
            gauge.contains(".focusable()"),
        )
        assertTrue(
            "a Launch that cannot act holds no focus, so Down falls back to the ordinary search " +
                "instead of dead-ending",
            detail.contains("FocusRequester.Default"),
        )
    }

    @Test
    fun gameDetailGaugeSurfacesAWrongMatchAndLeadsToItsFix() {
        val detail = readNovaGameDetail()
        val gauge = detail.section(
            "private fun NovaGameDetailBeatGauge(",
            "/**\n * Whether two titles are the same game"
        )

        assertTrue(
            "a fuzzy match that went wrong looks exactly like one that went right, so the name " +
                "it found is shown when it is not plainly the same game",
            gauge.contains("novaSameTitle(matched, gameName).not()") &&
                gauge.contains("R.string.nova_game_detail_matched_as")
        )
        assertTrue(
            "punctuation and case disagree constantly between a launcher and a catalogue, and " +
                "saying so every time would bury the mismatches that matter",
            detail.contains("private fun novaSameTitle(") &&
                detail.contains("value.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) }")
        )
        assertTrue(
            "seeing the mismatch is half of it: the line is the way to the studio that fixes the identity",
            gauge.contains("onCorrectMatch") &&
                detail.contains("onCorrectMatch = { onDestination(NovaGameDetailDestination.ARTWORK) }")
        )
    }

    @Test
    fun hostOnlyDisplayModeUsesTheExistingScopedManagementHandoff() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt")
        val library = readNovaLibraryActivity()
        val picker = readSource("src/main/java/com/papi/nova/ui/NovaPlaySetupModePicker.kt")

        assertTrue(
            "Headless Dongle must remain host-only even when an older host omits the flag",
            picker.contains("normalizedMode == PolarisClientSettings.MODE_HEADLESS_DONGLE") &&
                picker.contains("hostDefaultOnly = hostDefaultOnly")
        )
        assertTrue(
            "the host-only card should ask the library to open host settings, not select a launch mode",
            detail.contains("EXTRA_RESULT_MANAGE_SERVER") &&
                detail.contains("onConfigureHost = { finishWithManageServerRequest() }") &&
                library.contains("openServerDisplaySettings()")
        )
        assertTrue(
            "the handoff should land on the host Audio/Video settings section",
            library.contains("/#/config#av")
        )
    }

    @Test
    fun emulatorSourceIsLabelledInEveryLegacyMap() {
        listOf(
            "src/main/java/com/papi/nova/nvstream/http/NvApp.kt",
            "src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt",
            "src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt",
        ).forEach { path ->
            assertTrue("$path must label the emulator source", readSource(path).contains("\"emulator\" -> \"Emulator\""))
        }
        val legacyApp = readSource("src/main/java/com/papi/nova/nvstream/http/NvApp.kt")
        assertTrue("the legacy grid must prefer the host's platform label", legacyApp.contains("platformLabelFromServer.ifBlank"))
        assertTrue("the legacy grid must prefer the host's runtime label", legacyApp.contains("runtimeLabelFromServer.ifBlank"))
    }

    @Test
    fun perGameFaceButtonLayoutReachesTheLaunchBeforeThePadsAreBuilt() {
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val applied = game.indexOf("NovaFaceButtonLayoutOverrides.flipFaceButtons(")
        val handler = game.indexOf("controllerHandler = ControllerHandler(")
        assertTrue("Game must apply the per-game face button layout", applied >= 0)
        assertTrue("Game must build ControllerHandler after applying the layout", handler > applied)
        assertTrue(readSource("src/main/java/com/papi/nova/utils/ServerHelper.kt").contains("Game.EXTRA_FACE_BUTTON_LAYOUT"))
        assertTrue(readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").contains("row = NovaPlaySetupRow.FACE_BUTTONS,"))
        assertTrue(readSource("src/main/java/com/papi/nova/ShortcutTrampoline.kt").contains("faceButtonLayout = readyLaunchPlan.faceButtonLayout"))
    }
}
