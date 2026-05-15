package com.papi.nova.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class NovaComposeSourceGuardTest {
    @Test
    public void libraryFilterSheetContentIsScrollable() throws Exception {
        String source = readNovaLibraryActivity();
        String filterSheet = source.substring(
                source.indexOf("private fun NovaLibraryFilterSheet("),
                source.indexOf("private fun NovaSelectableChip("));

        assertTrue("filter sheet should keep long source/category/genre lists reachable",
                filterSheet.contains(".verticalScroll(rememberScrollState())"));
    }

    @Test
    public void libraryCoverLoadingIsKeyedOutsideAndroidViewUpdate() throws Exception {
        String source = readNovaLibraryActivity();

        assertTrue("cover view should be keyed by the game cover identity",
                source.contains("key(game.id, game.coverUrl)"));
        assertTrue("cover load should happen when the keyed ImageView is created",
                source.contains("apiClient.loadCoverInto(this, game)"));
        assertFalse("cover load should not be restarted from AndroidView.update on focus recomposition",
                source.contains("update = { imageView ->\n                    apiClient.loadCoverInto(imageView, game)"));
    }

    @Test
    public void librarySearchDoesNotEnterTextInputOnDpadFocus() throws Exception {
        String source = readNovaLibraryActivity();
        String searchField = source.substring(
                source.indexOf("private fun NovaSearchField("),
                source.indexOf("private fun NovaFilterChip("));

        assertTrue("search should keep a browse mode before explicitly editing text",
                searchField.contains("var searchEditing by remember { mutableStateOf(false) }"));
        assertTrue("search should not show the IME just because D-pad focus lands on it",
                searchField.contains("readOnly = !searchEditing"));
        assertTrue("search should handle D-pad keys before the IME traps navigation",
                searchField.contains(".onPreviewKeyEvent"));
        assertTrue("search should hide the keyboard when D-pad navigation leaves edit mode",
                searchField.contains("keyboardController?.hide()"));
        assertTrue("search should move focus down out of the field instead of trapping D-pad input",
                searchField.contains("Key.DirectionDown -> leaveSearchEditing(FocusDirection.Down)"));
        assertFalse("search should not wait for edit mode before releasing D-pad navigation",
                searchField.contains("Key.DirectionDown -> if (searchEditing)"));
        assertTrue("controller select should not place search into a D-pad-trapping edit mode",
                searchField.contains("Key.DirectionCenter -> true"));
    }

    @Test
    public void libraryRailKeepsDpadTraversalInsideRail() throws Exception {
        String source = readNovaLibraryActivity();
        String rail = source.substring(
                source.indexOf("private fun NovaLibraryRail("),
                source.indexOf("private fun NovaLibraryTopHeader("));

        assertTrue("side rail should be a focus group so vertical D-pad traversal stays in the rail",
                rail.contains(".focusGroup()"));
    }

    @Test
    public void libraryGameCardsUseHighVisibilityFocusedFrame() throws Exception {
        String source = readNovaLibraryActivity();
        String gameCard = source.substring(
                source.indexOf("private fun NovaLibraryGameCard("),
                source.indexOf("private fun NovaMiniBadge("));

        assertTrue("focused library cards should use a thicker outer focus border",
                gameCard.contains("width = if (focused) 3.dp else 1.dp"));
        assertTrue("focused library cards should draw a foreground focus frame above cover art",
                gameCard.contains(".border(4.dp, surfaces.focusRing, RoundedCornerShape(14.dp))"));
    }

    private static String readNovaLibraryActivity() throws Exception {
        return new String(
                Files.readAllBytes(Paths.get("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")),
                StandardCharsets.UTF_8);
    }
}
