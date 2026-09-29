package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCommonPagesComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val state = NovaPanelState()
    private var closeRequests = 0

    private fun host(): NovaTestKeys = rule.setPanelContent {
        NovaPageStackHost(state = state, onCloseRequest = { closeRequests++ }) { page ->
            NovaRow(title = "Owner ${page.key}", onClick = {}, modifier = Modifier.novaInitialFocus())
        }
    }

    @Test
    fun choiceOpensOnTheCurrentOptionAndOneAPicksAndPops() {
        var chosen: Int? = null
        state.open(TestPage("root"))
        state.push(
            NovaCommonPage.Choice(
                key = "fps",
                title = "Frame rate",
                options = (0 until 40).map { NovaOption(it, "Option $it") },
                current = 25,
                onChoose = { chosen = it },
            ),
        )
        val keys = host()
        rule.onNodeWithText("Option 25").assertIsFocused()
        rule.onNodeWithText("Option 25").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))

        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.CENTER)

        assertEquals(26, chosen)
        assertEquals(1, state.depth)
    }

    @Test
    fun choiceFirstOpensOnACurrentOptionFarPastTheViewportAndAAppliesIt() {
        var chosen: Int? = null
        state.open(TestPage("root"))
        state.push(
            NovaCommonPage.Choice(
                key = "resolution",
                title = "Resolution",
                options = (0 until 60).map { NovaOption(it, "Option $it") },
                current = 52,
                onChoose = { chosen = it },
            ),
        )
        val keys = host()

        rule.onNodeWithText("Option 52").assertIsDisplayed().assertIsFocused()
        rule.onNodeWithText("Option 51").assertIsDisplayed()

        keys.press(NovaTestKeys.CENTER)
        assertEquals("A applies the current option it opened on", 52, chosen)
        assertEquals(1, state.depth)
    }

    @Test
    fun aDisabledCurrentOptionFarDownStillTakesFocusToShowWhy() {
        var chosen: Int? = null
        state.open(TestPage("root"))
        state.push(
            NovaCommonPage.Choice(
                key = "resolution",
                title = "Resolution",
                options = (0 until 60).map { NovaOption(it, "Option $it", disabledReason = if (it == 45) "Too big for this device" else null) },
                current = 45,
                onChoose = { chosen = it },
            ),
        )
        val keys = host()

        rule.onNodeWithText("Option 45", substring = true).assertIsDisplayed().assertIsFocused()
        rule.onNodeWithText("Too big for this device", substring = true).assertIsDisplayed()
        keys.press(NovaTestKeys.CENTER)
        assertEquals("a disabled option swallows A", null, chosen)
    }

    @Test
    fun aCurrentValueThatIsNoLongerAnOptionOpensOnTheFirstRow() {
        state.open(TestPage("root"))
        state.push(
            NovaCommonPage.Choice(
                key = "resolution",
                title = "Resolution",
                options = (0 until 60).map { NovaOption(it, "Option $it") },
                current = 99,
                onChoose = {},
            ),
        )
        host()
        rule.onNodeWithText("Option 0").assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun twoPressesInOneFramePickOnceAndPopOnePage() {
        val chosen = mutableListOf<Int>()
        state.open(TestPage("root"))
        state.push(TestPage("menu"))
        state.push(
            NovaCommonPage.Choice(
                key = "fps",
                title = "Frame rate",
                options = (0 until 4).map { NovaOption(it, "Option $it") },
                current = 1,
                onChoose = { chosen += it },
            ),
        )
        val keys = host()
        rule.onNodeWithText("Option 1").assertIsFocused()

        keys.pressTwiceInOneFrame(NovaTestKeys.CENTER)

        assertEquals("the second press landed on a page already leaving", listOf(1), chosen)
        assertEquals("menu", state.top?.key)
        assertEquals(0, closeRequests)
    }

    @Test
    fun twoBacksInOneFrameCloseANoticeOnce() {
        var closed = 0
        state.open(TestPage("root"))
        state.push(TestPage("menu"))
        state.push(NovaCommonPage.Notice(key = "details", title = "Details", message = "name: Nova PC", closeLabel = "Close", onClose = { closed++ }))
        val keys = host()

        keys.backTwiceInOneFrame()

        assertEquals(1, closed)
        assertEquals("menu", state.top?.key)
    }

    @Test
    fun confirmOpensOnStayAndBRunsStay() {
        var stayed = 0
        var confirmed = 0
        state.open(TestPage("root"))
        state.push(
            NovaCommonPage.Confirm(
                key = "quit",
                title = "Quit",
                message = AnnotatedString("Quit the running app?"),
                stayLabel = "Stay",
                actionLabel = "Quit",
                destructive = true,
                onConfirm = { confirmed++ },
                onStay = { stayed++ },
            ),
        )
        val keys = host()
        rule.onNodeWithText("Stay").assertIsFocused()
        keys.back()
        assertEquals(1, stayed)
        assertEquals(0, confirmed)
        assertEquals(1, state.depth)
    }

    @Test
    fun theHeaderLeavesAConfirmAsStay() {
        var stayed = 0
        state.open(TestPage("root"))
        state.push(
            NovaCommonPage.Confirm(
                key = "quit",
                title = "Quit",
                message = AnnotatedString("Quit the running app?"),
                stayLabel = "Stay",
                actionLabel = "Quit",
                destructive = true,
                onConfirm = {},
                onStay = { stayed++ },
            ),
        )
        host()

        val header = rule.onNodeWithText("$BackGlyph Quit").getUnclippedBoundsInRoot()
        assertTrue("the header is a full touch target", header.bottom - header.top >= NovaPanelMetrics.ArrowTarget)
        rule.onNodeWithText("$BackGlyph Quit").performClick()

        assertEquals(1, state.depth)
        assertEquals("tapping the header is the touch B, and B on a confirm is Stay", 1, stayed)
    }

    @Test
    fun multiChoiceRowsAreTogglesNotTheCurrentValue() {
        var done: Set<Int>? = null
        state.open(TestPage("root"))
        state.push(
            NovaCommonPage.MultiChoice(
                key = "sources",
                title = "Sources",
                options = (0 until 3).map { NovaOption(it, "Source $it") },
                selected = setOf(1),
                doneLabel = "Done",
                onDone = { done = it },
            ),
        )
        val keys = host()
        val on = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)
        val off = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off)
        rule.onNodeWithText("Source 1").assert(on)
        rule.onNodeWithText("Source 1").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        rule.onNodeWithText("Source 0").assert(off)

        keys.press(NovaTestKeys.CENTER)
        rule.onNodeWithText("Source 0").assert(on)
        rule.onNodeWithText("Done").performClick()
        assertEquals(setOf(0, 1), done)
    }

    @Test
    fun noticeFocusesItsPrimary() {
        var sent = 0
        state.open(
            NovaCommonPage.Notice(
                key = "crash",
                title = "Nova closed unexpectedly",
                message = "A report is ready.",
                primary = NovaAction("Send report") { sent++ },
                closeLabel = "Close",
            ),
        )
        val keys = host()
        rule.onNodeWithText("Send report").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals(1, sent)
        assertEquals("the root leaves by closing", 1, closeRequests)
    }

    @Test
    fun aFormFieldStaysReadOnlyUntilAAndAnErrorKeepsThePage() {
        var submitted: Map<String, String>? = null
        state.open(TestPage("root"))
        state.push(
            NovaCommonPage.Form(
                key = "pin",
                title = "Pair with PIN",
                fields = listOf(NovaField(key = "pin", label = "PIN", initial = "12", kind = NovaFieldKind.Number, maxLength = 4)),
                submitLabel = "Pair",
                onSubmit = {
                    submitted = it
                    "That PIN did not work"
                },
            ),
        )
        val keys = host()
        val field = rule.onNodeWithText("12")
        field.assertIsFocused()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))

        keys.press(NovaTestKeys.CENTER)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))

        keys.press(NovaTestKeys.DOWN)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))
        rule.onNodeWithText("Pair").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)

        assertEquals(mapOf("pin" to "12"), submitted)
        rule.onNodeWithText("That PIN did not work").assertExists()
        assertEquals(2, state.depth)
    }

    @Test
    fun aFormThatSubmitsCleanlyPops() {
        state.open(TestPage("root"))
        state.push(
            NovaCommonPage.Form(
                key = "rename",
                title = "Rename",
                fields = listOf(NovaField(key = "name", label = "Name", initial = "Deck")),
                submitLabel = "Save",
                onSubmit = { null },
            ),
        )
        val keys = host()
        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.CENTER)
        assertEquals(1, state.depth)
    }

    @Test
    fun theSliderStepsAndSaves() {
        var saved: Int? = null
        val previews = mutableListOf<Int>()
        state.open(TestPage("root"))
        state.push(
            NovaCommonPage.Slider(
                key = "bitrate",
                title = "Bitrate",
                value = 20,
                range = 0..150,
                step = 5,
                format = { "$it Mbps" },
                onPreview = { previews += it },
                onSave = { saved = it },
            ),
        )
        val keys = host()
        rule.onNodeWithText("20 Mbps").assertIsFocused()
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.RIGHT)
        rule.onNodeWithText("30 Mbps").assertIsFocused()

        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText("Save").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)

        assertEquals(30, saved)
        assertEquals(1, state.depth)
        assertEquals(listOf(20, 25, 30), previews)
    }

    @Test
    fun theExactFieldTakesAMinusSignOnlyWhereTheRangeGoesBelowZero() {
        assertEquals("-150", novaExactValueText("-150", negatives = true))
        assertEquals("a lone minus is kept while the number is typed", "-", novaExactValueText("-", negatives = true))
        assertEquals("one sign, and only in front", "-12", novaExactValueText("-1-2", negatives = true))
        assertEquals("150", novaExactValueText("-150", negatives = false))
        assertEquals("150", novaExactValueText("1a5 0", negatives = false))
    }

    @Test
    fun aNegativeValueCanBeTypedAndSaved() {
        var saved: Int? = null
        state.open(TestPage("root"))
        state.push(
            NovaCommonPage.Slider(
                key = "sensitivity",
                title = "Trackpad sensitivity",
                value = 20,
                range = -200..200,
                step = 10,
                format = { "$it%" },
                onSave = { saved = it },
            ),
        )
        val keys = host()
        rule.onNodeWithText("20%").assertIsFocused()
        keys.press(NovaTestKeys.DOWN)
        val field = rule.onNodeWithText("20")
        field.assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
        field.performTextReplacement("-150")
        rule.onNodeWithText("-150%").assertExists()

        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText("Save").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals(-150, saved)
    }

    @Test
    fun aMenuOpensPagesAndChangesValuesInPlace() {
        var mode by mutableStateOf("Grid")
        state.open(
            NovaCommonPage.Menu(
                key = "options",
                title = "Options",
                header = NovaMenuHeader(title = "Library", status = "12 games"),
                items = listOf(
                    NovaMenuItem.Value(
                        key = "layout",
                        label = "Layout",
                        options = listOf("Grid", "Compact", "Stage").map { NovaOption(it, it) },
                        current = mode,
                        onChange = { mode = it },
                    ),
                    NovaMenuItem.Opens(key = "sort", label = "Sort", value = "Recent", page = { TestPage("sort") }),
                ),
            ),
        )
        val keys = host()
        rule.onNodeWithText("Layout").assertExists()
        keys.press(NovaTestKeys.RIGHT)
        assertEquals("Compact", mode)
        assertEquals("the value changed in its row; nothing opened", 1, state.depth)
        rule.onNodeWithText("Layout").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Compact"))

        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.CENTER)
        assertEquals("sort", state.top?.key)
    }
}
