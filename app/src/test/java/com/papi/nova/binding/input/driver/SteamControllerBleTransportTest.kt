package com.papi.nova.binding.input.driver

import android.Manifest
import android.app.Application
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
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.binding.input.driver.SteamControllerTransport.Failure
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBluetoothDevice

@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
class SteamControllerBleTransportTest {
    private class Write(val uuid: UUID, val value: ByteArray, val writeType: Int)

    /** The Valve service as discovery returns it; a reconnect discovers new objects. */
    private class Layout(
        inputReference: ByteArray? = null,
        timestampReference: ByteArray? = null,
        featureProperties: Int = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        val hasService: Boolean = true
    ) {
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val input = notifying(INPUT, inputReference)
        // The UUID a timestamp input would follow by pattern; it is routed only by its reference
        val timestamp = notifying(TIMESTAMP_BY_PATTERN, timestampReference)
        val feature = BluetoothGattCharacteristic(FEATURE, featureProperties, BluetoothGattCharacteristic.PERMISSION_WRITE)
        val rumble = BluetoothGattCharacteristic(
            RUMBLE,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        init {
            for (characteristic in listOf(input, timestamp, feature, rumble)) {
                service.addCharacteristic(characteristic)
            }
        }

        private fun notifying(uuid: UUID, reference: ByteArray?) =
            BluetoothGattCharacteristic(uuid, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).apply {
                addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_WRITE))
                if (reference != null) {
                    addDescriptor(BluetoothGattDescriptor(REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ))
                }
            }
    }

    /** One GATT client the transport opened, driven as the Bluetooth stack would. */
    private inner class Link(val layout: Layout, val callback: BluetoothGattCallback) {
        val gatt: BluetoothGatt = mock(BluetoothGatt::class.java)
        val writes = CopyOnWriteArrayList<Write>()
        @Volatile var writeFailure: RuntimeException? = null

        init {
            `when`(gatt.getService(SERVICE)).thenReturn(if (layout.hasService) layout.service else null)
            `when`(gatt.discoverServices()).thenReturn(true)
            `when`(gatt.requestMtu(anyInt())).thenReturn(true)
            `when`(gatt.readDescriptor(any(BluetoothGattDescriptor::class.java))).thenReturn(true)
            `when`(gatt.setCharacteristicNotification(any(BluetoothGattCharacteristic::class.java), anyBoolean()))
                .thenReturn(true)
            @Suppress("DEPRECATION")
            `when`(gatt.writeDescriptor(any(BluetoothGattDescriptor::class.java))).thenReturn(true)
            // The value-carrying overloads only exist from Android 13
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                `when`(gatt.writeDescriptor(any(BluetoothGattDescriptor::class.java), any(ByteArray::class.java)))
                    .thenReturn(BluetoothStatusCodes.SUCCESS)
                doAnswer { invocation ->
                    writeFailure?.let { throw it }
                    val characteristic = invocation.getArgument<BluetoothGattCharacteristic>(0)
                    writes += Write(characteristic.uuid, invocation.getArgument<ByteArray>(1).copyOf(), invocation.getArgument(2))
                    BluetoothStatusCodes.SUCCESS
                }.`when`(gatt).writeCharacteristic(
                    any(BluetoothGattCharacteristic::class.java),
                    any(ByteArray::class.java),
                    anyInt()
                )
            }
            @Suppress("DEPRECATION")
            doAnswer { invocation ->
                writeFailure?.let { throw it }
                val characteristic = invocation.getArgument<BluetoothGattCharacteristic>(0)
                writes += Write(characteristic.uuid, characteristic.value.copyOf(), characteristic.writeType)
                true
            }.`when`(gatt).writeCharacteristic(any(BluetoothGattCharacteristic::class.java))
        }

        fun connected(status: Int = BluetoothGatt.GATT_SUCCESS) =
            event { callback.onConnectionStateChange(gatt, status, BluetoothProfile.STATE_CONNECTED) }

        fun disconnected(status: Int) =
            event { callback.onConnectionStateChange(gatt, status, BluetoothProfile.STATE_DISCONNECTED) }

        fun discovered(status: Int = BluetoothGatt.GATT_SUCCESS) = event { callback.onServicesDiscovered(gatt, status) }

        fun mtu(mtu: Int = 517, status: Int = BluetoothGatt.GATT_SUCCESS) = event { callback.onMtuChanged(gatt, mtu, status) }

        fun referenceRead(characteristic: BluetoothGattCharacteristic, value: ByteArray) = event {
            callback.onDescriptorRead(gatt, characteristic.getDescriptor(REPORT_REFERENCE), BluetoothGatt.GATT_SUCCESS, value)
        }

        fun subscribed(characteristic: BluetoothGattCharacteristic, status: Int = BluetoothGatt.GATT_SUCCESS) =
            event { callback.onDescriptorWrite(gatt, characteristic.getDescriptor(CCCD), status) }

        fun notified(characteristic: BluetoothGattCharacteristic, payload: ByteArray) =
            event { callback.onCharacteristicChanged(gatt, characteristic, payload) }

        @Suppress("DEPRECATION")
        fun notifiedBeforeAndroid13(characteristic: BluetoothGattCharacteristic, payload: ByteArray) = event {
            characteristic.value = payload
            callback.onCharacteristicChanged(gatt, characteristic)
        }

        fun written(characteristic: BluetoothGattCharacteristic, status: Int = BluetoothGatt.GATT_SUCCESS) =
            event { callback.onCharacteristicWrite(gatt, characteristic, status) }

        fun ready() {
            connected()
            discovered()
            mtu()
            subscribed(layout.input)
        }

        private fun event(block: () -> Unit) {
            block()
            idle()
        }
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var device: BluetoothDevice
    private var layouts: () -> Layout = { Layout() }
    private val links = CopyOnWriteArrayList<Link>()
    private val openContexts = CopyOnWriteArrayList<Context>()
    private val events = CopyOnWriteArrayList<String>()
    private val reports = CopyOnWriteArrayList<ByteArray>()
    private val callbackThreads = CopyOnWriteArrayList<String>()
    private var transport: SteamControllerBleTransport? = null
    private var looper: Looper? = null

    private val opener = SteamControllerBleTransport.GattOpener { context, _, callback ->
        openContexts += context
        Link(layouts(), callback).also { links += it }.gatt
    }

    private open inner class Recorder : SteamControllerTransport.Observer {
        override fun onReady() = record("ready")

        override fun onReport(report: ByteArray) {
            callbackThreads += Thread.currentThread().name
            reports += report
        }

        override fun onLinkLost() = record("lost")

        override fun onClosed(failure: Failure) = record("closed:$failure")

        protected fun record(event: String) {
            callbackThreads += Thread.currentThread().name
            events += event
        }
    }

    @Before
    @Suppress("DEPRECATION") // ShadowBluetoothDevice.newInstance is the shadow's own factory
    fun setUp() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT)
        device = ShadowBluetoothDevice.newInstance("00:11:22:33:44:55")
        shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
    }

    @After
    fun tearDown() {
        transport?.close()
        idle()
    }

    private fun start(observer: SteamControllerTransport.Observer = Recorder()): SteamControllerBleTransport {
        val created = SteamControllerBleTransport(app, device, opener)
        transport = created
        assertTrue(created.start(observer))
        // The thread can finish before this looks for it, but its handler keeps the looper
        looper = SteamControllerBleTransport::class.java.getDeclaredField("handler").run {
            isAccessible = true
            (get(created) as Handler).looper
        }
        assertEquals(THREAD_NAME, looper!!.thread.name)
        idle()
        return created
    }

    /** Runs the transport thread until idle, first advancing the clock by [ms] for its timers. */
    private fun idle(ms: Long = 0) {
        val target = looper ?: return
        try {
            if (quitting(target)) {
                return
            }
            val shadow = shadowOf(target)
            if (ms == 0L) shadow.idle() else shadow.idleFor(Duration.ofMillis(ms))
        } catch (e: RuntimeException) {
            // The transport released its thread while the test was handing it work
            if (!quitting(target)) throw e
        }
    }

    /** A quitting looper refuses new messages; an accepted probe is a no-op. */
    private fun quitting(target: Looper) = !Handler(target).post {}

    private fun released(): Boolean {
        val target = looper!!
        if (!quitting(target)) {
            return false
        }
        target.thread.join(5_000)
        return !target.thread.isAlive
    }

    private fun feature(tag: Int) = ByteArray(64).also {
        it[0] = 1
        it[1] = tag.toByte()
        it[63] = 0x7F
    }

    private fun rumble(tag: Int) = ByteArray(10).also {
        it[0] = 0x80.toByte()
        it[4] = tag.toByte()
    }

    @Test
    fun readinessFollowsServiceMtuAndNotificationHandshake() {
        val started = start()
        assertFalse("start accepts startup only once", started.start(Recorder()))
        val link = links.single()
        assertSame("only the application context is used", app, openContexts.single())

        link.connected()
        link.discovered()
        verify(link.gatt).requestMtu(517)
        link.mtu()
        verify(link.gatt).writeDescriptor(
            link.layout.input.getDescriptor(CCCD),
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        )
        link.notified(link.layout.input, byteArrayOf(1, 2, 3))
        assertTrue("nothing is ready or reported before notifications are on", events.isEmpty() && reports.isEmpty())

        link.subscribed(link.layout.input)
        assertEquals(listOf("ready"), events)
        assertEquals(listOf(THREAD_NAME), callbackThreads)

        val order = inOrder(link.gatt)
        order.verify(link.gatt).discoverServices()
        order.verify(link.gatt).requestMtu(517)
        order.verify(link.gatt).setCharacteristicNotification(link.layout.input, true)
        order.verify(link.gatt).writeDescriptor(
            link.layout.input.getDescriptor(CCCD),
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        )
        verify(link.gatt, never()).readDescriptor(any(BluetoothGattDescriptor::class.java))
        verify(link.gatt, never()).setCharacteristicNotification(link.layout.timestamp, true)
        assertTrue("validation writes nothing to the controller", link.writes.isEmpty())

        link.subscribed(link.layout.input)
        assertEquals("a repeated confirmation does not make the link ready twice", listOf("ready"), events)
    }

    @Test
    fun reportIdComesFromTheCharacteristicNotThePayload() {
        start()
        val link = links.single()
        link.ready()

        // The first payload byte is a sequence number; here it looks like the timestamp report ID
        val payload = byteArrayOf(0x47, 0x42, 3)
        link.notified(link.layout.input, payload)
        payload[1] = 9
        link.notified(link.layout.input, byteArrayOf(0x45))

        assertEquals(2, reports.size)
        assertArrayEquals(byteArrayOf(0x45, 0x47, 0x42, 3), reports[0])
        assertArrayEquals(byteArrayOf(0x45, 0x45), reports[1])
        assertNotSame(reports[0], reports[1])

        link.notified(link.layout.timestamp, byteArrayOf(1, 2))
        link.notified(link.layout.feature, byteArrayOf(1, 2))
        link.notified(link.layout.input, ByteArray(0))
        link.notified(link.layout.input, ByteArray(64))
        assertEquals("unrouted, empty and oversized notifications are dropped", 2, reports.size)
        link.notified(link.layout.input, ByteArray(63))
        assertEquals(64, reports.last().size)
    }

    @Test
    fun timestampInputIsRoutedOnlyWhenItsReportReferenceProvesIt() {
        layouts = { Layout(timestampReference = byteArrayOf(0x47, 1)) }
        start()
        val link = links.single()
        link.connected()
        link.discovered()
        verify(link.gatt).readDescriptor(link.layout.timestamp.getDescriptor(REPORT_REFERENCE))
        verify(link.gatt, never()).requestMtu(anyInt())

        link.referenceRead(link.layout.timestamp, byteArrayOf(0x47, 1))
        link.mtu()
        link.subscribed(link.layout.input)
        assertTrue("ready waits for every routed input", events.isEmpty())
        verify(link.gatt).writeDescriptor(
            link.layout.timestamp.getDescriptor(CCCD),
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        )
        link.subscribed(link.layout.timestamp)
        assertEquals(listOf("ready"), events)

        link.notified(link.layout.timestamp, byteArrayOf(0x45, 7))
        link.notified(link.layout.input, byteArrayOf(0x47, 8))
        assertArrayEquals(byteArrayOf(0x47, 0x45, 7), reports[0])
        assertArrayEquals(byteArrayOf(0x45, 0x47, 8), reports[1])
    }

    @Test
    fun contradictoryInputReferenceIsAProtocolFailure() {
        layouts = { Layout(inputReference = byteArrayOf(0x47, 1)) }
        start()
        val link = links.single()
        link.connected()
        link.discovered()
        link.referenceRead(link.layout.input, byteArrayOf(0x47, 1))

        assertEquals(listOf("closed:PROTOCOL"), events)
        verify(link.gatt, never()).requestMtu(anyInt())
        verify(link.gatt).close()
        idle(60_000)
        assertEquals("a protocol failure is not retried", 1, links.size)
        assertTrue(released())
    }

    @Test
    fun missingValveServiceFailsBeforeAnyWrite() {
        layouts = { Layout(hasService = false) }
        start()
        val link = links.single()
        link.connected()
        link.discovered()

        assertEquals(listOf("closed:PROTOCOL"), events)
        verify(link.gatt, never()).requestMtu(anyInt())
        verify(link.gatt, never()).setCharacteristicNotification(any(BluetoothGattCharacteristic::class.java), anyBoolean())
        assertTrue(link.writes.isEmpty())
    }

    @Test
    fun featureCharacteristicWithoutAcknowledgedWritesIsRejected() {
        layouts = { Layout(featureProperties = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) }
        start()
        val link = links.single()
        link.connected()
        link.discovered()

        assertEquals(listOf("closed:PROTOCOL"), events)
        verify(link.gatt, never()).requestMtu(anyInt())
    }

    @Test
    fun mtuMustCarryAWholeFeatureReport() {
        start()
        val link = links.single()
        link.connected()
        link.discovered()
        link.mtu(65)

        assertEquals(listOf("closed:PROTOCOL"), events)
        verify(link.gatt, never()).setCharacteristicNotification(any(BluetoothGattCharacteristic::class.java), anyBoolean())
    }

    @Test
    fun featureWritesAreSerializedAndCompleteOnlyOnConfirmation() {
        val transport = start()
        val link = links.single()
        link.ready()
        val results = CopyOnWriteArrayList<String>()

        transport.writeFeature(feature(0x11)) { results += "a:$it" }
        transport.writeFeature(feature(0x22)) { results += "b:$it" }
        transport.writeFeature(ByteArray(63).also { it[0] = 1 }) { results += "short:$it" }
        transport.writeFeature(ByteArray(64).also { it[0] = 2 }) { results += "id:$it" }
        idle()

        assertEquals(listOf("short:false", "id:false"), results)
        val first = link.writes.single()
        assertEquals(FEATURE, first.uuid)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, first.writeType)
        assertArrayEquals("the report goes without its ID byte", feature(0x11).copyOfRange(1, 64), first.value)

        link.written(link.layout.feature)
        assertEquals(listOf("short:false", "id:false", "a:true"), results)
        assertEquals(2, link.writes.size)
        assertEquals(0x22.toByte(), link.writes[1].value[0])

        link.written(link.layout.feature, 3)
        link.written(link.layout.feature)
        assertEquals("each accepted write completes once", listOf("short:false", "id:false", "a:true", "b:false"), results)
        assertEquals(listOf("ready"), events)
    }

    @Test
    fun rumbleKeepsOnlyTheLatestStateBehindOtherWrites() {
        val transport = start()
        val link = links.single()
        link.ready()

        transport.writeFeature(feature(1)) {}
        transport.writeRumble(rumble(1))
        transport.writeRumble(rumble(2))
        transport.writeRumble(ByteArray(9).also { it[0] = 0x80.toByte() })
        transport.writeRumble(ByteArray(10).also { it[0] = 0x81.toByte() })
        transport.writeRumble(rumble(3))
        idle()
        assertEquals("rumble waits for the outstanding write", 1, link.writes.size)

        link.written(link.layout.feature)
        assertEquals(2, link.writes.size)
        val write = link.writes[1]
        assertEquals(RUMBLE, write.uuid)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, write.writeType)
        assertArrayEquals(rumble(3).copyOfRange(1, 10), write.value)

        repeat(100) { transport.writeRumble(rumble(it)) }
        idle()
        assertEquals(2, link.writes.size)
        link.written(link.layout.rumble)
        link.written(link.layout.rumble)
        assertEquals("a refresh burst becomes one write of the latest state", 3, link.writes.size)
        assertArrayEquals(rumble(99).copyOfRange(1, 10), link.writes[2].value)
    }

    @Test
    fun writeTimeoutLosesTheLinkThenReconnectsWithFreshReadiness() {
        val transport = start()
        val first = links.single()
        first.ready()
        val results = CopyOnWriteArrayList<Boolean>()
        transport.writeFeature(feature(1)) { results += it }
        idle()

        idle(999)
        assertTrue(results.isEmpty())
        idle(1)
        assertEquals(listOf(false), results)
        assertEquals(listOf("ready", "lost"), events)
        verify(first.gatt).close()

        idle(499)
        assertEquals("reconnect waits for its backoff", 1, links.size)
        idle(1)
        val second = links[1]
        second.ready()
        assertEquals(listOf("ready", "lost", "ready"), events)

        // Callbacks from the abandoned client are suppressed
        first.notified(first.layout.input, byteArrayOf(1))
        first.connected()
        first.subscribed(first.layout.input)
        first.written(first.layout.feature)
        assertTrue(reports.isEmpty())
        assertEquals(listOf("ready", "lost", "ready"), events)

        second.notified(second.layout.input, byteArrayOf(2))
        assertArrayEquals(byteArrayOf(0x45, 2), reports.single())
    }

    @Test
    fun gattErrorReleasesInputBeforeCancellingWrites() {
        val transport = start()
        val first = links.single()
        first.ready()
        transport.writeFeature(feature(1)) { events += "a:$it" }
        transport.writeFeature(feature(2)) { events += "b:$it" }
        idle()

        first.disconnected(133)
        assertEquals(listOf("ready", "lost", "a:false", "b:false"), events)
        verify(first.gatt).close()
        transport.writeFeature(feature(3)) { events += "c:$it" }
        transport.writeRumble(rumble(3))
        idle()
        assertEquals("writes between links are refused", "c:false", events.last())

        idle(500)
        val second = links[1]
        second.ready()
        assertEquals("ready", events.last())
        assertTrue("nothing queued on the lost link reaches the new one", second.writes.isEmpty())
    }

    @Test
    fun reconnectsAreBoundedAndCloseExactlyOnce() {
        val transport = start()
        var backoff = 500L
        repeat(4) {
            idle(15_000)
            idle(backoff)
            backoff *= 2
        }
        assertEquals(5, links.size)
        idle(15_000)

        assertEquals(listOf("closed:UNAVAILABLE"), events)
        links.forEach { verify(it.gatt).close() }
        assertTrue("the thread is released after the terminal outcome", released())
        idle(60_000)
        assertEquals(5, links.size)

        val results = mutableListOf<Boolean>()
        transport.writeFeature(feature(1)) { results += it }
        assertEquals("a write after the end completes at once", listOf(false), results)
        transport.close()
        assertEquals(listOf("closed:UNAVAILABLE"), events)
    }

    @Config(sdk = [31])
    @Test
    fun deniedConnectPermissionIsTypedWithoutTouchingBluetooth() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        start()

        assertEquals(listOf("closed:PERMISSION"), events)
        assertTrue(links.isEmpty())
        assertTrue(released())
    }

    @Config(sdk = [30])
    @Test
    fun shieldApiLevelUsesLegacyPermissionAndCallbacks() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val transport = start()
        val link = links.single()
        link.ready()
        assertEquals(listOf("ready"), events)

        link.notifiedBeforeAndroid13(link.layout.input, byteArrayOf(5, 6))
        assertArrayEquals(byteArrayOf(0x45, 5, 6), reports.single())

        val results = CopyOnWriteArrayList<Boolean>()
        transport.writeFeature(feature(9)) { results += it }
        idle()
        assertArrayEquals(feature(9).copyOfRange(1, 64), link.writes.single().value)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, link.writes.single().writeType)
        link.written(link.layout.feature)
        assertEquals(listOf(true), results)
    }

    @Config(sdk = [30])
    @Test
    fun shieldApiLevelStillNeedsTheBluetoothPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH)
        start()

        assertEquals(listOf("closed:PERMISSION"), events)
        assertTrue(links.isEmpty())
    }

    @Test
    fun unbondedDeviceIsUnavailableAndNeverPaired() {
        shadowOf(device).setBondState(BluetoothDevice.BOND_NONE)
        start()

        assertEquals(listOf("closed:UNAVAILABLE"), events)
        assertTrue(links.isEmpty())
        assertEquals(BluetoothDevice.BOND_NONE, device.bondState)
    }

    @Test
    fun revokedPermissionDuringALinkEndsWithoutReconnecting() {
        val transport = start()
        val link = links.single()
        link.ready()
        link.writeFailure = SecurityException("revoked")
        val results = CopyOnWriteArrayList<Boolean>()

        transport.writeFeature(feature(1)) { results += it }
        idle()

        assertEquals(listOf(false), results)
        assertEquals(listOf("ready", "lost", "closed:PERMISSION"), events)
        idle(60_000)
        assertEquals(1, links.size)
    }

    @Test
    fun closeIsIdempotentAndCancelsPendingWrites() {
        val transport = start()
        val link = links.single()
        link.ready()
        val results = CopyOnWriteArrayList<String>()
        transport.writeFeature(feature(1)) { results += "a:$it" }
        transport.writeFeature(feature(2)) { results += "b:$it" }
        idle()

        transport.close()
        transport.close()
        idle()

        assertEquals(listOf("a:false", "b:false"), results)
        assertEquals("explicit close reports no loss or terminal outcome", listOf("ready"), events)
        verify(link.gatt).close()
        assertTrue(released())

        link.notified(link.layout.input, byteArrayOf(1))
        link.written(link.layout.feature)
        transport.writeFeature(feature(3)) { results += "c:$it" }
        transport.writeRumble(rumble(1))
        assertEquals(listOf("a:false", "b:false", "c:false"), results)
        assertTrue(reports.isEmpty())
        assertEquals(1, link.writes.size)
        assertFalse(transport.start(Recorder()))
    }

    @Test
    fun closeFromAnObserverCallbackDoesNotBlockItsThread() {
        lateinit var transport: SteamControllerBleTransport
        transport = start(object : Recorder() {
            override fun onReady() {
                record("ready")
                transport.close()
            }
        })
        val link = links.single()
        link.ready()
        link.notified(link.layout.input, byteArrayOf(1))

        assertEquals(listOf("ready"), events)
        assertTrue(reports.isEmpty())
        assertTrue(released())
        verify(link.gatt).close()
    }

    @Test
    fun closeBeforeStartOwnsNothing() {
        val transport = SteamControllerBleTransport(app, device, opener)
        transport.close()
        val results = mutableListOf<Boolean>()
        transport.writeFeature(feature(1)) { results += it }

        assertFalse(transport.start(Recorder()))
        assertEquals(listOf(false), results)
        assertTrue(links.isEmpty())
    }

    private companion object {
        const val THREAD_NAME = "SteamControllerBle"
        val SERVICE: UUID = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3")
        val FEATURE: UUID = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3")
        val INPUT: UUID = UUID.fromString("100F6C7A-1735-4313-B402-38567131E5F3")
        val TIMESTAMP_BY_PATTERN: UUID = UUID.fromString("100F6C7C-1735-4313-B402-38567131E5F3")
        val RUMBLE: UUID = UUID.fromString("100F6CB5-1735-4313-B402-38567131E5F3")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val REPORT_REFERENCE: UUID = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb")
    }
}
