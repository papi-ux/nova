package com.papi.nova.shadows

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLegacyCanvas

/**
 * The legacy canvas, which also keeps the rounded rectangles drawn by their edges: Compose draws
 * a rounded fill, and a rounded border, that way, and the legacy canvas kept only the ones drawn by
 * a rectangle. A test draws a view into a canvas and reads what was drawn, in legacy graphics,
 * never native graphics, which ends the suite's JVM.
 */
@Implements(Canvas::class)
class ShadowDrawRecordingCanvas : ShadowLegacyCanvas() {
    /** One rounded rectangle drawn: where, and with what paint, copied as it was then. */
    class RoundRect(val rect: RectF, val paint: Paint)

    val roundRects = mutableListOf<RoundRect>()

    @Implementation
    protected fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) {
        roundRects += RoundRect(RectF(left, top, right, bottom), Paint(paint))
    }
}
