package com.papi.nova.ui.panel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source rules for the panel foundation (spec R10 and section 9.1).
 *
 * Nova's own UI opens in NovaPanelWindow. During the migration each group listed its remaining
 * legacy modal constructors under `src/test/nova-legacy-modals/` and lowered its line as it moved
 * each site; the closing step removed the emptied group lists and sealed the guard. Only
 * `platform.txt` is left, holding the platform surfaces section 8 keeps on purpose, and every file
 * has exactly what it lists, so no legacy modal can come back.
 */
class NovaPanelSourceGuardTest {
    @Test
    fun noTestRunsRobolectricInNativeGraphicsMode() {
        val offenders = sources(File("src/test")).filter { NATIVE_GRAPHICS.containsMatchIn(it.readText()) }
        assertEquals(
            "native graphics mode passes a test on its own and then kills the JVM running the whole " +
                "suite, which drops every later suite without a failure; use the default LEGACY mode",
            emptyList<String>(),
            offenders.map { it.path },
        )
    }

    @Test
    fun legacyModalConstructorsMatchTheirAllowlistsExactly() {
        val lists = allowlists()
        val allowed = lists.values.flatMap { it.entries }.associate { it.key to it.value }
        val listedIn = lists.flatMap { (list, entries) -> entries.keys.map { it to list } }.toMap()
        val found = legacyModalCounts()
        val over = found.filter { (path, count) -> count > (allowed[path] ?: 0) }
            .map { (path, count) -> "$path: $count, allowed ${allowed[path] ?: 0}" }
        assertEquals(
            "Nova's own UI opens in NovaPanelWindow (R10): a new AlertDialog.Builder, BottomSheetDialog, " +
                "ModalBottomSheet, Compose Dialog or AlertDialog, platform or AppCompat dialog, " +
                "DialogFragment or PopupWindow is a panel page, a state page or a split instead. Only " +
                "the platform surfaces of spec section 8 are listed, in src/test/nova-legacy-modals/platform.txt.",
            emptyList<String>(),
            over,
        )
        val stale = allowed.filter { (path, count) -> (found[path] ?: 0) != count }
            .map { (path, count) -> "$path: ${found[path] ?: 0}, listed $count; lower its line in ${listedIn[path]}" }
        assertEquals(
            "a change that removes a listed platform site lowers its line, so the list stays exact " +
                "and a new legacy modal can never take the place one left",
            emptyList<String>(),
            stale,
        )
    }

    @Test
    fun onceSealedOnlyThePlatformListRemains() {
        assertEquals(
            "the closing step emptied and removed every group's list, so no legacy modal may come back",
            setOf(PLATFORM),
            allowlists().keys,
        )
        assertEquals(
            "platform.txt lists only the surfaces spec section 8 keeps on purpose",
            SECTION_8_SITES,
            allowlists().getValue(PLATFORM).keys,
        )
    }

    @Test
    fun theOldHardCodedRedIsGone() {
        val offenders = sources(MAIN).filter { it.relativeTo(MAIN).path !in FIXED_PALETTE }
            .filter { it.readText().contains("Color(0xFFF87171)") }
        assertEquals(
            "destructive colour comes from NovaComposeColors.destructive, which is checked for contrast " +
                "against the theme; a fixed red fails it on the light themes",
            emptyList<String>(),
            offenders.map { it.relativeTo(MAIN).path },
        )
    }

    @Test
    fun panelComponentsActOnlyThroughNovaClickable() {
        val bare = Regex("""\.(clickable|combinedClickable|selectable|toggleable)\(""")
        val offenders = panelSources().flatMap { file ->
            val calls = bare.findAll(code(file.readText())).count()
            // novaClickable itself wraps exactly one clickable, for touch and TalkBack.
            val own = if (file.name == "NovaControllerContract.kt") 1 else 0
            if (calls > own) listOf("${file.name}: $calls") else emptyList()
        }
        assertEquals(
            "a bare clickable acts on key down and on whatever element sees the release; panel " +
                "components use novaClickable, which acts on release and only where the press began",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun panelSurfacesMarkCurrentOnlyWithTheCheck() {
        val surfaceCall = Regex("""NovaAction(Button|Surface)\(""")
        val selectedArg = Regex("""\bselected\s*=""")
        val offenders = panelSources().flatMap { file ->
            val text = code(file.readText())
            surfaceCall.findAll(text)
                .mapNotNull { match -> balancedArgs(text, match.range.last + 1) }
                .filter { args -> selectedArg.containsMatchIn(args) }
                .map { "${file.name}: selected" }
                .toList()
        }
        assertEquals(
            "fills and borders only ever mean focus (R9): a panel marks the current value with " +
                "NovaCurrentMark, never with NovaActionSurface's selected fill",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun panelCornersComeFromNovaRadius() {
        val offenders = panelSources().flatMap { file ->
            val text = file.readText()
            Regex("""RoundedCornerShape\(""").findAll(text)
                .mapNotNull { match -> balancedArgs(text, match.range.last + 1) }
                .filter { args -> Regex("""\d""").containsMatchIn(args) }
                .map { args -> "${file.name}: RoundedCornerShape($args)" }
                .toList()
        }
        assertEquals("ui/panel takes every corner from NovaRadius, never a number", emptyList<String>(), offenders)
    }

    private fun legacyModalCounts(): Map<String, Int> = sources(MAIN)
        .filter { it.relativeTo(MAIN).path != PANEL_WINDOW }
        .associate { it.relativeTo(MAIN).path to LEGACY_MODAL.findAll(code(it.readText())).count() }
        .filterValues { it > 0 }

    /** Each allowlist file's entries, path to count. */
    private fun allowlists(): Map<String, Map<String, Int>> =
        File("src/test/nova-legacy-modals").listFiles { file -> file.extension == "txt" }.orEmpty()
            .associate { list ->
                list.name to list.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    .associate { line ->
                        val parts = line.split(":")
                        require(parts.size == 2) { "${list.name}: expected path:count, found $line" }
                        parts[0] to parts[1].toInt()
                    }
            }

    private fun panelSources(): List<File> = sources(File(MAIN, "ui/panel"))

    private fun sources(dir: File): List<File> =
        dir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .sortedBy { it.path }
            .toList()

    /** Source without comment lines and trailing comments, so prose never counts as code. */
    private fun code(text: String): String = text.lines()
        .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
        .joinToString("\n") { it.replace(Regex("""\s//.*$"""), "") }

    private fun balancedArgs(text: String, from: Int): String? {
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
        const val PANEL_WINDOW = "ui/panel/NovaPanelWindow.kt"
        const val PLATFORM = "platform.txt"

        /**
         * The section 8 sites these patterns match. The in-stream key preview is a popup, not a
         * modal; the other section 8 surfaces (permission, installer, pickers, share sheets, the
         * launcher's pin prompt, the soft keyboard) are platform intents and match none of them.
         */
        val SECTION_8_SITES = setOf("binding/input/virtual_controller/keyboard/KeyBoardLayoutController.kt")

        /** The HUD's traffic-light tones over live video are a fixed palette, not a destructive action. */
        val FIXED_PALETTE = setOf("ui/NovaStreamHudContent.kt")

        val NATIVE_GRAPHICS = Regex("""@GraphicsMode\(\s*(GraphicsMode\.)?(Mode\.)?NATIVE""")

        /**
         * Legacy modal constructors, qualified or not: alert builders, Compose and Material 3
         * alerts, platform, AppCompat, progress and picker dialogs, bottom sheets, dialog
         * fragments in Kotlin and Java, and popup windows.
         */
        val LEGACY_MODAL = Regex(
            listOf(
                """AlertDialog\.Builder\(""",
                """(?<!\w)(Basic)?AlertDialog\(""",
                """(?<!\w)(AppCompat|Progress|DatePicker|TimePicker)?Dialog\(""",
                """BottomSheetDialog\(""",
                """ModalBottomSheet\(""",
                """:\s*\w*DialogFragment(Compat)?\(\)""",
                """extends\s+\w*DialogFragment(Compat)?\b""",
                """PopupWindow\(""",
            ).joinToString("|"),
        )
    }
}
