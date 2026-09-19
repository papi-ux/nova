package com.papi.nova

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nova draws behind the system bars on every API level, with the bars transparent.
 * A screen that pads its whole root pushes its background off the bars too, and a
 * screen that pads nothing puts its first line under the clock.
 */
class EdgeToEdgeSourceGuardTest {

    @Test
    fun dashboardKeepsItsBackgroundUnderTheBarsAndOnlyItsControlsClear() {
        val pcView = readSource("src/main/java/com/papi/nova/PcView.kt")
        assertTrue(
            "the dashboard pads its content layout, not the root: padding the root kept the particle " +
                "field and background below the status bar on every device",
            pcView.contains("UiHelper.notifyNewRootView(\n            this,\n            findViewById<View>(R.id.dashboardCockpit)"),
        )
        assertTrue(
            "the portrait layout names its content layout so it can be padded alone",
            readSource("src/main/res/layout/activity_pc_view.xml").contains("android:id=\"@+id/dashboardContent\""),
        )
        assertTrue(
            "the landscape layout keeps the cockpit id the dashboard pads",
            readSource("src/main/res/layout-land/activity_pc_view.xml").contains("android:id=\"@+id/dashboardCockpit\""),
        )
    }

    @Test
    fun screensWithoutTheirOwnInsetHandlingPadForTheBars() {
        for (path in listOf(
            "src/main/java/com/papi/nova/HelpActivity.kt",
            "src/main/java/com/papi/nova/DebugInfoActivity.kt",
            "src/main/java/com/papi/nova/ui/NovaWelcomeActivity.kt",
            "src/main/java/com/papi/nova/ui/NovaQrScanActivity.kt",
        )) {
            val source = readSource(path)
            val content = source.indexOf("setContentView(")
            val pad = source.indexOf("UiHelper.padContentForSystemBars(this)")
            assertTrue(
                "$path relied on a painted status bar to hide what sat under it; with transparent bars " +
                    "it pads its content after setting it",
                content >= 0 && pad > content,
            )
        }
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)
}
