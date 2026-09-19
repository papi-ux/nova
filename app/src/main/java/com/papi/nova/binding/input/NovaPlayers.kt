package com.papi.nova.binding.input

/** One player on the host: the controller number it plays as, and the pad that holds it. */
data class NovaPlayerSlot(val number: Int, val name: String)

/**
 * Who plays as whom, for couch co-op. The host numbers its pads from 0, and a pad takes the
 * lowest free number the first time a button on it is pressed, so the order people press in
 * is the order they play in.
 */
object NovaPlayers {
    /**
     * The controller numbers held before anyone presses anything: 0 for a handheld's own pad
     * when several pads can play. The built-in pad took 0 without holding it, so the first
     * pad paired to the handheld took 0 as well, and the host saw the two as one player.
     */
    fun initialHeldControllers(multiController: Boolean, builtInGamepad: Boolean): Short =
        if (multiController && builtInGamepad) 1 else 0

    /**
     * One player per controller number, lowest first, named after the first pad that holds
     * it. A pad can arrive as two devices, its buttons and its sticks, both on one number.
     */
    fun players(assigned: List<Pair<Int, String>>): List<NovaPlayerSlot> =
        assigned
            .groupBy { it.first }
            .toSortedMap()
            .map { (number, holders) ->
                NovaPlayerSlot(number, holders.map { it.second }.firstOrNull { it.isNotBlank() }.orEmpty())
            }

    /** How the Command Center reads the players out, with the words it is given. */
    fun caption(
        players: List<NovaPlayerSlot>,
        waiting: List<String>,
        multiController: Boolean,
        playerFormat: String,
        unnamedPad: String,
        waitingFormat: String,
        nobodyYet: String,
        onePlayer: String,
    ): String {
        if (!multiController) return onePlayer
        val parts = players.map { playerFormat.format(it.number + 1, it.name.ifBlank { unnamedPad }) }.toMutableList()
        if (waiting.isNotEmpty()) parts += waitingFormat.format(waiting.joinToString(", "))
        return if (parts.isEmpty()) nobodyYet else parts.joinToString(" · ")
    }
}
