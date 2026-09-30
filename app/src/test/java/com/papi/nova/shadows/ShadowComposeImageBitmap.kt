package com.papi.nova.shadows

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.util.DisplayMetrics
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLegacyBitmap

/**
 * The legacy bitmap, which also makes the bitmap Compose caches a vector icon in: that one is made
 * with a colour space and no display, which the legacy bitmap left to the real code, and the real
 * code has no pixels to give it here. Lets a test draw a screen with icons into a
 * [ShadowDrawRecordingCanvas], in legacy graphics.
 */
@Implements(Bitmap::class)
class ShadowComposeImageBitmap : ShadowLegacyBitmap() {
    companion object {
        @JvmStatic
        @Implementation
        @Suppress("UNUSED_PARAMETER")
        fun createBitmap(
            display: DisplayMetrics?,
            width: Int,
            height: Int,
            config: Bitmap.Config,
            hasAlpha: Boolean,
            colorSpace: ColorSpace?,
        ): Bitmap = Bitmap.createBitmap(width, height, config)
    }
}
