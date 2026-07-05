package com.papi.nova.utils

object AndroidStreamDisplayTarget {
    const val AUTO = "auto"
    const val PRIMARY = "primary"
    const val EXTERNAL = "external"
    const val LARGEST = "largest"

    data class Candidate(
        val displayId: Int,
        val width: Int,
        val height: Int,
    ) {
        val pixelArea: Long = width.toLong() * height.toLong()
    }

    fun select(displays: List<Candidate>, defaultDisplayId: Int, target: String?): Candidate? {
        if (displays.isEmpty()) return null
        val primary = displays.firstOrNull { it.displayId == defaultDisplayId } ?: displays.first()
        val external = displays.firstOrNull { it.displayId != defaultDisplayId }
        return when (target) {
            PRIMARY -> primary
            EXTERNAL -> external
            LARGEST -> displays.maxWithOrNull(
                compareBy<Candidate> { it.pixelArea }
                    .thenByDescending { if (it.displayId == defaultDisplayId) 0 else 1 }
            )
            else -> external ?: primary
        }
    }

    fun selectCompanion(
        displays: List<Candidate>,
        defaultDisplayId: Int,
        streamDisplayId: Int?,
    ): Candidate? {
        if (displays.size < 2 || streamDisplayId == null) return null

        val candidates = displays.filter { it.displayId != streamDisplayId }
        if (candidates.isEmpty()) return null

        return candidates.minWithOrNull(
            compareBy<Candidate> { it.pixelArea }
                .thenBy { if (it.displayId == defaultDisplayId) 0 else 1 }
        )
    }
}
