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

    fun shouldUseDisplayLaunchTrampoline(
        selectedDisplayId: Int?,
        currentDisplayId: Int?,
        companionDisplayId: Int?,
    ): Boolean {
        if (selectedDisplayId == null || companionDisplayId == null) return false
        return selectedDisplayId != currentDisplayId
    }

    data class Resolution(val width: Int, val height: Int)

    fun requiresGameRecreation(activeDisplayId: Int, requestedDisplayId: Int): Boolean =
        activeDisplayId != requestedDisplayId

    fun resolveStreamResolution(
        modeWidth: Int,
        modeHeight: Int,
        windowWidth: Int,
        windowHeight: Int,
        landscape: Boolean,
    ): Resolution {
        val safeMode = Resolution(modeWidth.coerceAtLeast(1), modeHeight.coerceAtLeast(1))
        if (windowWidth <= 0 || windowHeight <= 0) return safeMode

        val rawWindow = Resolution(windowWidth, windowHeight)
        val orientedWindow = when {
            landscape && rawWindow.width < rawWindow.height -> Resolution(rawWindow.height, rawWindow.width)
            !landscape && rawWindow.width > rawWindow.height -> Resolution(rawWindow.height, rawWindow.width)
            else -> rawWindow
        }
        val modeRatio = safeMode.width.toDouble() / safeMode.height
        val windowRatio = orientedWindow.width.toDouble() / orientedWindow.height
        val relativeRatioDelta = kotlin.math.abs(modeRatio - windowRatio) / modeRatio

        return if (relativeRatioDelta > MAX_WINDOW_MODE_RATIO_DELTA) orientedWindow else safeMode
    }

    private const val MAX_WINDOW_MODE_RATIO_DELTA = 0.08
}
