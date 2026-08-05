package com.papi.nova.api

import com.papi.nova.shared.polaris.model.PolarisGame
import kotlinx.serialization.json.Json

/**
 * Round-trips a [PolarisGame] through JSON so it can travel in an Intent.
 *
 * [PolarisGameJsonAdapter] only reads, because until now games only ever arrived from the
 * Polaris API. Handing one to another Activity needs the other direction, and the model is
 * already `@Serializable`, so its generated serializer does the work rather than a
 * hand-written mirror of the adapter that could drift away from it.
 */
object PolarisGameJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(game: PolarisGame): String = json.encodeToString(PolarisGame.serializer(), game)

    /** Returns null rather than throwing: a malformed extra should close the window, not crash it. */
    fun decode(raw: String): PolarisGame? = try {
        json.decodeFromString(PolarisGame.serializer(), raw)
    } catch (_: Exception) {
        null
    }
}
