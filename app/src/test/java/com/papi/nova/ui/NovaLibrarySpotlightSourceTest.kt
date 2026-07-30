package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaLibrarySpotlightSourceTest {
    private fun read(path: String): String = File(path).readText()

    @Test
    fun spotlightIsARealLibraryRenderingModeWithoutChangingExistingCardModes() {
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val spotlight = read("src/main/java/com/papi/nova/ui/NovaLibrarySpotlightRow.kt")

        assertTrue(activity.contains("layoutMode == NovaLibraryLayoutMode.SPOTLIGHT"))
        assertTrue(activity.contains("NovaLibrarySpotlightRow("))
        assertTrue(activity.contains("NovaLibraryLayoutMode.SPOTLIGHT -> R.string.nova_library_options_layout_spotlight"))
        assertTrue(spotlight.contains("LazyRow("))
        assertTrue(spotlight.contains("itemsIndexed("))
        assertTrue(spotlight.contains("key = { _, game -> game.id }"))
        assertTrue(spotlight.contains("NovaLibraryUiStateMapper.spotlightCardWidthDp("))
        assertTrue(spotlight.contains("NovaLibraryUiStateMapper.spotlightHorizontalContentPaddingDp("))
        assertTrue(spotlight.contains("NovaLibraryUiStateMapper.spotlightCardHeightDp("))
        assertTrue(spotlight.contains("NovaLibraryUiStateMapper.spotlightConstrainedCardHeightDp("))
    }

    @Test
    fun spotlightUsesRealArtworkAccessibleCardsAndDeterministicFocusRestoration() {
        val spotlight = read("src/main/java/com/papi/nova/ui/NovaLibrarySpotlightRow.kt")

        assertTrue(spotlight.contains("apiClient.loadCoverInto(view, game)"))
        assertTrue(spotlight.contains("coverLoader(view, game)"))
        assertTrue(spotlight.contains("NovaLibraryUiStateMapper.spotlightRestoreIndex("))
        assertTrue(spotlight.contains("NovaLibraryUiStateMapper.spotlightAdjacentIndex("))
        assertTrue(spotlight.contains("rememberLazyListState("))
        assertTrue(spotlight.contains("FocusRequester()"))
        assertTrue(spotlight.contains("repeat(SPOTLIGHT_FOCUS_REQUEST_ATTEMPTS)"))
        assertTrue(spotlight.contains("SPOTLIGHT_FOCUS_RETRY_DELAY_MS"))
        assertTrue(spotlight.contains(".onPreviewKeyEvent"))
        assertTrue(spotlight.contains("Key.DirectionLeft"))
        assertTrue(spotlight.contains("Key.DirectionRight"))
        assertTrue(spotlight.contains(".semantics"))
        assertTrue(spotlight.contains("contentDescription = accessibilityLabel"))
        assertTrue(spotlight.contains("showPosterTitles || focused"))
        assertTrue(spotlight.contains("LocalDensity.current.fontScale >= 1.5f"))
    }

    @Test
    fun adaptiveControllerChromeObservesInputWithoutStealingNavigation() {
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")

        assertTrue(activity.contains("private var controllerHintChromeState by mutableStateOf(NovaControllerHintChromeState())"))
        assertTrue(activity.contains("override fun dispatchKeyEvent(event: KeyEvent): Boolean"))
        assertTrue(activity.contains("registerControllerBrowseIntent()"))
        assertTrue(activity.contains("override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean"))
        assertTrue(activity.contains("InputDevice.SOURCE_JOYSTICK"))
        assertTrue(activity.contains("NovaControllerHintChromeEvent.BROWSE_INTENT"))
        assertTrue(activity.contains("NovaControllerHintChromeEvent.IDLE"))
        assertTrue(activity.contains("NovaControllerHintChromeEvent.LAYOUT_CHANGED"))
        assertTrue(activity.contains("NovaControllerHintChromeEvent.HELP_REQUESTED"))
        assertTrue(activity.contains("NovaControllerHintChromeEvent.EXPLICIT_REVEAL"))
        assertTrue(activity.contains("controllerHintChromeState.visible"))
        assertTrue(activity.contains("AnimatedVisibility("))
        assertTrue(activity.contains("visible = controllerHintsVisible"))
        assertTrue(activity.contains("return super.dispatchGenericMotionEvent(event)"))
    }
}
