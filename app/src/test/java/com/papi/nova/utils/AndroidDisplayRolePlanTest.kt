package com.papi.nova.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDisplayRolePlanTest {
    private val primary = AndroidDisplayRolePlan.DisplaySpec(
        displayId = 0,
        label = "Top / primary",
        width = 1920,
        height = 1080,
        refreshRateHz = 120f,
        isDefault = true,
    )
    private val external = AndroidDisplayRolePlan.DisplaySpec(
        displayId = 4,
        label = "Bottom / external",
        width = 1240,
        height = 1080,
        refreshRateHz = 60f,
        isDefault = false,
    )

    @Test
    fun followPreservesAutoCompatibilityRoute() {
        val state = build(current = AndroidStreamDisplayTarget.AUTO, pending = AndroidStreamDisplayTarget.AUTO)

        assertTrue(state.pending.followingSafeDefault)
        assertEquals(4, state.pending.stream?.displayId)
        assertEquals(0, state.pending.companion?.displayId)
        assertFalse(state.hasChanges)
        assertFalse(state.canApply)
    }

    @Test
    fun currentAndPendingAssignmentsRemainDistinctUntilApply() {
        val state = build(current = AndroidStreamDisplayTarget.EXTERNAL, pending = AndroidStreamDisplayTarget.PRIMARY)

        assertEquals(AndroidStreamDisplayTarget.EXTERNAL, state.current.target)
        assertEquals(4, state.current.stream?.displayId)
        assertEquals(0, state.current.companion?.displayId)
        assertEquals(AndroidStreamDisplayTarget.PRIMARY, state.pending.target)
        assertEquals(0, state.pending.stream?.displayId)
        assertEquals(4, state.pending.companion?.displayId)
        assertTrue(state.hasChanges)
        assertTrue(state.canApply)
    }

    @Test
    fun streamAndCompanionNeverConflict() {
        val targets = listOf(
            AndroidStreamDisplayTarget.AUTO,
            AndroidStreamDisplayTarget.PRIMARY,
            AndroidStreamDisplayTarget.EXTERNAL,
            AndroidStreamDisplayTarget.LARGEST,
        )

        targets.forEach { target ->
            val route = build(target, target).pending
            assertTrue(route.stream != null)
            assertTrue(route.stream?.displayId != route.companion?.displayId)
            val duplicateRoles = route.assignments
                .groupBy { it.display.displayId }
                .filterValues { assignments -> assignments.map { it.role }.distinct().size > 1 }
            assertTrue("one display received conflicting roles for $target", duplicateRoles.isEmpty())
        }
    }

    @Test
    fun swapRolesIsReversibleForTwoDisplays() {
        val externalRoute = build(
            current = AndroidStreamDisplayTarget.EXTERNAL,
            pending = AndroidStreamDisplayTarget.EXTERNAL,
        ).pending
        val primaryTarget = AndroidDisplayRolePlan.swapTarget(externalRoute)
        assertEquals(AndroidStreamDisplayTarget.PRIMARY, primaryTarget)

        val primaryRoute = build(
            current = AndroidStreamDisplayTarget.EXTERNAL,
            pending = requireNotNull(primaryTarget),
        ).pending
        assertEquals(AndroidStreamDisplayTarget.EXTERNAL, AndroidDisplayRolePlan.swapTarget(primaryRoute))
    }

    @Test
    fun singleDisplayFollowFallsBackSafelyWithoutCompanion() {
        val state = AndroidDisplayRolePlan.build(
            displays = listOf(primary),
            defaultDisplayId = 0,
            currentTarget = AndroidStreamDisplayTarget.AUTO,
            pendingTarget = AndroidStreamDisplayTarget.AUTO,
        )

        assertEquals(0, state.pending.stream?.displayId)
        assertNull(state.pending.companion)
        assertEquals(AndroidDisplayRolePlan.Recovery.SINGLE_DISPLAY, state.pending.recovery)
        assertTrue(state.pending.requestedRouteAvailable)
        assertFalse(state.canSwap)
    }

    @Test
    fun unavailableExternalTargetPreviewsSafePrimaryAndCannotApply() {
        val state = AndroidDisplayRolePlan.build(
            displays = listOf(primary),
            defaultDisplayId = 0,
            currentTarget = AndroidStreamDisplayTarget.AUTO,
            pendingTarget = AndroidStreamDisplayTarget.EXTERNAL,
        )

        assertEquals(0, state.pending.stream?.displayId)
        assertNull(state.pending.companion)
        assertEquals(AndroidDisplayRolePlan.Recovery.REQUESTED_DISPLAY_UNAVAILABLE, state.pending.recovery)
        assertFalse(state.pending.requestedRouteAvailable)
        assertTrue(state.hasChanges)
        assertFalse(state.canApply)
    }

    @Test
    fun choosingFollowCanRepairUnknownPersistedTarget() {
        val state = AndroidDisplayRolePlan.build(
            displays = listOf(primary, external),
            defaultDisplayId = 0,
            currentTarget = "future-value",
            pendingTarget = AndroidStreamDisplayTarget.AUTO,
        )

        assertTrue(state.hasChanges)
        assertTrue(state.canApply)
        assertEquals(AndroidStreamDisplayTarget.AUTO, state.pending.target)
    }

    @Test
    fun displayRemovalRecomputesToSafeFallbackWithoutRewritingPersistedTarget() {
        val connected = build(
            current = AndroidStreamDisplayTarget.EXTERNAL,
            pending = AndroidStreamDisplayTarget.EXTERNAL,
        )
        assertEquals(4, connected.pending.stream?.displayId)

        val removed = AndroidDisplayRolePlan.build(
            displays = listOf(primary),
            defaultDisplayId = 0,
            currentTarget = connected.current.target,
            pendingTarget = connected.pending.target,
        )

        assertEquals(AndroidStreamDisplayTarget.EXTERNAL, removed.pending.target)
        assertEquals(0, removed.pending.stream?.displayId)
        assertEquals(AndroidDisplayRolePlan.Recovery.REQUESTED_DISPLAY_UNAVAILABLE, removed.pending.recovery)
    }

    @Test
    fun largestTargetPreservesLegacyResolution() {
        val largestExternal = external.copy(width = 2560, height = 1600)
        val state = AndroidDisplayRolePlan.build(
            displays = listOf(primary, largestExternal),
            defaultDisplayId = 0,
            currentTarget = AndroidStreamDisplayTarget.LARGEST,
            pendingTarget = AndroidStreamDisplayTarget.LARGEST,
        )

        assertEquals(4, state.pending.stream?.displayId)
        assertEquals(0, state.pending.companion?.displayId)
        assertEquals(AndroidDisplayRolePlan.Recovery.NONE, state.pending.recovery)
    }

    @Test
    fun followPreservesFrameworkOrderWhenMultipleExternalDisplaysExist() {
        val firstExternal = external.copy(displayId = 9, label = "First external")
        val secondExternal = external.copy(displayId = 4, label = "Second external")
        val state = AndroidDisplayRolePlan.build(
            displays = listOf(primary, firstExternal, secondExternal),
            defaultDisplayId = primary.displayId,
            currentTarget = AndroidStreamDisplayTarget.AUTO,
            pendingTarget = AndroidStreamDisplayTarget.AUTO,
        )

        assertEquals(firstExternal.displayId, state.pending.stream?.displayId)
    }

    @Test
    fun unknownTargetPreviewsAsFollowWithExplicitRecoveryStatus() {
        val state = build(current = "future-value", pending = "future-value")

        assertEquals(AndroidStreamDisplayTarget.AUTO, state.pending.target)
        assertTrue(state.pending.followingSafeDefault)
        assertEquals(AndroidDisplayRolePlan.Recovery.UNKNOWN_TARGET, state.pending.recovery)
        assertEquals(4, state.pending.stream?.displayId)
    }

    @Test
    fun displayMetadataRemainsTruthfulInAssignments() {
        val route = build(
            current = AndroidStreamDisplayTarget.PRIMARY,
            pending = AndroidStreamDisplayTarget.PRIMARY,
        ).pending

        assertEquals("Top / primary", route.stream?.label)
        assertEquals(1920, route.stream?.width)
        assertEquals(1080, route.stream?.height)
        assertEquals(120f, route.stream?.refreshRateHz)
        assertEquals("Bottom / external", route.companion?.label)
        assertEquals(60f, route.companion?.refreshRateHz)
    }

    private fun build(current: String, pending: String): AndroidDisplayRolePlan.State {
        return AndroidDisplayRolePlan.build(
            displays = listOf(primary, external),
            defaultDisplayId = 0,
            currentTarget = current,
            pendingTarget = pending,
        )
    }
}
