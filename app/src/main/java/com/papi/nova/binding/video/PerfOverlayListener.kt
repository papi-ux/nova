package com.papi.nova.binding.video

interface PerfOverlayListener {
    fun onPerfUpdate(text: String)

    fun onPerfSample(sample: PerfOverlaySample) {
    }
}
