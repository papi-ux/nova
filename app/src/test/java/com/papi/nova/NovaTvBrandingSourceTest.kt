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
}
