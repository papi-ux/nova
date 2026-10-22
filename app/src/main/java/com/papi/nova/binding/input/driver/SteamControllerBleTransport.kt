/*
 * Portions are adapted from two upstream sources; the code here is Nova's own, altered from both.
 *
 * moonlight-android PR #1619 (Antoniscool123/moonlight-android@6e83831838b3, GPLv3),
 * SteamTritonBleController.java: the Valve GATT service, the input, feature and per-report output
 * characteristic UUIDs, and writing a report to its characteristic without the report ID byte.
 *
 * SDL (libsdl-org/SDL@d0183f0ee3b2) src/joystick/hidapi/steam/controller_structs.h and
 * SDL_hidapi_steam_triton.c: the Triton report IDs and the feature and rumble report sizes.
 * controller_structs.h carries this notice:
 *
 *   Simple DirectMedia Layer
 *   Copyright (C) 2020 Valve Corporation
 *
 *   This software is provided 'as-is', without any express or implied
 *   warranty.  In no event will the authors be held liable for any damages
 *   arising from the use of this software.
 *
 *   Permission is granted to anyone to use this software for any purpose,
 *   including commercial applications, and to alter it and redistribute it
 *   freely, subject to the following restrictions:
 *
 *   1. The origin of this software must not be misrepresented; you must not
 *      claim that you wrote the original software. If you use this software
 *      in a product, an acknowledgment in the product documentation would be
 *      appreciated but is not required.
 *   2. Altered source versions must be plainly marked as such, and must not be
 *      misrepresented as being the original software.
 *   3. This notice may not be removed or altered from any source distribution.
 */
package com.papi.nova.binding.input.driver

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.papi.nova.LimeLog
import com.papi.nova.binding.input.driver.SteamControllerTransport.Failure
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * One Bluetooth LE link to a 2026 Steam Controller the caller selected from the bonded devices.
 *
 * The controller does not use HID over GATT. Input reports arrive as notifications on a Valve
 * characteristic without their report ID, and each output or feature report is written to its own
 * characteristic, also without the ID. This transport only moves those reports; feature, lizard
 * mode and IMU policy belong to the caller.
 *
 * Every Bluetooth call, timeout, observer callback and write completion runs on one private
 * thread, and at most one GATT operation is outstanding. The transport never scans, pairs or
 * prompts, and it does not log or keep the device address.
 */
@SuppressLint("MissingPermission") // preflight() checks the permission and guarded() handles revocation
internal class SteamControllerBleTransport internal constructor(
    context: Context,
    private val device: BluetoothDevice,
    private val opener: GattOpener
) : SteamControllerTransport {
    constructor(context: Context, device: BluetoothDevice) : this(context, device, GattOpener.DIRECT)

    /** Opens a GATT client for the device. Only tests replace [DIRECT]. */
    internal fun interface GattOpener {
        fun open(context: Context, device: BluetoothDevice, callback: BluetoothGattCallback): BluetoothGatt?

        companion object {
            /** A direct connection, so a controller that never appears fails in bounded time. */
            val DIRECT = GattOpener { context, device, callback ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, callback)
                }
            }
        }
    }

    private enum class Step { CONNECT, DISCOVER, REFERENCE, MTU, SUBSCRIBE, FEATURE, RUMBLE }

    private class Op(val step: Step, val target: Any? = null, val write: FeatureWrite? = null)

    private class Route(val characteristic: BluetoothGattCharacteristic, val reportId: Int)

    private class FeatureWrite(val payload: ByteArray?, private val completion: (Boolean) -> Unit) {
        private val done = AtomicBoolean()

        fun complete(success: Boolean) {
            if (done.compareAndSet(false, true)) {
                completion(success)
            }
        }
    }

    /** One connection attempt. A reconnect gets a new one, so callbacks for an old one are dropped. */
    private class Link {
        var gatt: BluetoothGatt? = null
        var op: Op? = null
        var timer: Runnable? = null
        var ready = false
        var feature: BluetoothGattCharacteristic? = null
        var rumble: BluetoothGattCharacteristic? = null
        var rumbleWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var pendingRumble: ByteArray? = null
        val routes = ArrayList<Route>(2)
        val references = ArrayDeque<BluetoothGattDescriptor>()
        val subscriptions = ArrayDeque<Route>()
    }

    private val context = context.applicationContext
    private val lifecycleLock = Any()
    private var started = false
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var closed = false
    @Volatile private var finished = false

    // Owned by the transport thread
    private var observer: SteamControllerTransport.Observer? = null
    private var link: Link? = null
    private var failures = 0
    private val features = ArrayDeque<FeatureWrite>()

    private val latestRumble = AtomicReference<ByteArray?>()
    private val rumblePosted = AtomicBoolean()
    private val queuedReports = AtomicInteger()

    private val reconnect = Runnable { guarded { connect() } }

    override fun start(observer: SteamControllerTransport.Observer): Boolean {
        val created = synchronized(lifecycleLock) {
            if (started || closed) {
                return false
            }
            started = true
            this.observer = observer
            val thread = HandlerThread(THREAD_NAME).also { it.start() }
            this.thread = thread
            Handler(thread.looper).also { handler = it }
        }
        created.post { guarded { connect() } }
        return true
    }

    override fun writeFeature(report: ByteArray, completion: (Boolean) -> Unit) {
        val valid = report.size == FEATURE_REPORT_SIZE && report[0] == FEATURE_REPORT_ID.toByte()
        val write = FeatureWrite(if (valid) report.copyOfRange(1, report.size) else null, completion)
        if (closed || finished || !post { queueFeature(write) }) {
            write.complete(false)
        }
    }

    override fun writeRumble(report: ByteArray) {
        if (report.size != RUMBLE_REPORT_SIZE || report[0] != RUMBLE_REPORT_ID.toByte() || closed || finished) {
            return
        }
        latestRumble.set(report.copyOfRange(1, report.size))
        if (rumblePosted.compareAndSet(false, true) && !post { takeRumble() }) {
            rumblePosted.set(false)
        }
    }

    override fun close() {
        val owned = synchronized(lifecycleLock) {
            if (closed) {
                return
            }
            closed = true
            handler
        }
        // Never waits for the thread: close() may come from an observer callback on it
        if (!finished) {
            owned?.post { shutdown() }
        }
    }

    private fun post(block: () -> Unit): Boolean = handler?.post { guarded(block) } ?: false

    /** A revoked permission surfaces as SecurityException from any Bluetooth call. */
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            LimeLog.warning("Steam Controller BLE: Bluetooth permission was revoked")
            val current = link
            if (current != null) {
                fail(current, Failure.PERMISSION)
            } else {
                finish(Failure.PERMISSION)
            }
        }
    }

    private fun deliver(block: (SteamControllerTransport.Observer) -> Unit) {
        val target = observer
        if (target != null && !closed) {
            block(target)
        }
    }

    private fun preflight(): Failure? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return Failure.UNAVAILABLE
        }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        if (context.checkPermission(permission, Process.myPid(), Process.myUid()) != PackageManager.PERMISSION_GRANTED) {
            return Failure.PERMISSION
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled || device.bondState != BluetoothDevice.BOND_BONDED) {
            return Failure.UNAVAILABLE
        }
        return null
    }

    private fun connect() {
        if (closed || finished) {
            return
        }
        preflight()?.let {
            LimeLog.warning("Steam Controller BLE: cannot connect ($it)")
            finish(it)
            return
        }

        val next = Link()
        link = next
        next.op = Op(Step.CONNECT)
        val gatt = opener.open(context, device, LinkCallback(next))
        if (gatt == null) {
            fail(next, Failure.IO)
            return
        }
        next.gatt = gatt
        startTimer(next, CONNECT_TIMEOUT_MS, Failure.UNAVAILABLE)
    }

    private fun startTimer(link: Link, timeoutMs: Long, failure: Failure) {
        cancelTimer(link)
        val timer = Runnable {
            guarded {
                if (this.link === link) {
                    LimeLog.warning("Steam Controller BLE: ${link.op?.step} timed out")
                    fail(link, failure)
                }
            }
        }
        link.timer = timer
        handler?.postDelayed(timer, timeoutMs)
    }

    private fun cancelTimer(link: Link) {
        link.timer?.let { handler?.removeCallbacks(it) }
        link.timer = null
    }

    private fun begin(link: Link, op: Op, timeoutMs: Long, call: (BluetoothGatt) -> Boolean): Boolean {
        val gatt = link.gatt ?: return false
        link.op = op
        startTimer(link, timeoutMs, Failure.IO)
        if (call(gatt)) {
            return true
        }
        endOp(link)
        return false
    }

    private fun endOp(link: Link) {
        link.op = null
        cancelTimer(link)
    }

    private fun current(link: Link, gatt: BluetoothGatt) =
        !closed && !finished && this.link === link && link.gatt === gatt

    private fun onConnectionState(link: Link, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (link.op?.step != Step.CONNECT) {
                    return
                }
                endOp(link)
                if (status != BluetoothGatt.GATT_SUCCESS ||
                    !begin(link, Op(Step.DISCOVER), OP_TIMEOUT_MS) { it.discoverServices() }) {
                    fail(link, Failure.IO)
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                LimeLog.info("Steam Controller BLE: disconnected (status $status)")
                fail(link, if (status == GATT_ERROR) Failure.IO else Failure.UNAVAILABLE)
            }
        }
    }

    private fun onDiscovered(link: Link, gatt: BluetoothGatt, status: Int) {
        if (link.op?.step != Step.DISCOVER) {
            return
        }
        endOp(link)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(link, Failure.IO)
            return
        }
        val service = gatt.getService(SERVICE_UUID)
        if (service == null || !inspect(link, service)) {
            LimeLog.warning("Steam Controller BLE: the Valve GATT service is missing or incomplete")
            fail(link, Failure.PROTOCOL)
            return
        }
        readNextReference(link)
    }

    /** Validates the service before anything is written to it, and plans the input routes. */
    private fun inspect(link: Link, service: BluetoothGattService): Boolean {
        val feature = service.getCharacteristic(FEATURE_UUID)
        if (feature == null || feature.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            return false
        }
        link.feature = feature

        service.getCharacteristic(RUMBLE_UUID)?.let {
            val properties = it.properties
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                link.rumble = it
                link.rumbleWriteType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                link.rumble = it
                link.rumbleWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        }

        val characteristics = service.characteristics
        if (characteristics.size > MAX_SERVICE_CHARACTERISTICS) {
            return false
        }
        for (characteristic in characteristics) {
            val notifies = characteristic.properties and
                (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            if (!notifies || characteristic.getDescriptor(CCCD_UUID) == null) {
                continue
            }
            val reference = characteristic.getDescriptor(REPORT_REFERENCE_UUID)
            if (reference != null) {
                if (link.references.size == MAX_REFERENCE_READS) {
                    return false
                }
                link.references.addLast(reference)
            } else if (characteristic.uuid == STATE_INPUT_UUID && !addRoute(link, characteristic, STATE_REPORT_ID)) {
                return false
            }
        }
        return true
    }

    private fun addRoute(link: Link, characteristic: BluetoothGattCharacteristic, reportId: Int): Boolean {
        if (link.routes.any { it.reportId == reportId }) {
            return false
        }
        link.routes.add(Route(characteristic, reportId))
        return true
    }

    private fun readNextReference(link: Link) {
        val reference = link.references.pollFirst()
        if (reference != null) {
            if (!begin(link, Op(Step.REFERENCE, reference), OP_TIMEOUT_MS) { it.readDescriptor(reference) }) {
                fail(link, Failure.IO)
            }
            return
        }

        if (link.routes.isEmpty()) {
            LimeLog.warning("Steam Controller BLE: no input characteristic carries a known report")
            fail(link, Failure.PROTOCOL)
        } else if (!begin(link, Op(Step.MTU), OP_TIMEOUT_MS) { it.requestMtu(REQUESTED_MTU) }) {
            fail(link, Failure.IO)
        }
    }

    private fun onReferenceRead(link: Link, descriptor: BluetoothGattDescriptor, status: Int, value: ByteArray?) {
        val op = link.op
        if (op?.step != Step.REFERENCE || op.target !== descriptor) {
            return
        }
        endOp(link)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(link, Failure.IO)
            return
        }

        // A Report Reference is [report ID, report type]; only inputs this protocol knows are routed
        val characteristic = descriptor.characteristic
        val reportId = if (value != null && value.size == 2 && value[1].toInt() == REPORT_TYPE_INPUT) {
            value[0].toInt() and 0xFF
        } else {
            null
        }
        val routed = when {
            characteristic.uuid == STATE_INPUT_UUID ->
                reportId == STATE_REPORT_ID && addRoute(link, characteristic, STATE_REPORT_ID)
            reportId == STATE_REPORT_ID || reportId == TIMESTAMP_STATE_REPORT_ID ->
                addRoute(link, characteristic, reportId)
            else -> true
        }
        if (!routed) {
            LimeLog.warning("Steam Controller BLE: an input report reference contradicts the protocol")
            fail(link, Failure.PROTOCOL)
            return
        }
        readNextReference(link)
    }

    private fun onMtu(link: Link, mtu: Int, status: Int) {
        if (link.op?.step != Step.MTU) {
            return
        }
        endOp(link)
        when {
            status != BluetoothGatt.GATT_SUCCESS -> fail(link, Failure.IO)
            mtu < REQUIRED_MTU -> {
                LimeLog.warning("Steam Controller BLE: MTU $mtu cannot carry a whole report")
                fail(link, Failure.PROTOCOL)
            }
            else -> {
                link.subscriptions.addAll(link.routes)
                subscribeNext(link)
            }
        }
    }

    private fun subscribeNext(link: Link) {
        val route = link.subscriptions.pollFirst()
        if (route == null) {
            link.ready = true
            failures = 0
            LimeLog.info("Steam Controller BLE: ready")
            deliver { it.onReady() }
            pump(link)
            return
        }

        val characteristic = route.characteristic
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val gatt = link.gatt
        if (gatt == null || !gatt.setCharacteristicNotification(characteristic, true) ||
            !begin(link, Op(Step.SUBSCRIBE, cccd), OP_TIMEOUT_MS) { writeDescriptor(it, cccd, value) }) {
            fail(link, Failure.IO)
        }
    }

    private fun onDescriptorWritten(link: Link, descriptor: BluetoothGattDescriptor, status: Int) {
        val op = link.op
        if (op?.step != Step.SUBSCRIBE || op.target !== descriptor) {
            return
        }
        endOp(link)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(link, Failure.IO)
            return
        }
        subscribeNext(link)
    }

    private fun onReport(link: Link, characteristic: BluetoothGattCharacteristic, payload: ByteArray) {
        if (!link.ready || payload.isEmpty() || payload.size > MAX_INPUT_PAYLOAD) {
            return
        }
        // The report ID comes from the characteristic. The first payload byte is a sequence
        // number, which can look like any report ID.
        val route = link.routes.firstOrNull { it.characteristic === characteristic } ?: return
        val report = ByteArray(payload.size + 1)
        report[0] = route.reportId.toByte()
        payload.copyInto(report, 1)
        deliver { it.onReport(report) }
    }

    private fun queueFeature(write: FeatureWrite) {
        val current = link
        if (write.payload == null || closed || finished || current == null || !current.ready ||
            features.size >= MAX_PENDING_FEATURES) {
            write.complete(false)
            return
        }
        features.addLast(write)
        pump(current)
    }

    private fun takeRumble() {
        rumblePosted.set(false)
        val payload = latestRumble.getAndSet(null) ?: return
        val current = link ?: return
        if (!closed && !finished && current.ready && current.rumble != null) {
            current.pendingRumble = payload
            pump(current)
        }
    }

    /** Starts the next write once the link is idle; queued features go before the latest rumble. */
    private fun pump(link: Link) {
        while (this.link === link && link.ready && link.op == null) {
            val write = features.pollFirst()
            if (write != null) {
                // Acknowledged writes only: completion has to mean the controller took the report
                val characteristic = link.feature
                val payload = write.payload
                if (characteristic != null && payload != null &&
                    begin(link, Op(Step.FEATURE, characteristic, write), WRITE_TIMEOUT_MS) {
                        writeCharacteristic(it, characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    }) {
                    return
                }
                write.complete(false)
                continue
            }

            val payload = link.pendingRumble ?: return
            val characteristic = link.rumble ?: return
            link.pendingRumble = null
            if (begin(link, Op(Step.RUMBLE, characteristic), WRITE_TIMEOUT_MS) {
                    writeCharacteristic(it, characteristic, payload, link.rumbleWriteType)
                }) {
                return
            }
        }
    }

    private fun onWritten(link: Link, characteristic: BluetoothGattCharacteristic, status: Int) {
        val op = link.op
        if ((op?.step != Step.FEATURE && op?.step != Step.RUMBLE) || op.target !== characteristic) {
            return
        }
        endOp(link)
        op.write?.complete(status == BluetoothGatt.GATT_SUCCESS)
        pump(link)
    }

    /** Ends this link: input is released before any write completes, then a bounded reconnect. */
    private fun fail(link: Link, failure: Failure) {
        if (this.link !== link) {
            return
        }
        val inFlight = link.op?.write
        val wasReady = detach(link)
        if (wasReady) {
            deliver { it.onLinkLost() }
        }
        inFlight?.complete(false)
        cancelQueuedWrites()

        if (closed || finished) {
            return
        }
        failures += 1
        if (failure == Failure.PERMISSION || failure == Failure.PROTOCOL || failures >= MAX_LINK_ATTEMPTS) {
            finish(failure)
            return
        }
        val delay = minOf(RECONNECT_BASE_MS shl (failures - 1), RECONNECT_MAX_MS)
        handler?.postDelayed(reconnect, delay)
    }

    private fun detach(link: Link): Boolean {
        val wasReady = link.ready
        this.link = null
        link.ready = false
        link.op = null
        link.pendingRumble = null
        cancelTimer(link)
        link.gatt?.let { gatt ->
            link.gatt = null
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: SecurityException) {
                // Permission is already gone; the platform has released the client
            }
        }
        return wasReady
    }

    private fun cancelQueuedWrites() {
        while (true) {
            (features.pollFirst() ?: return).complete(false)
        }
    }

    /** Terminal failure: one onClosed, then the thread is released. */
    private fun finish(failure: Failure) {
        if (finished) {
            return
        }
        link?.let { lost ->
            val inFlight = lost.op?.write
            if (detach(lost)) {
                deliver { it.onLinkLost() }
            }
            inFlight?.complete(false)
        }
        cancelQueuedWrites()
        finished = true
        deliver { it.onClosed(failure) }
        release()
    }

    private fun shutdown() {
        link?.let {
            val inFlight = it.op?.write
            detach(it)
            inFlight?.complete(false)
        }
        cancelQueuedWrites()
        finished = true
        release()
    }

    private fun release() {
        observer = null
        latestRumble.set(null)
        handler?.removeCallbacks(reconnect)
        // Writes posted before this still run and complete false; later posts are refused
        thread?.quitSafely()
    }

    private inner class LinkCallback(private val link: Link) : BluetoothGattCallback() {
        private fun dispatch(gatt: BluetoothGatt, event: () -> Unit) {
            post {
                if (current(link, gatt)) {
                    event()
                }
            }
        }

        private fun notified(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
            // Copy now: before Android 13 the characteristic's value is overwritten by the next one
            val payload = value?.copyOf() ?: return
            if (queuedReports.incrementAndGet() > MAX_QUEUED_REPORTS) {
                queuedReports.decrementAndGet()
                return
            }
            val posted = post {
                queuedReports.decrementAndGet()
                if (current(link, gatt)) {
                    onReport(link, characteristic, payload)
                }
            }
            if (!posted) {
                queuedReports.decrementAndGet()
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) =
            dispatch(gatt) { onConnectionState(link, status, newState) }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
            dispatch(gatt) { onDiscovered(link, gatt, status) }

        // Handles found by discovery are stale once the service changes; before it, nothing is cached
        override fun onServiceChanged(gatt: BluetoothGatt) =
            dispatch(gatt) {
                val step = link.op?.step
                if (step != Step.CONNECT && step != Step.DISCOVER) {
                    fail(link, Failure.IO)
                }
            }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) =
            dispatch(gatt) { onMtu(link, mtu, status) }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray
        ) {
            val copy = value.copyOf()
            dispatch(gatt) { onReferenceRead(link, descriptor, status, copy) }
        }

        @Deprecated("Called before Android 13")
        @Suppress("DEPRECATION")
        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val copy = descriptor.value?.copyOf()
                dispatch(gatt) { onReferenceRead(link, descriptor, status, copy) }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            dispatch(gatt) { onDescriptorWritten(link, descriptor, status) }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            dispatch(gatt) { onWritten(link, characteristic, status) }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) = notified(gatt, characteristic, value)

        @Deprecated("Called before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                notified(gatt, characteristic, characteristic.value)
            }
        }
    }

    companion object {
        private const val THREAD_NAME = "SteamControllerBle"

        private val SERVICE_UUID = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3")
        private val FEATURE_UUID = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3")
        // Report N uses characteristic 100F6C(N + 0x35): input 0x45 is 7A and rumble output 0x80 is B5
        private val STATE_INPUT_UUID = UUID.fromString("100F6C7A-1735-4313-B402-38567131E5F3")
        private val RUMBLE_UUID = UUID.fromString("100F6CB5-1735-4313-B402-38567131E5F3")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val REPORT_REFERENCE_UUID = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb")

        private const val STATE_REPORT_ID = 0x45
        private const val TIMESTAMP_STATE_REPORT_ID = 0x47
        private const val REPORT_TYPE_INPUT = 1
        private const val FEATURE_REPORT_ID = 1
        private const val FEATURE_REPORT_SIZE = 64
        private const val RUMBLE_REPORT_ID = 0x80
        private const val RUMBLE_REPORT_SIZE = 10
        // A report is at most the 64-byte HID report, and BLE carries it without the ID
        private const val MAX_INPUT_PAYLOAD = FEATURE_REPORT_SIZE - 1

        // Ask for the largest MTU, but the link only has to fit a whole feature report in one write
        private const val REQUESTED_MTU = 517
        private const val REQUIRED_MTU = 3 + FEATURE_REPORT_SIZE - 1

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val OP_TIMEOUT_MS = 5_000L
        private const val WRITE_TIMEOUT_MS = 1_000L
        private const val MAX_LINK_ATTEMPTS = 5
        private const val RECONNECT_BASE_MS = 500L
        private const val RECONNECT_MAX_MS = 4_000L
        private const val GATT_ERROR = 133

        private const val MAX_SERVICE_CHARACTERISTICS = 64
        private const val MAX_REFERENCE_READS = 8
        private const val MAX_PENDING_FEATURES = 8
        private const val MAX_QUEUED_REPORTS = 32

        @Suppress("DEPRECATION")
        private fun writeCharacteristic(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int
        ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }

        @Suppress("DEPRECATION")
        private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
    }
}
