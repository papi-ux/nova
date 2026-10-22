package com.papi.nova.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import com.papi.nova.LimeLog
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * USB transport for one 2026 Steam Controller channel: the wired controller's gamepad interface,
 * or one of the wireless Puck's per-controller HID interfaces.
 *
 * This only moves bytes. Lizard mode, motion, rumble refresh and the meaning of wireless
 * connect/disconnect reports belong to the protocol core, which receives every input report
 * unchanged. The service opens one connection per channel and hands it to this transport,
 * which then owns that connection and the one interface it claims.
 *
 * Interface selection, report IDs, report sizes and the SET_REPORT fallback are adapted from
 * moonlight-android PR #1619 (Antoniscool123/moonlight-android@6e83831, GPLv3,
 * SteamTritonController.java) and SDL_hidapi_steam_triton.c (libsdl-org/SDL@d0183f0, zlib
 * license, Copyright (C) 1997-2026 Sam Lantinga). Unlike #1619, Puck channels are never merged:
 * each physical controller gets its own transport.
 *
 * [uptimeMillis] is a test seam for telling an idle read timeout from a device error.
 */
internal class SteamControllerUsbTransport(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val interfaceId: Int,
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis
) : SteamControllerTransport {
    private enum class State { NEW, STARTING, RUNNING, CLOSED }

    private class PendingFeature(val report: ByteArray, val completion: (Boolean) -> Unit)

    private val lock = ReentrantLock()
    private val stateChanged = lock.newCondition()

    // Serializes observer callbacks from the input and output threads. Taken before lock, never after.
    private val callbackLock = Any()

    // Guarded by lock
    private var state = State.NEW
    private var observer: SteamControllerTransport.Observer? = null
    private var activeUsers = 0
    private var released = false
    private var claimedInterface: UsbInterface? = null
    private val pendingFeatures = ArrayDeque<PendingFeature>()
    private var pendingRumble: ByteArray? = null

    // Guarded by callbackLock
    private var readyDelivered = false

    // Written before the threads start
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    // Output thread only
    private var rumbleFailing = false

    /** Why [start] returned false, or null if it was closed while starting. */
    @Volatile
    var startFailure: SteamControllerTransport.Failure? = null
        private set

    override fun start(observer: SteamControllerTransport.Observer): Boolean {
        lock.withLock {
            if (state != State.NEW) {
                return false
            }
            state = State.STARTING
            activeUsers++
        }

        val failure = try {
            claimChannel() ?: launchThreads()
        } catch (_: SecurityException) {
            SteamControllerTransport.Failure.PERMISSION
        } catch (_: RuntimeException) {
            SteamControllerTransport.Failure.IO
        }

        val running = lock.withLock {
            val result = when {
                // Closed while starting
                state != State.STARTING -> false
                failure != null -> {
                    state = State.CLOSED
                    startFailure = failure
                    false
                }
                else -> {
                    this.observer = observer
                    state = State.RUNNING
                    true
                }
            }
            stateChanged.signalAll()
            result
        }

        if (failure != null) {
            LimeLog.warning("Steam Controller USB channel $interfaceId failed to start: $failure")
        }
        leave()
        return running
    }

    override fun writeFeature(report: ByteArray, completion: (Boolean) -> Unit) {
        val accepted = report.size == FEATURE_REPORT_SIZE && report[0] == FEATURE_REPORT_ID.toByte() &&
            lock.withLock {
                if (state != State.RUNNING || pendingFeatures.size >= MAX_PENDING_FEATURES) {
                    return@withLock false
                }
                pendingFeatures.addLast(PendingFeature(report.copyOf(), completion))
                stateChanged.signalAll()
                true
            }

        if (!accepted) {
            completion(false)
        }
    }

    override fun writeRumble(report: ByteArray) {
        if (report.size != RUMBLE_REPORT_SIZE || report[0] != RUMBLE_REPORT_ID.toByte()) {
            return
        }

        lock.withLock {
            if (state == State.RUNNING) {
                pendingRumble = report.copyOf()
                stateChanged.signalAll()
            }
        }
    }

    override fun close() {
        val cancelled = lock.withLock {
            if (state == State.CLOSED) {
                return
            }
            closeLocked()
        }

        for (feature in cancelled) {
            feature.completion(false)
        }

        // A thread still inside a transfer releases the channel once it returns
        releaseIfUnused()
    }

    /** Validates and claims this channel's interface. Returns the failure, or null once claimed. */
    private fun claimChannel(): SteamControllerTransport.Failure? {
        if (interfaceId !in candidateInterfaceIds(device)) {
            return SteamControllerTransport.Failure.PROTOCOL
        }

        val iface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == interfaceId && isChannelInterface(device, it) }
            ?: return SteamControllerTransport.Failure.PROTOCOL

        // Force the claim so the kernel's keyboard and mouse emulation lets go of this interface
        if (!connection.claimInterface(iface, true)) {
            return SteamControllerTransport.Failure.IO
        }

        lock.withLock { claimedInterface = iface }
        inEndpoint = findEndpoint(iface, UsbConstants.USB_DIR_IN)
        outEndpoint = findEndpoint(iface, UsbConstants.USB_DIR_OUT)
        return null
    }

    private fun launchThreads(): SteamControllerTransport.Failure? {
        lock.withLock {
            // Closed while claiming: start() reports it and releases the claim
            if (state != State.STARTING) {
                return null
            }
        }

        val threads = listOf(
            Thread({ readInput() }, "SteamControllerUsb-in-$interfaceId"),
            Thread({ writeOutput() }, "SteamControllerUsb-out-$interfaceId")
        )

        for (thread in threads) {
            lock.withLock { activeUsers++ }
            try {
                thread.start()
            } catch (_: OutOfMemoryError) {
                // Any thread already started sees the failed start and exits
                lock.withLock { activeUsers-- }
                return SteamControllerTransport.Failure.IO
            }
        }
        return null
    }

    private fun readInput() {
        try {
            val running = lock.withLock {
                while (state == State.STARTING) {
                    stateChanged.await()
                }
                state == State.RUNNING
            }
            if (!running) {
                return
            }

            deliver {
                readyDelivered = true
                it.onReady()
            }

            val endpoint = inEndpoint!!
            val buffer = ByteArray(maxOf(FEATURE_REPORT_SIZE, endpoint.maxPacketSize))
            var errors = 0
            while (lock.withLock { state == State.RUNNING }) {
                val begin = uptimeMillis()
                val length = connection.bulkTransfer(endpoint, buffer, buffer.size, READ_TIMEOUT_MS)
                if (length > 0) {
                    errors = 0
                    val report = buffer.copyOf(length)
                    deliver { it.onReport(report) }
                } else if (uptimeMillis() - begin >= READ_TIMEOUT_MIN_ELAPSED_MS) {
                    // An idle timeout: a Puck channel without a controller is silent
                    errors = 0
                } else if (++errors >= MAX_CONSECUTIVE_READ_ERRORS) {
                    fail(SteamControllerTransport.Failure.IO)
                    return
                } else {
                    Thread.sleep(READ_ERROR_BACKOFF_MS)
                }
            }
        } catch (_: SecurityException) {
            fail(SteamControllerTransport.Failure.PERMISSION)
        } catch (_: InterruptedException) {
            fail(SteamControllerTransport.Failure.IO)
        } catch (e: RuntimeException) {
            LimeLog.warning("Steam Controller USB channel $interfaceId input failed: ${e.javaClass.simpleName}")
            fail(SteamControllerTransport.Failure.IO)
        } finally {
            leave()
        }
    }

    private fun writeOutput() {
        var inFlight: PendingFeature? = null
        try {
            while (true) {
                var rumble: ByteArray? = null
                var feature: PendingFeature? = null
                lock.withLock {
                    while (state != State.CLOSED && pendingRumble == null && pendingFeatures.isEmpty()) {
                        stateChanged.await()
                    }
                    if (state == State.CLOSED) {
                        return
                    }

                    // Take one of each so that a constantly refreshed rumble cannot starve features
                    rumble = pendingRumble
                    pendingRumble = null
                    feature = pendingFeatures.removeFirstOrNull()
                }

                // The feature left the queue alongside rumble. If that earlier transfer
                // throws, finally must still complete the accepted feature exactly once.
                inFlight = feature
                rumble?.let { sendRumble(it) }
                feature?.let {
                    val written = sendFeature(it.report)
                    inFlight = null
                    it.completion(written)
                }
            }
        } catch (_: SecurityException) {
            fail(SteamControllerTransport.Failure.PERMISSION)
        } catch (_: InterruptedException) {
            fail(SteamControllerTransport.Failure.IO)
        } catch (e: RuntimeException) {
            LimeLog.warning("Steam Controller USB channel $interfaceId output failed: ${e.javaClass.simpleName}")
            fail(SteamControllerTransport.Failure.IO)
        } finally {
            inFlight?.let { it.completion(false) }
            leave()
        }
    }

    private fun sendFeature(report: ByteArray): Boolean {
        val written = connection.controlTransfer(
            HID_REQUEST_TYPE_SET, HID_REQUEST_SET_REPORT,
            (HID_REPORT_TYPE_FEATURE shl 8) or FEATURE_REPORT_ID,
            interfaceId, report, report.size, FEATURE_TIMEOUT_MS
        )
        return written == report.size
    }

    private fun sendRumble(report: ByteArray) {
        val endpoint = outEndpoint
        val written = if (endpoint != null) {
            connection.bulkTransfer(endpoint, report, report.size, RUMBLE_TIMEOUT_MS)
        } else {
            connection.controlTransfer(
                HID_REQUEST_TYPE_SET, HID_REQUEST_SET_REPORT,
                (HID_REPORT_TYPE_OUTPUT shl 8) or RUMBLE_REPORT_ID,
                interfaceId, report, report.size, RUMBLE_TIMEOUT_MS
            )
        }

        // The core refreshes rumble continuously, so only log the first failure of a run
        val failed = written != report.size
        if (failed && !rumbleFailing) {
            LimeLog.warning("Steam Controller USB channel $interfaceId rumble transfer failed: $written")
        }
        rumbleFailing = failed
    }

    /** Delivers a callback unless the transport has closed. */
    private inline fun deliver(event: (SteamControllerTransport.Observer) -> Unit) {
        synchronized(callbackLock) {
            val target = lock.withLock { observer } ?: return
            event(target)
        }
    }

    /** Delivers the terminal failure once; later failures and failures after close are ignored. */
    private fun fail(failure: SteamControllerTransport.Failure) {
        var target: SteamControllerTransport.Observer? = null
        val cancelled = lock.withLock {
            if (state == State.CLOSED) {
                return
            }
            target = observer
            closeLocked()
        }

        LimeLog.warning("Steam Controller USB channel $interfaceId closed: $failure")
        target?.let {
            synchronized(callbackLock) {
                if (readyDelivered) {
                    it.onLinkLost()
                }
                it.onClosed(failure)
            }
        }

        for (feature in cancelled) {
            feature.completion(false)
        }
    }

    /** Must hold lock. Returns the queued feature writes, which the caller completes as failed. */
    private fun closeLocked(): List<PendingFeature> {
        state = State.CLOSED
        observer = null
        pendingRumble = null
        val cancelled = pendingFeatures.toList()
        pendingFeatures.clear()
        stateChanged.signalAll()
        return cancelled
    }

    private fun leave() {
        lock.withLock { activeUsers-- }
        releaseIfUnused()
    }

    /** Releases the claimed interface and the connection once closed and no thread can use them. */
    private fun releaseIfUnused() {
        val iface = lock.withLock {
            if (state != State.CLOSED || activeUsers > 0 || released) {
                return
            }
            released = true
            claimedInterface.also { claimedInterface = null }
        }

        try {
            if (iface != null && !connection.releaseInterface(iface)) {
                LimeLog.warning("Failed to release Steam Controller USB channel $interfaceId")
            }
        } catch (e: RuntimeException) {
            LimeLog.warning("Failed to release Steam Controller USB channel $interfaceId: ${e.javaClass.simpleName}")
        } finally {
            connection.close()
        }
    }

    companion object {
        private const val VALVE_VENDOR_ID = 0x28de
        private const val PRODUCT_ID_WIRED = 0x1302
        private const val PRODUCT_ID_PUCK = 0x1304

        // The Puck exposes one HID interface per paired controller
        private val PUCK_CHANNEL_INTERFACES = 2..5
        private const val HID_SUBCLASS_BOOT = 1

        private const val FEATURE_REPORT_ID = 1
        private const val FEATURE_REPORT_SIZE = 64
        private const val RUMBLE_REPORT_ID = 0x80
        private const val RUMBLE_REPORT_SIZE = 10

        private const val HID_REQUEST_TYPE_SET = 0x21 // Host to device, class, interface
        private const val HID_REQUEST_SET_REPORT = 0x09
        private const val HID_REPORT_TYPE_OUTPUT = 2
        private const val HID_REPORT_TYPE_FEATURE = 3

        private const val READ_TIMEOUT_MS = 1000

        // A failed read that returns well before its timeout is an error, not an idle channel
        private const val READ_TIMEOUT_MIN_ELAPSED_MS = 750L
        private const val MAX_CONSECUTIVE_READ_ERRORS = 3
        private const val READ_ERROR_BACKOFF_MS = 10L
        private const val FEATURE_TIMEOUT_MS = 1000
        private const val RUMBLE_TIMEOUT_MS = 100
        private const val MAX_PENDING_FEATURES = 8

        @JvmStatic
        fun canClaimDevice(device: UsbDevice): Boolean =
            device.vendorId == VALVE_VENDOR_ID &&
                (device.productId == PRODUCT_ID_WIRED || device.productId == PRODUCT_ID_PUCK)

        /**
         * The interfaces that each carry one physical controller, lowest first: every eligible
         * Puck channel, or the wired controller's single gamepad interface.
         */
        @JvmStatic
        fun candidateInterfaceIds(device: UsbDevice): List<Int> {
            if (!canClaimDevice(device)) {
                return emptyList()
            }

            val ids = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .filter { isChannelInterface(device, it) }
                .map { it.id }
                .distinct()
                .sorted()
            return if (device.productId == PRODUCT_ID_PUCK) ids else ids.take(1)
        }

        private fun isChannelInterface(device: UsbDevice, iface: UsbInterface): Boolean {
            if (findEndpoint(iface, UsbConstants.USB_DIR_IN) == null) {
                return false
            }

            if (device.productId == PRODUCT_ID_PUCK) {
                return iface.id in PUCK_CHANNEL_INTERFACES && iface.interfaceClass == UsbConstants.USB_CLASS_HID
            }

            // Skip the boot keyboard and mouse interfaces that lizard mode uses
            return (iface.interfaceClass == UsbConstants.USB_CLASS_HID ||
                iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC) &&
                iface.interfaceSubclass != HID_SUBCLASS_BOOT
        }

        private fun findEndpoint(iface: UsbInterface, direction: Int): UsbEndpoint? =
            (0 until iface.endpointCount)
                .map { iface.getEndpoint(it) }
                .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.direction == direction }
    }
}
