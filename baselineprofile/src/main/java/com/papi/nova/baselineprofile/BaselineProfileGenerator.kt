package com.papi.nova.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }

    @Test
    fun librarySurface() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        device.wait(Until.hasObject(By.text("Library")), WAIT_TIMEOUT_MS)
        device.pressDPadRight()
        device.pressDPadLeft()
        device.pressDPadCenter()
        device.wait(Until.hasObject(By.textContains("Library")), WAIT_TIMEOUT_MS)
        device.waitForIdle()
    }

    companion object {
        private const val PACKAGE_NAME = "com.papi.nova"
        private const val WAIT_TIMEOUT_MS = 5_000L
    }
}
