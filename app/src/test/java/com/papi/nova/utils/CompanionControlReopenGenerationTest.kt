package com.papi.nova.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionControlReopenGenerationTest {
    @Test
    fun hideInvalidatesADelayedReopen() {
        val generations = CompanionControlReopenGeneration()
        val notificationTap = generations.beginRequest()

        assertTrue(generations.isCurrent(notificationTap))
        generations.invalidatePendingRequests()
        assertFalse(generations.isCurrent(notificationTap))
    }

    @Test
    fun laterNotificationTapSupersedesEarlierTap() {
        val generations = CompanionControlReopenGeneration()
        val firstTap = generations.beginRequest()
        val secondTap = generations.beginRequest()

        assertFalse(generations.isCurrent(firstTap))
        assertTrue(generations.isCurrent(secondTap))
    }
}
