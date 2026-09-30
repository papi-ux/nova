package com.papi.nova.ui

/**
 * An instrument line that may wrap, but only after one of its dots.
 *
 * Left to itself a wrapped line breaks wherever the width runs out, "SDR (HDR NOT" over
 * "REQUESTED)", which reads as damage. Every space becomes a no-break space except the last
 * one after a dot, so a second line always starts on a whole reading. The dot stays at the end
 * of the line it closes, the way a list carries its comma.
 */
internal fun novaBreakAtDots(line: String): String = line
    .replace(' ', NOVA_NO_BREAK_SPACE)
    .replace(Regex("·($NOVA_NO_BREAK_SPACE*)$NOVA_NO_BREAK_SPACE"), "·\$1 ")

private const val NOVA_NO_BREAK_SPACE = '\u00A0'

/**
 * The parts of a dotted line, packed onto lines whole: each line takes the parts [fits] says it
 * holds, joined by [separator], and the next part starts a new line with no dot before it.
 * [novaBreakAtDots] left the dot at the end of the line it closed, and on the game page that read
 * as a dangling "·" (N22). A part too long for a line alone still takes a line of its own.
 */
internal fun novaPackAtDots(parts: List<String>, separator: String, fits: (String) -> Boolean): String {
    val lines = mutableListOf<String>()
    var line = ""
    for (part in parts) {
        val candidate = if (line.isEmpty()) part else line + separator + part
        if (line.isEmpty() || fits(candidate)) {
            line = candidate
        } else {
            lines += line
            line = part
        }
    }
    if (line.isNotEmpty()) lines += line
    return lines.joinToString("\n")
}

/** A dotted line's parts: what lies between its dots, trimmed, with none left empty. */
internal fun novaDottedParts(line: String): List<String> =
    line.split('·').map { it.trim() }.filter { it.isNotEmpty() }
