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

    private static String readNovaLibraryActivity() throws Exception {
        return new String(
                Files.readAllBytes(Paths.get("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")),
                StandardCharsets.UTF_8);
    }
}
