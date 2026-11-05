package com.papi.nova.nvstream.av.video

abstract class VideoDecoderRenderer {
    abstract fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int

    abstract fun start()

    abstract fun stop()

    // This is called once for each frame-start NALU. This means it will be called several times
    // for an IDR frame which contains several parameter sets and the I-frame data.
    //
    // receiveTimeMs and enqueueTimeMs are this device's monotonic milliseconds, as the streaming
    // library measured them. rtpTimestamp is the host's: the frame's raw RTP timestamp in 90 kHz
    // ticks modulo 2^32, carried in a Long so it is never negative. It is never synthesized from
    // the receive time, zero is a legal value at a wrap, and it is not a clock on its own; whether
    // a run of them is the source's real cadence is for the source-time mapper to decide.
    abstract fun submitDecodeUnit(
        decodeUnitData: ByteArray?,
        decodeUnitLength: Int,
        decodeUnitType: Int,
        frameNumber: Int,
        frameType: Int,
        frameHostProcessingLatency: Char,
        receiveTimeMs: Long,
        enqueueTimeMs: Long,
        rtpTimestamp: Long,
    ): Int

    abstract fun cleanup()

    abstract fun getCapabilities(): Int

    abstract fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?)
}
