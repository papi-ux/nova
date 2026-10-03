package com.papi.nova.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class NovaCameraGeometryTest {
    private val landscape = Rect(0f, 0f, 900f, 400f)
    private fun padding(target: Rect, camera: Rect, window: Rect = landscape, floor: Float = 0f) =
        novaCameraPadding(target, NovaCameraWindow(window, listOf(camera)), floor)

    @Test fun noCameraLeavesTheRegionAtTheEdge() {
        assertEquals(NovaCameraPadding(), novaCameraPadding(Rect(8f, 8f, 240f, 56f), NovaCameraWindow(landscape, emptyList())))
    }
    @Test fun sideHoleMovesOnlyTheRowAtItsHeight() {
        val camera = Rect(0f, 180f, 32f, 220f)
        assertEquals(NovaCameraPadding(), padding(Rect(8f, 80f, 240f, 128f), camera))
        assertEquals(NovaCameraPadding(left = 24f), padding(Rect(8f, 180f, 240f, 228f), camera))
        assertEquals(NovaCameraPadding(), padding(Rect(8f, 240f, 240f, 288f), camera))
    }
    @Test fun reverseLandscapeUsesThePhysicalRightEdge() {
        assertEquals(NovaCameraPadding(right = 24f), padding(Rect(660f, 180f, 892f, 228f), Rect(868f, 180f, 900f, 220f)))
    }
    @Test fun centralPortraitHoleLeavesSideControlsBesideIt() {
        val window = Rect(0f, 0f, 412f, 915f)
        val hole = Rect(186f, 0f, 226f, 80f)
        assertEquals(NovaCameraPadding(), padding(Rect(12f, 12f, 140f, 60f), hole, window))
        assertEquals(NovaCameraPadding(), padding(Rect(280f, 12f, 400f, 60f), hole, window))
        assertEquals(NovaCameraPadding(top = 68f), padding(Rect(170f, 12f, 242f, 60f), hole, window))
    }
    @Test fun fullWidthNotchLegitimatelyClearsTheHeaderRegion() {
        assertEquals(NovaCameraPadding(top = 68f), padding(Rect(12f, 12f, 400f, 60f), Rect(0f, 0f, 412f, 80f), Rect(0f, 0f, 412f, 915f)))
    }
    @Test fun upsideDownPortraitUsesThePhysicalBottom() {
        assertEquals(NovaCameraPadding(bottom = 68f), padding(Rect(170f, 855f, 242f, 903f), Rect(186f, 835f, 226f, 915f), Rect(0f, 0f, 412f, 915f)))
    }
    @Test fun windowOriginIsNotAssumedToBeTheDisplayOrigin() {
        assertEquals(NovaCameraPadding(left = 24f), padding(Rect(58f, 200f, 290f, 248f), Rect(50f, 200f, 82f, 240f), Rect(50f, 20f, 950f, 420f)))
    }
    @Test fun narrowControlKeepsIts48PixelFloorAndGrowsBelowTheHole() {
        assertEquals(NovaCameraPadding(top = 40f), padding(Rect(8f, 180f, 56f, 228f), Rect(0f, 180f, 80f, 220f), floor = 48f))
    }
    @Test fun separateCamerasKeepTheirOwnPhysicalEdges() {
        assertEquals(NovaCameraPadding(left = 24f, right = 24f), novaCameraPadding(Rect(8f, 180f, 892f, 228f),
            NovaCameraWindow(landscape, listOf(Rect(0f, 180f, 32f, 220f), Rect(868f, 180f, 900f, 220f)))))
    }
    @Test fun emptyBoundsAndAnAdjacentNonoverlappingCameraAddNothing() {
        assertEquals(NovaCameraPadding(), padding(Rect.Zero, Rect(0f, 0f, 80f, 80f)))
        assertEquals(NovaCameraPadding(), padding(Rect(32f, 180f, 240f, 228f), Rect(0f, 180f, 32f, 220f)))
    }
}
