package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening the Artwork Studio by touch on a handheld put a full screen of landscape keyboard over
 * it before any of it had been seen. Found on a Retroid Pocket 6, 2026-09-21.
 */
class NovaArtworkStudioKeyboardTest {

    @Test
    fun theStudioKeepsTheKeyboardDownUntilItsFieldIsPressed() {
        val studio = String(
            Files.readAllBytes(Path.of("src/main/java/com/papi/nova/ui/NovaArtworkStudio.kt")),
            StandardCharsets.UTF_8,
        )
        assertTrue(
            "the search field is the studio's first stop, so focus on open raised a full screen of " +
                "keyboard over a studio nobody had seen yet",
            studio.contains(".focusProperties { canFocus = fieldTakesFocus }") &&
                studio.contains("delay(NOVA_FIRST_FOCUS_SETTLE_MS * 4)") &&
                studio.contains("showKeyboardOnFocus = false") && studio.contains("imeAction = ImeAction.Search") &&
                studio.contains("keyboardActions = KeyboardActions(onSearch = {")
        )
    }
}
