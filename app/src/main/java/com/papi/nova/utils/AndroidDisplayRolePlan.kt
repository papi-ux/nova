package com.papi.nova.utils

object AndroidDisplayRolePlan {
    enum class Role {
        STREAM,
        COMPANION,
        AVAILABLE,
    }

    enum class Recovery {
        NONE,
        SINGLE_DISPLAY,
        REQUESTED_DISPLAY_UNAVAILABLE,
        UNKNOWN_TARGET,
    }

    data class DisplaySpec(
        val displayId: Int,
        val label: String,
        val width: Int,
        val height: Int,
        val refreshRateHz: Float,
        val isDefault: Boolean,
    ) {
        val pixelArea: Long = width.toLong() * height.toLong()
    }

    data class Assignment(
        val display: DisplaySpec,
        val role: Role,
    )

    data class Route(
        val requestedTarget: String?,
        val target: String,
        val followingSafeDefault: Boolean,
        val stream: DisplaySpec?,
        val companion: DisplaySpec?,
        val assignments: List<Assignment>,
        val recovery: Recovery,
        val requestedRouteAvailable: Boolean,
        internal val swappableTarget: String?,
    )

    data class State(
        val current: Route,
        val pending: Route,
        val hasChanges: Boolean,
        val canApply: Boolean,
        val canSwap: Boolean,
    )

    fun build(
        displays: List<DisplaySpec>,
        defaultDisplayId: Int,
        currentTarget: String?,
        pendingTarget: String?,
    ): State {
        val current = resolve(displays, defaultDisplayId, currentTarget)
        val pending = resolve(displays, defaultDisplayId, pendingTarget)
        val hasChanges = comparisonTarget(currentTarget) != comparisonTarget(pendingTarget)
        return State(
            current = current,
            pending = pending,
            hasChanges = hasChanges,
            canApply = hasChanges && pending.requestedRouteAvailable,
            canSwap = pending.swappableTarget != null,
        )
    }

    fun swapTarget(route: Route): String? = route.swappableTarget

    private fun resolve(
        displays: List<DisplaySpec>,
        defaultDisplayId: Int,
        rawTarget: String?,
    ): Route {
        val targetKnown = rawTarget in supportedTargets
        val target = rawTarget.takeIf { targetKnown } ?: AndroidStreamDisplayTarget.AUTO
        val candidates = displays.map {
            AndroidStreamDisplayTarget.Candidate(
                displayId = it.displayId,
                width = it.width,
                height = it.height,
            )
        }
        val requestedStreamCandidate = AndroidStreamDisplayTarget.select(
            displays = candidates,
            defaultDisplayId = defaultDisplayId,
            target = target,
        )
        val requestedRouteAvailable = requestedStreamCandidate != null
        val streamCandidate = requestedStreamCandidate
            ?: AndroidStreamDisplayTarget.select(
                displays = candidates,
                defaultDisplayId = defaultDisplayId,
                target = AndroidStreamDisplayTarget.AUTO,
            )
        val stream = streamCandidate?.let { selected ->
            displays.firstOrNull { it.displayId == selected.displayId }
        }
        val companionCandidate = AndroidStreamDisplayTarget.selectCompanion(
            displays = candidates,
            defaultDisplayId = defaultDisplayId,
            streamDisplayId = stream?.displayId,
        )
        val companion = companionCandidate?.let { selected ->
            displays.firstOrNull { it.displayId == selected.displayId }
        }
        val recovery = when {
            !targetKnown -> Recovery.UNKNOWN_TARGET
            !requestedRouteAvailable -> Recovery.REQUESTED_DISPLAY_UNAVAILABLE
            displays.size < 2 -> Recovery.SINGLE_DISPLAY
            else -> Recovery.NONE
        }
        val assignments = displays.map { display ->
            Assignment(
                display = display,
                role = when (display.displayId) {
                    stream?.displayId -> Role.STREAM
                    companion?.displayId -> Role.COMPANION
                    else -> Role.AVAILABLE
                },
            )
        }
        val swappableTarget = if (displays.size == 2 && stream != null && companion != null) {
            if (companion.displayId == defaultDisplayId) {
                AndroidStreamDisplayTarget.PRIMARY
            } else {
                AndroidStreamDisplayTarget.EXTERNAL
            }
        } else {
            null
        }
        return Route(
            requestedTarget = rawTarget,
            target = target,
            followingSafeDefault = target == AndroidStreamDisplayTarget.AUTO,
            stream = stream,
            companion = companion,
            assignments = assignments,
            recovery = recovery,
            requestedRouteAvailable = requestedRouteAvailable,
            swappableTarget = swappableTarget,
        )
    }

    private val supportedTargets = setOf(
        AndroidStreamDisplayTarget.AUTO,
        AndroidStreamDisplayTarget.PRIMARY,
        AndroidStreamDisplayTarget.EXTERNAL,
        AndroidStreamDisplayTarget.LARGEST,
    )

    private fun comparisonTarget(target: String?): String {
        return target ?: AndroidStreamDisplayTarget.AUTO
    }
}
