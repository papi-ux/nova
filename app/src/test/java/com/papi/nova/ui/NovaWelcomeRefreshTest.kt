package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaWelcomeRefreshTest {
    @Test
    fun welcomeLayoutsExposeThreeControllerActions() {
        // Landscape takes its action row from nova_welcome_actions, which has a variant
        // for screens with the height to stack it; every variant carries all three.
        val layouts = arrayOf(
            "src/main/res/layout/activity_nova_welcome.xml",
            "src/main/res/layout/nova_welcome_actions.xml",
            "src/main/res/layout-h420dp/nova_welcome_actions.xml",
        )
        assertTrue(
            "landscape welcome should take its actions from nova_welcome_actions",
            readFile("src/main/res/layout-land/activity_nova_welcome.xml").contains("@layout/nova_welcome_actions"),
        )

        for (layout in layouts) {
            val xml = readFile(layout)
            assertTrue("$layout should expose Discover hosts", xml.contains("@+id/welcome_discover_btn"))
            assertTrue("$layout should expose Add manually", xml.contains("@+id/welcome_add_manual_btn"))
            assertTrue("$layout should expose Scan QR", xml.contains("@+id/welcome_scan_qr_btn"))
            assertTrue("$layout primary action should be D-pad focusable", buttonBlock(xml, "welcome_discover_btn").contains("android:focusable=\"true\""))
            assertTrue("$layout manual action should be D-pad focusable", buttonBlock(xml, "welcome_add_manual_btn").contains("android:focusable=\"true\""))
            assertTrue("$layout QR action should be D-pad focusable", buttonBlock(xml, "welcome_scan_qr_btn").contains("android:focusable=\"true\""))
        }
    }

    @Test
    fun narrowWelcomeGivesThePrimaryActionTheFullWidth() {
        val stacked = readFile("src/main/res/layout-h420dp/nova_welcome_actions.xml")
        assertTrue(
            "on the 4:3 Retroid Pocket Nova three buttons in one row left each about 100dp and " +
                "Discover hosts wrapped onto three lines, the last clipped",
            buttonBlock(stacked, "welcome_discover_btn").contains("android:layout_width=\"match_parent\""),
        )
        assertTrue(
            "the row under Discover hosts reaches back up to it with the D-pad",
            buttonBlock(stacked, "welcome_add_manual_btn").contains("android:nextFocusUp=\"@id/welcome_discover_btn\"") &&
                buttonBlock(stacked, "welcome_scan_qr_btn").contains("android:nextFocusUp=\"@id/welcome_discover_btn\""),
        )
    }

    @Test
    fun welcomeCopyStaysScopedToVerifiedFlows() {
        val portrait = readFile("src/main/res/layout/activity_nova_welcome.xml")
        val landscape = readFile("src/main/res/layout-land/activity_nova_welcome.xml")
        val copy = portrait + landscape

        assertTrue("welcome should mention Polaris", copy.contains("Polaris"))
        assertTrue("welcome should mention Moonlight compatibility", copy.contains("Moonlight-compatible") || copy.contains("Moonlight pairing"))
        assertTrue("welcome should include Android TV players", copy.contains("Android TVs"))
        assertTrue("welcome should include handheld players", copy.contains("handhelds"))
        assertTrue("welcome should include tablet players", copy.contains("tablets"))
        assertTrue("welcome should include phone players", copy.contains("phones"))
        assertFalse("welcome should not position Nova as a handheld-only product", copy.contains("Polaris, handhelds, TV"))
        assertTrue("welcome should frame QR as Polaris pairing only", copy.contains("Polaris pairing QR"))
        assertFalse("welcome should not overclaim automatic QR or TOFU pairing", copy.contains("TOFU auto-pair"))
        assertFalse("welcome should not overclaim AI tuning", copy.contains("AI-optimized"))
    }

    @Test
    fun welcomeActivityKeepsSeenFlagAndRoutesActions() {
        val welcomeSource = readFile("src/main/java/com/papi/nova/ui/NovaWelcomeActivity.kt")
        val pcViewSource = readFile("src/main/java/com/papi/nova/PcView.kt")

        assertTrue("welcome_seen should still be persisted", welcomeSource.contains("KEY_WELCOME_SEEN") && welcomeSource.contains("putBoolean(KEY_WELCOME_SEEN, true)"))
        assertTrue("welcome completion should still commit synchronously before leaving", welcomeSource.contains(".commit()"))
        assertTrue("manual add action should use the existing manual add screen", welcomeSource.contains("AddComputerManually::class.java"))
        assertTrue("QR action should be explicit", welcomeSource.contains("EXTRA_WELCOME_ACTION") && welcomeSource.contains("ACTION_SCAN_QR"))
        assertTrue("PcView should handle the welcome QR action through the wired scanner", pcViewSource.contains("handleWelcomeAction") && pcViewSource.contains("launchQrScanner()"))
    }

    private fun buttonBlock(xml: String, id: String): String {
        val idIndex = xml.indexOf("@+id/$id")
        assertTrue("missing $id", idIndex >= 0)
        val start = xml.lastIndexOf('<', idIndex).coerceAtLeast(0)
        val end = xml.indexOf("/>", idIndex).let { if (it >= 0) it + 2 else xml.length }
        return xml.substring(start, end)
    }

    private fun readFile(path: String): String = File(path).readText()
}
