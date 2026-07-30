package com.papi.nova.utils

import android.util.DisplayMetrics
import android.view.Display

object AndroidDisplayCandidateAdapter {
    @Suppress("DEPRECATION")
    fun from(display: Display): AndroidStreamDisplayTarget.Candidate {
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return AndroidStreamDisplayTarget.Candidate(
            displayId = display.displayId,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
        )
    }
}
