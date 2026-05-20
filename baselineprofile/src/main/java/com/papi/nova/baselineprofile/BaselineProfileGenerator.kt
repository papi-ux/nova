package com.papi.nova.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
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
        launchHome()
    }

    @Test
    fun librarySurface() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME
    ) {
        launchHome()
        openLibrarySurface()
    }

    @Test
    fun libraryDetailSurface() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME
    ) {
        launchHome()
        openLibrarySurface()
        device.pressDPadCenter()
        device.waitForIdle()
        device.pressDPadRight()
        device.pressDPadLeft()
        device.pressBack()
        device.waitForIdle()
    }

    @Test
    fun settingsSurface() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME
    ) {
        pressHome()
        startActivityAndWait(streamSettingsIntent())
        device.waitForIdle()
        device.wait(Until.hasObject(By.textContains("Settings")), WAIT_TIMEOUT_MS)
        device.pressDPadDown()
        device.pressDPadDown()
        device.pressDPadUp()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.launchHome() {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.openLibrarySurface() {
        device.wait(Until.hasObject(By.text("Library")), WAIT_TIMEOUT_MS)
        device.pressDPadRight()
        device.pressDPadLeft()
        device.pressDPadCenter()
        device.wait(Until.hasObject(By.textContains("Library")), WAIT_TIMEOUT_MS)
        device.waitForIdle()
    }

    private fun streamSettingsIntent(): Intent {
        return Intent().setClassName(PACKAGE_NAME, "$PACKAGE_NAME.preferences.StreamSettings")
    }

    companion object {
        private const val PACKAGE_NAME = "com.papi.nova"
        private const val WAIT_TIMEOUT_MS = 5_000L
    }
}
