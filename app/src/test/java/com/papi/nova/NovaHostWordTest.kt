package com.papi.nova

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The player's machine has one name in Nova's words, host (C28). The copy called it host, server,
 * PC and computer, at times on one screen. What is kept below is a name for something else, or a
 * feature Polaris names that way itself.
 */
class NovaHostWordTest {
    private val word = Regex("\\b(PC|PCs|server|servers|computer|computers|Rechner|Rechners)\\b", RegexOption.IGNORE_CASE)
    // Not a self-closing <string name="x" />, which would run on into the next entry.
    private val entry = Regex("<string name=\"([^\"]+)\"[^>/]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

    /** Where another word is right, and why. */
    private val kept = setOf(
        // Nova's own connection test servers on the internet, not the player's machine.
        "nettest_text_inconclusive",
        // The feature's own name in Polaris's console: Server Commands.
        "game_menu_server_cmd",
        "game_dialog_message_server_cmd_empty",
        // The in-stream connection warnings belong to the Command Center branch, which rewrites them.
        "slow_connection_msg",
        "poor_connection_msg",
    )

    private fun offenders(path: String): Map<String, String> =
        entry.findAll(File(path).readText()).mapNotNull { match ->
            val (name, value) = match.destructured
            if (name !in kept && word.containsMatchIn(value)) name to value.trim() else null
        }.toMap()

    @Test
    fun englishCallsThePlayersMachineTheHost() {
        // strings_ui_stream.xml is the Command Center branch's.
        val files = listOf("strings.xml", "strings_ui_hosts.xml", "strings_ui_library.xml", "strings_ui_panel.xml", "strings_ui_settings.xml", "strings_ui_debug.xml")
        val found = files.flatMap { offenders("src/main/res/values/$it").toList() }.toMap()
        assertEquals("say host", emptyMap<String, String>(), found)
    }

    @Test
    fun germanCallsItHostToo() {
        assertEquals("sag Host", emptyMap<String, String>(), offenders("src/main/res/values-de/strings.xml"))
    }

    @Test
    fun settingsWrittenInThePreferencesFileSayHostToo() {
        val written = Regex("android:(title|summary)=\"([^@\"][^\"]*)\"").findAll(File("src/main/res/xml/preferences.xml").readText())
            .map { it.groupValues[2] }
            .filter { word.containsMatchIn(it) }
            .toList()
        assertEquals(emptyList<String>(), written)
    }

    @Test
    fun wordsWrittenInCodeSayHostToo() {
        val code = mapOf(
            "nvstream/NvConnection.kt" to listOf("the PC itself", "PC GPU", "host PC", "not paired with computer", "on the PC", "Server version"),
            "nvstream/http/HostHttpResponseException.kt" to listOf("Host PC"),
            "utils/ServerHelper.kt" to listOf("the PC itself"),
            "ui/NovaLibraryUiState.kt" to listOf("Wake the PC", "\"Manage Server\""),
            "ui/NovaStreamOverlayContent.kt" to listOf("\"Server starting or unlocking\""),
            "Game.kt" to listOf("else \"Server\""),
        )
        val found = code.flatMap { (file, phrases) ->
            val source = File("src/main/java/com/papi/nova/$file").readText()
            phrases.filter { source.contains(it) }.map { "$file: $it" }
        }
        assertEquals(emptyList<String>(), found)
    }
}
