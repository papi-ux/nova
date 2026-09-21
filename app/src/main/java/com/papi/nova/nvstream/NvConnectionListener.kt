package com.papi.nova.nvstream

interface NvConnectionListener {
    fun stageStarting(stage: String)

    fun stageComplete(stage: String)

    fun stageFailed(stage: String, portFlags: Int, errorCode: Int): Boolean

    fun connectionStarted()

    fun connectionTerminated(errorCode: Int)

    fun connectionStatusUpdate(connectionStatus: Int)

    fun displayMessage(message: String)

    fun displayTransientMessage(message: String)

    /**
     * The stream will not be the size this device asked for: it is watching someone else's.
     * The screen fits its picture to the stream's shape, which it took from its own settings.
     */
    fun streamModeAdopted(width: Int, height: Int) {}

    fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short)

    fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short)

    fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?)

    fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short)

    fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte)
}
