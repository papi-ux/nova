package com.papi.nova.grid.assets

import android.graphics.Bitmap

class ScaledBitmap(
    @JvmField var originalWidth: Int = 0,
    @JvmField var originalHeight: Int = 0,
    @JvmField var bitmap: Bitmap? = null
)
