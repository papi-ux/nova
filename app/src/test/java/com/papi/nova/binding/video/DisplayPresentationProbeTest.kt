package com.papi.nova.binding.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.binding.video.sourcetime.ArrSupport
import com.papi.nova.binding.video.sourcetime.SourceTimeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DisplayPresentationProbeTest {
    @Test
    fun anAndroid13DisplayIsReadAsFixedRefreshAndNeverAskedForArr() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val capability = DisplayPresentationProbe.read(display)
        assertEquals(33, capability.facts.sdkInt)
        assertNull("API 33 has no hasArrSupport() to call", capability.facts.hasArrSupport)
        assertEquals(ArrSupport.UNSUPPORTED, capability.arr)
        assertEquals(SourceTimeReason.DISPLAY_FIXED_REFRESH, capability.reason)
        assertTrue(capability.facts.modeRefreshHz > 0f)
    }

    @Test
    fun aMissingDisplayGivesNoAnswer() {
        val capability = DisplayPresentationProbe.read(null)
        assertEquals(0f, capability.facts.modeRefreshHz)
        assertNull(capability.facts.hasArrSupport)
    }

    @Test
    fun theProbeOnlyReadsAndAsksAtTheApiThatHasTheCall() {
        val source = File("src/main/java/com/papi/nova/binding/video/DisplayPresentationProbe.kt").readText()
        for (write in listOf("setFrameRate", "setRequestedFrameRate", "preferredDisplayModeId", "clearFrameRate")) {
            assertTrue("the probe must not $write", !source.contains(write))
        }
        val guarded = source.substringAfter("if (Build.VERSION.SDK_INT >= 36) {").substringBefore("\n        }\n")
        assertTrue(guarded.contains("display.hasArrSupport()"))
        assertTrue(guarded.contains("FRAME_RATE_CATEGORY_HIGH"))
        assertEquals(1, Regex("""hasArrSupport\(\)""").findAll(source).count())
    }
}
