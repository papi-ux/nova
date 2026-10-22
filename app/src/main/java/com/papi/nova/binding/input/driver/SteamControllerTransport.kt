package com.papi.nova.binding.input.driver

/** One physical Steam Controller channel, not all four channels of a wireless Puck. */
internal interface SteamControllerTransport {
    enum class Failure { PERMISSION, UNAVAILABLE, IO, PROTOCOL }

    interface Observer {
        /** The link can accept feature reports. A reconnect calls this again. */
        fun onReady()

        /** A complete, owned copy of one report, including its report ID. */
        fun onReport(report: ByteArray)

        /** Release input immediately, even when the transport will attempt to reconnect. */
        fun onLinkLost()

        /** Terminal outcome, delivered at most once. Explicit close need not deliver this. */
        fun onClosed(failure: Failure)
    }

    /** Local startup only; readiness and reports may arrive asynchronously. Never prompts. */
    fun start(observer: Observer): Boolean

    /**
     * The input includes the HID report ID. Completion means the complete feature write
     * succeeded, not merely that it entered a queue. Every accepted call completes once,
     * including failure/cancellation. A caller must tolerate completion after link loss.
     */
    fun writeFeature(report: ByteArray, completion: (Boolean) -> Unit)

    /** Replace any queued rumble with this state. A transport must never grow a rumble queue. */
    fun writeRumble(report: ByteArray)

    /** Actual ready-link output support; optional BLE characteristics need not exist. */
    fun supportsRumble(): Boolean = true

    /** Idempotent; cancels writes and releases only this channel's resources. */
    fun close()
}
