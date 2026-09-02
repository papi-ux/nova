package com.papi.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NovaTvBrandingSourceTest {
    private val pathDataPattern = Regex("""android:pathData="([^"]+)"""")

    @Test
    fun leanbackBannerUsesApprovedPolarisPathFamily() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val launcher = File("src/main/res/drawable/ic_nova_star_foreground.xml").readText()
        val tvBanner = File("src/main/res/drawable/nova_tv_banner.xml").readText()
        val atvAlias = File("src/main/res/drawable/nova_atv_banner.xml").readText()

        assertTrue(manifest.contains("android:banner=\"@drawable/nova_tv_banner\""))

        val approvedPaths = pathDataPattern.findAll(launcher)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(8, approvedPaths.size)
        approvedPaths.forEach { approvedPath ->
            assertTrue("TV banner is missing an approved Polaris path", tvBanner.contains(approvedPath))
        }

        assertFalse(tvBanner.contains("M60,10 Q63,52 100,60"))
        assertTrue(tvBanner.contains("android:width=\"320dp\""))
        assertTrue(tvBanner.contains("android:height=\"180dp\""))
        assertEquals(tvBanner, atvAlias)
    }

    @Test
    fun debugBuildUsesAnExplicitlyBadgedLauncherAndTvBanner() {
        val manifest = File("src/debug/AndroidManifest.xml").readText()
        val launcher = File("src/debug/res/mipmap-anydpi-v26/nova_debug_launcher.xml").readText()
        val launcherForeground = File("src/debug/res/drawable/nova_debug_launcher_foreground.xml").readText()
        val tvBanner = File("src/debug/res/drawable/nova_debug_tv_banner.xml").readText()
        val tvBadge = File("src/debug/res/drawable/nova_debug_tv_badge.xml").readText()

        assertTrue(manifest.contains("android:icon=\"@mipmap/nova_debug_launcher\""))
        assertTrue(manifest.contains("android:banner=\"@drawable/nova_debug_tv_banner\""))
        assertTrue(launcher.contains("@drawable/nova_debug_launcher_foreground"))
        assertTrue(launcherForeground.contains("@drawable/ic_nova_star_foreground"))
        assertTrue(launcherForeground.contains("@drawable/nova_debug_launcher_badge"))
        assertTrue(tvBanner.contains("@drawable/nova_tv_banner"))
        assertTrue(tvBanner.contains("@drawable/nova_debug_tv_badge"))
        assertTrue(tvBadge.contains("#FFF4B266"))
    }
}
