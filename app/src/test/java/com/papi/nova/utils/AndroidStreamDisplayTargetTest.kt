package com.papi.nova.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
