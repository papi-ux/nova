package com.papi.nova.ui.panel

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On the Shield only panels kept the television title-safe area: the library header, the first
 * poster column, the HUD, the host list, the game page and Settings sat 9 to 35dp from the edge.
 */
class NovaTitleSafeTest {
    @Test
    fun aTelevisionKeepsTheTitleSafeAreaAndAHandheldItsOwnMargin() {
        val tv = novaScreenPadding(12.dp, television = true)
        assertEquals(48.dp, tv.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(48.dp, tv.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(27.dp, tv.calculateTopPadding())
        assertEquals(27.dp, tv.calculateBottomPadding())
        assertEquals(PaddingValues(12.dp), novaScreenPadding(12.dp, television = false))
    }

    @Test
    fun everyFullScreenSurfaceUsesIt() {
        fun read(path: String) = File("src/main/java/com/papi/nova/$path").readText()
        assertTrue(read("ui/NovaLibraryActivity.kt").contains("novaScreenPadding(NovaLibraryUiStateMapper.screenPaddingDp(isLandscape).dp)"))
        assertTrue(read("ui/NovaGameDetailDestinations.kt").contains("if (television) com.papi.nova.ui.panel.NovaPanelMetrics.TvSafeHorizontal"))
        val helper = read("utils/UiHelper.kt")
        assertTrue(helper.contains("TV_VERTICAL_PADDING_DP = 27") && helper.contains("TV_HORIZONTAL_PADDING_DP = 48"))
        assertTrue(read("ui/NovaStreamHud.kt").contains("private fun hudMarginPx(horizontal: Boolean): Float"))
    }
}
