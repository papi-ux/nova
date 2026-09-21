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
