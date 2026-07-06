package com.papi.nova.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStreamDisplayTargetTest {
    private val displays = listOf(
        AndroidStreamDisplayTarget.Candidate(displayId = 0, width = 1920, height = 1080),
        AndroidStreamDisplayTarget.Candidate(displayId = 2, width = 1280, height = 720),
        AndroidStreamDisplayTarget.Candidate(displayId = 3, width = 2560, height = 1600),
    )

    @Test
    fun autoKeepsFirstExternalDisplayForBackCompat() {
        assertEquals(2, AndroidStreamDisplayTarget.select(displays, 0, AndroidStreamDisplayTarget.AUTO)?.displayId)
    }

    @Test
    fun primaryChoosesDefaultDisplayEvenWhenExternalDisplaysExist() {
        assertEquals(0, AndroidStreamDisplayTarget.select(displays, 0, AndroidStreamDisplayTarget.PRIMARY)?.displayId)
    }

    @Test
    fun externalChoosesFirstNonDefaultDisplay() {
        assertEquals(2, AndroidStreamDisplayTarget.select(displays, 0, AndroidStreamDisplayTarget.EXTERNAL)?.displayId)
    }

    @Test
    fun largestChoosesDisplayWithLargestPixelArea() {
        assertEquals(3, AndroidStreamDisplayTarget.select(displays, 0, AndroidStreamDisplayTarget.LARGEST)?.displayId)
    }

    @Test
    fun externalReturnsNullWhenOnlyPrimaryExists() {
        assertNull(AndroidStreamDisplayTarget.select(displays.take(1), 0, AndroidStreamDisplayTarget.EXTERNAL))
    }

    @Test
    fun companionForThorLargestStreamUsesSmallerNonStreamDisplay() {
        val thorDisplays = listOf(
            AndroidStreamDisplayTarget.Candidate(displayId = 0, width = 1920, height = 1080),
            AndroidStreamDisplayTarget.Candidate(displayId = 4, width = 1240, height = 1080),
        )

        val stream = AndroidStreamDisplayTarget.select(
            thorDisplays,
            defaultDisplayId = 0,
            target = AndroidStreamDisplayTarget.LARGEST,
        )
        val companion = AndroidStreamDisplayTarget.selectCompanion(
            thorDisplays,
            defaultDisplayId = 0,
            streamDisplayId = stream?.displayId,
        )

        assertEquals(0, stream?.displayId)
        assertEquals(4, companion?.displayId)
    }

    @Test
    fun companionForExternalStreamUsesPrimaryDeviceDisplay() {
        val displays = listOf(
            AndroidStreamDisplayTarget.Candidate(displayId = 0, width = 1920, height = 1080),
            AndroidStreamDisplayTarget.Candidate(displayId = 4, width = 1240, height = 1080),
        )

        val stream = AndroidStreamDisplayTarget.select(
            displays,
            defaultDisplayId = 0,
            target = AndroidStreamDisplayTarget.EXTERNAL,
        )
        val companion = AndroidStreamDisplayTarget.selectCompanion(
            displays,
            defaultDisplayId = 0,
            streamDisplayId = stream?.displayId,
        )

        assertEquals(4, stream?.displayId)
        assertEquals(0, companion?.displayId)
    }

    @Test
    fun companionIsNullWhenOnlyTheStreamDisplayExists() {
        val displays = listOf(
            AndroidStreamDisplayTarget.Candidate(displayId = 0, width = 1920, height = 1080),
        )

        assertNull(
            AndroidStreamDisplayTarget.selectCompanion(
                displays,
                defaultDisplayId = 0,
                streamDisplayId = 0,
            )
        )
    }

    @Test
    fun companionPrefersSmallestNonStreamDisplayWhenSeveralRemain() {
        val displays = listOf(
            AndroidStreamDisplayTarget.Candidate(displayId = 0, width = 1920, height = 1080),
            AndroidStreamDisplayTarget.Candidate(displayId = 4, width = 1240, height = 1080),
            AndroidStreamDisplayTarget.Candidate(displayId = 7, width = 3840, height = 2160),
        )

        val companion = AndroidStreamDisplayTarget.selectCompanion(
            displays,
            defaultDisplayId = 0,
            streamDisplayId = 0,
        )

        assertEquals(4, companion?.displayId)
    }

    @Test
    fun displayLaunchTrampolineIsNeededForPrimaryStreamWhenCallerIsOnCompanionDisplay() {
        assertTrue(
            AndroidStreamDisplayTarget.shouldUseDisplayLaunchTrampoline(
                selectedDisplayId = 0,
                currentDisplayId = 4,
                companionDisplayId = 4,
            )
        )
    }

    @Test
    fun displayLaunchTrampolineIsNotNeededForPrimaryStreamWhenCallerIsAlreadyOnPrimaryDisplay() {
        assertFalse(
            AndroidStreamDisplayTarget.shouldUseDisplayLaunchTrampoline(
                selectedDisplayId = 0,
                currentDisplayId = 0,
                companionDisplayId = 4,
            )
        )
    }

    @Test
    fun displayLaunchTrampolineIsNotNeededForPrimaryStreamWithoutCompanionDisplay() {
        assertFalse(
            AndroidStreamDisplayTarget.shouldUseDisplayLaunchTrampoline(
                selectedDisplayId = 0,
                currentDisplayId = 4,
                companionDisplayId = null,
            )
        )
    }

    @Test
    fun displayLaunchTrampolineIsNeededForExternalStreamWhenCallerIsOnPrimaryDisplay() {
        assertTrue(
            AndroidStreamDisplayTarget.shouldUseDisplayLaunchTrampoline(
                selectedDisplayId = 4,
                currentDisplayId = 0,
                companionDisplayId = 0,
            )
        )
    }

    @Test
    fun displayLaunchTrampolineIsNotNeededForExternalStreamWhenCallerIsAlreadyOnExternalDisplay() {
        assertFalse(
            AndroidStreamDisplayTarget.shouldUseDisplayLaunchTrampoline(
                selectedDisplayId = 4,
                currentDisplayId = 4,
                companionDisplayId = 0,
            )
        )
    }
}
