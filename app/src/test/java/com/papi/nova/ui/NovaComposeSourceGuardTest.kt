package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaComposeSourceGuardTest {
    @Test
    fun sharedComposeFocusControlsUseHighContrastTreatment() {
        val focusComponents = readNovaFocusComponents()
        val actionButton = focusComponents.substring(focusComponents.indexOf("fun NovaActionButton("))
        // The panel foundation gave every Nova control one focus look, drawn once by
        // Modifier.novaFocusRing: a fill plus a 3dp ring inside the shape, with no scale and no
        // halo. The action button pins below follow it there; the chip pins are unchanged.
        val focusRing = readSource("src/main/java/com/papi/nova/ui/panel/NovaPanelTokens.kt")
        val selectableChip = readNovaLibraryActivity().section(
            "private fun NovaSelectableChip(",
            "private fun NovaLibraryPanel("
        )

        assertTrue(
            "action buttons should reserve a stronger focused outline: the one 3dp focus ring",
            actionButton.contains(".novaFocusRing(") &&
                focusRing.contains("val FocusRingWidth: Dp = 3.dp") &&
                focusRing.contains("lerp(restBorderWidth, NovaPanelMetrics.FocusRingWidth, amount)")
        )
        assertTrue(
            "action buttons should use the focused control surface on D-pad focus",
            actionButton.contains("else surfaces.selectedControl") &&
                actionButton.contains("focusedFill = focusedContainer")
        )
        assertTrue(
            "selectable chips should use the same 3dp focused outline",
            selectableChip.contains(".border(if (focused) 3.dp else 1.dp")
        )
        assertTrue(
            "selectable chips should visibly fill on focus even when not selected",
            selectableChip.contains("focused -> surfaces.selectedControl")
        )
        assertTrue(
            "selectable chips should observe the focus target that receives D-pad focus",
            selectableChip.indexOf(".onFocusChanged {") in 0 until
                selectableChip.indexOf(".combinedClickable(")
        )
        assertTrue(
            "selectable chips should expose one merged button semantics node so clipped child text never becomes the accessibility target",
            selectableChip.contains(".semantics(mergeDescendants = true)") &&
                selectableChip.contains("val chipDescription = \"\$label. \$detail\"") &&
                selectableChip.contains("contentDescription = chipDescription") &&
                selectableChip.contains("role = Role.Button") &&
                selectableChip.contains(".combinedClickable(")
        )
        assertTrue(
            "shared Compose focus controls should animate the one focus look over 150ms, with no scale and no halo",
            focusRing.contains("const val FocusMillis = 150") &&
                focusRing.contains("progress.animateTo(if (now) 1f else 0f, tween(NovaPanelMetrics.FocusMillis))") &&
                !actionButton.contains(".novaFocusMotion(")
        )
    }

    private fun readNovaLibraryActivity(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")

    private fun readNovaFocusComponents(): String =
        readSource("src/main/java/com/papi/nova/ui/compose/NovaFocusComponents.kt")

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing start marker: $startMarker" }
        val end = indexOf(endMarker, start)
        require(end >= 0) { "Missing end marker: $endMarker" }
        return substring(start, end)
    }
}
