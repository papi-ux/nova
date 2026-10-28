package com.papi.nova.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.Build
import com.papi.nova.binding.input.driver.SteamControllerTransport.Failure
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mocked USB objects only: these control the transport's logic, not real controller hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamControllerUsbTransportTest {
    private val transports = mutableListOf<SteamControllerUsbTransport>()

    @After
    fun closeTransports() {
        transports.forEach { it.close() }
    }

    @Test
    fun claimsOnlyValveWiredControllerAndPuck() {
        assertTrue(SteamControllerUsbTransport.canClaimDevice(usbDevice(WIRED, emptyList())))
        assertTrue(SteamControllerUsbTransport.canClaimDevice(usbDevice(PUCK, emptyList())))
        assertFalse(SteamControllerUsbTransport.canClaimDevice(usbDevice(STEAM_DECK, emptyList())))
        assertFalse(SteamControllerUsbTransport.canClaimDevice(usbDevice(WIRED, emptyList(), vendorId = 0x045e)))
        assertEquals(emptyList<Int>(), SteamControllerUsbTransport.candidateInterfaceIds(usbDevice(STEAM_DECK, listOf(usbInterface(2)))))
    }

    @Test
    fun puckOffersEachEligibleHidChannelSeparately() {
        val device = usbDevice(
            PUCK,
            listOf(
                usbInterface(5),
                usbInterface(0, subclass = BOOT),
                usbInterface(1),
                usbInterface(2, endpoints = listOf(endpoint(IN), endpoint(OUT))),
                usbInterface(3, endpoints = listOf(endpoint(IN, UsbConstants.USB_ENDPOINT_XFER_BULK))),
                usbInterface(4),
                usbInterface(4),
                usbInterface(6)
            )
        )

        assertEquals(listOf(2, 4, 5), SteamControllerUsbTransport.candidateInterfaceIds(device))
    }

    @Test
    fun wiredControllerOffersOnlyItsLowestGamepadInterface() {
        val hidAndVendor = usbDevice(
            WIRED,
            listOf(
                usbInterface(0, subclass = BOOT),
                usbInterface(1, subclass = BOOT),
                usbInterface(3, interfaceClass = UsbConstants.USB_CLASS_VENDOR_SPEC),
                usbInterface(2)
            )
        )
        val vendorOnly = usbDevice(
            WIRED,
            listOf(usbInterface(0, subclass = BOOT), usbInterface(3, interfaceClass = UsbConstants.USB_CLASS_VENDOR_SPEC))
        )

        assertEquals(listOf(2), SteamControllerUsbTransport.candidateInterfaceIds(hidAndVendor))
        assertEquals(listOf(3), SteamControllerUsbTransport.candidateInterfaceIds(vendorOnly))
    }

    @Test
    fun ineligibleChannelFailsStartWithoutClaimOrCallbacks() {
        val puck = puck()
        val lizard = Channel(puck, puck.getInterface(1))
        val wired = usbDevice(WIRED, listOf(usbInterface(2), usbInterface(3)))
        val secondWired = Channel(wired, wired.getInterface(1))
        val recorder = Recorder()

        for (channel in listOf(lizard, secondWired)) {
            val transport = channel.transport()
            assertFalse(transport.start(recorder))
            assertEquals(Failure.PROTOCOL, transport.startFailure)
            verify(channel.connection, never()).claimInterface(any(), anyBoolean())
            verify(channel.connection, times(1)).close()
            assertFalse(transport.start(recorder))
        }
        assertNull(recorder.quiet())
    }

    @Test
    fun claimFailureAndSecurityExceptionAreTypedStartFailures() {
        val device = puck()
        val busy = Channel(device, device.getInterface(2))
        `when`(busy.connection.claimInterface(any(), anyBoolean())).thenReturn(false)
        val revoked = Channel(device, device.getInterface(3))
        `when`(revoked.connection.claimInterface(any(), anyBoolean())).thenThrow(SecurityException("revoked"))
        val recorder = Recorder()

        val busyTransport = busy.transport()
        assertFalse(busyTransport.start(recorder))
        assertEquals(Failure.IO, busyTransport.startFailure)

        val revokedTransport = revoked.transport()
        assertFalse(revokedTransport.start(recorder))
        assertEquals(Failure.PERMISSION, revokedTransport.startFailure)

        for (channel in listOf(busy, revoked)) {
            verify(channel.connection, never()).releaseInterface(any())
            verify(channel.connection, times(1)).close()
        }
        assertNull(recorder.quiet())
    }

    @Test
    fun closeBeforeStartReleasesOnlyTheConnection() {
        val device = puck()
        val channel = Channel(device, device.getInterface(2))
        val transport = channel.transport()

        transport.close()
        transport.close()

        assertFalse(transport.start(Recorder()))
        assertNull(transport.startFailure)
        verify(channel.connection, never()).claimInterface(any(), anyBoolean())
        verify(channel.connection, never()).releaseInterface(any())
        verify(channel.connection, times(1)).close()
    }

    @Test
    fun closeDuringClaimReleasesTheClaimWithoutCallbacks() {
        val device = puck()
        val channel = Channel(device, device.getInterface(2))
        val transport = channel.transport()
        doAnswer {
            transport.close()
            true
        }.`when`(channel.connection).claimInterface(any(), anyBoolean())
        val recorder = Recorder()

        assertFalse(transport.start(recorder))

        assertNull(transport.startFailure)
        verify(channel.connection, timeout(TIMEOUT_MS)).close()
        verify(channel.connection).releaseInterface(channel.iface)
        assertNull(recorder.quiet())
    }

    @Test
    fun puckChannelClaimsOnlyItsInterfaceAndDeliversOwnedReportsAfterReady() {
        val device = puck()
        val channel = Channel(device, device.getInterface(3))
        channel.data(0x42, 1, 2, 3)
        channel.data(0x79, 0x02)
        val recorder = Recorder()

        assertTrue(channel.transport().start(recorder))

        assertEquals("ready", recorder.next())
        assertEquals("report:42010203", recorder.next())
        assertEquals("report:7902", recorder.next())
        verify(channel.connection).claimInterface(channel.iface, true)
        verify(channel.connection, times(1)).claimInterface(any(), anyBoolean())
        // The second read reused the transfer buffer; the first delivered report kept its bytes
        assertNotSame(recorder.reports[0], recorder.reports[1])
        assertArrayEquals(byteArrayOf(0x42, 1, 2, 3), recorder.reports[0])
        assertTrue(channel.buffers.all { it.isDirect && !it.hasArray() })
        verify(channel.connection, never()).bulkTransfer(same(channel.iface.getEndpoint(0)), any(), anyInt(), anyInt())
    }

    @Test
    fun idleWaitsAndATransientZeroLengthCompletionKeepTheChannelOpen() {
        val device = puck()
        val channel = Channel(device, device.getInterface(2))
        repeat(3) { channel.reads.add(channel.idle) }
        channel.reads.add(channel.error)
        channel.data(0x42, 7)
        val recorder = Recorder()

        assertTrue(channel.transport().start(recorder))

        assertEquals("ready", recorder.next())
        assertEquals("report:4207", recorder.next())
        assertNull(recorder.quiet())
        verify(channel.connection, never()).close()
    }

    @Test
    fun idleWaitKeepsOneDirectRequestPendingUntilCompletion() {
        val device = puck()
        val channel = Channel(device, device.getInterface(2))
        repeat(3) { channel.reads.add(channel.idle) }
        val waiting = CountDownLatch(1)
        val complete = CountDownLatch(1)
        channel.reads.add {
            waiting.countDown()
            assertTrue(complete.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            0
        }
        val recorder = Recorder()
        val transport = channel.transport()
        assertTrue(transport.start(recorder))
        assertEquals("ready", recorder.next())
        assertTrue(waiting.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertEquals(1, channel.buffers.size)
        assertTrue(channel.buffers.single().isDirect)
        assertFalse(channel.buffers.single().hasArray())
        verify(channel.connection, never()).bulkTransfer(same(channel.iface.getEndpoint(0)), any(), anyInt(), anyInt())
        @Suppress("DEPRECATION")
        verify(channel.request, never()).queue(any(ByteBuffer::class.java), anyInt())
        transport.close()
        verify(channel.request, times(1)).cancel()
        verify(channel.request, never()).close()
        complete.countDown()
        verify(channel.request, timeout(TIMEOUT_MS)).close()
        verify(channel.connection, timeout(TIMEOUT_MS)).close()
        verify(channel.connection, never()).requestWait(anyLong())
        assertNull(recorder.quiet())
    }

    @Test
    @Config(sdk = [23]) // Robolectric 4.16 dropped 21/22; this exercises the same pre-26 API path.
    fun legacyDirectQueueAndCancellationWakeTheUntimedWait() {
        val device = puck()
        val channel = Channel(device, device.getInterface(2))
        val waiting = CountDownLatch(1)
        doAnswer { waiting.countDown(); channel.awaitInput(false) }.`when`(channel.connection).requestWait()
        val recorder = Recorder()
        val transport = channel.transport()
        assertTrue(transport.start(recorder))
        assertEquals("ready", recorder.next())
        assertTrue(waiting.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertEquals(1, channel.buffers.size)
        assertTrue(channel.buffers.single().isDirect)
        assertFalse(channel.buffers.single().hasArray())
        transport.close()
        verify(channel.request, timeout(TIMEOUT_MS)).close()
        verify(channel.request, times(1)).cancel()
        verify(channel.connection, timeout(TIMEOUT_MS)).close()
        verify(channel.connection, times(1)).requestWait()
        assertNull(recorder.quiet())
    }

    @Test
    fun fatalWaitClosesTheFdBeforeFreeingAnUnreapedRequest() {
        val device = puck()
        val channel = Channel(device, device.getInterface(3))
        doReturn(null).`when`(channel.connection).requestWait()
        doAnswer { invocation ->
            assertEquals(0L, invocation.getArgument<Long>(0))
            throw TimeoutException("no completed request")
        }.`when`(channel.connection).requestWait(anyLong())
        val recorder = Recorder()
        val transport = channel.transport()
        assertTrue(transport.start(recorder))
        assertEquals("ready", recorder.next())
        assertEquals("lost", recorder.next())
        assertEquals("closed:IO", recorder.next())
        verify(channel.request, timeout(TIMEOUT_MS)).close()
        verify(channel.connection, timeout(TIMEOUT_MS)).close()
        assertEquals(1, channel.buffers.size)
        verify(channel.connection, times(1)).requestWait()
        verify(channel.connection, times(1)).requestWait(0)
        val order = inOrder(channel.request, channel.connection)
        order.verify(channel.request).cancel()
        order.verify(channel.connection).requestWait(0)
        order.verify(channel.connection).releaseInterface(channel.iface)
        order.verify(channel.connection).close()
        order.verify(channel.request).close()
        verify(channel.request, times(1)).close()
        transport.close()
        verify(channel.request, times(1)).cancel()
        assertNull(recorder.quiet())
    }

    @Test
    fun anotherRequestsCompletionCannotDeliverOrRequeueOurInput() {
        val device = puck()
        val channel = Channel(device, device.getInterface(4))
        val other = mock(UsbRequest::class.java)
        doReturn(other).`when`(channel.connection).requestWait()
        channel.data(0x42, 8)
        val recorder = Recorder()
        val transport = channel.transport()
        assertTrue(transport.start(recorder))
        assertEquals("ready", recorder.next())
        assertEquals("lost", recorder.next())
        assertEquals("closed:IO", recorder.next())
        verify(channel.request, timeout(TIMEOUT_MS)).close()
        verify(channel.connection, timeout(TIMEOUT_MS)).close()
        assertEquals(1, channel.buffers.size)
        verify(channel.connection, times(1)).requestWait()
        verify(channel.connection, times(1)).requestWait(0)
        verify(channel.request, times(1)).close()
        verify(other, never()).cancel()
        verify(other, never()).close()
        assertNull(recorder.quiet())
    }

    @Test
    fun repeatedReadErrorsDeliverLinkLossThenOneIoClosure() {
        val device = puck()
        val channel = Channel(device, device.getInterface(4))
        repeat(3) { channel.reads.add(channel.error) }
        val recorder = Recorder()
        val transport = channel.transport()

        assertTrue(transport.start(recorder))

        assertEquals("ready", recorder.next())
        assertEquals("lost", recorder.next())
        assertEquals("closed:IO", recorder.next())
        verify(channel.connection, timeout(TIMEOUT_MS)).close()
        verify(channel.connection).releaseInterface(channel.iface)

        val late = Completion()
        transport.writeFeature(feature(0x87), late)
        assertEquals(listOf(false), late.results.toList())
        transport.close()
        verify(channel.connection, times(1)).close()
        assertNull(recorder.quiet())
    }

    @Test
    fun securityExceptionDuringWriteClosesOnceWithPermission() {
        val device = puck()
        val channel = Channel(device, device.getInterface(5))
        channel.onControl = { throw SecurityException("revoked") }
        val recorder = Recorder()
        val transport = channel.transport()
        assertTrue(transport.start(recorder))
        assertEquals("ready", recorder.next())

        val write = Completion()
        transport.writeFeature(feature(0x87), write)
        repeat(3) { channel.reads.add(channel.error) }

        assertEquals(false, write.await())
        assertEquals("lost", recorder.next())
        assertEquals("closed:PERMISSION", recorder.next())
        verify(channel.connection, timeout(TIMEOUT_MS)).close()
        verify(channel.connection).releaseInterface(channel.iface)
        assertNull(recorder.quiet())
        assertEquals(1, write.calls.get())
    }

    @Test
    fun featureWriteCompletesTrueOnlyForAFullValidSetReport() {
        val device = puck()
        val channel = Channel(device, device.getInterface(4))
        val transport = channel.transport()

        val beforeStart = Completion()
        transport.writeFeature(feature(0x87), beforeStart)
        assertEquals(listOf(false), beforeStart.results.toList())
        assertTrue(transport.start(Recorder()))

        val full = Completion()
        transport.writeFeature(feature(0x87, 3, 9), full)
        assertEquals(true, full.await())
        val call = channel.controls.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        assertNotNull(call)
        assertEquals(0x21, call!!.requestType)
        assertEquals(0x09, call.request)
        assertEquals(0x0301, call.value)
        assertEquals(4, call.index)
        assertArrayEquals(feature(0x87, 3, 9), call.bytes)

        channel.onControl = { it.bytes.size - 1 }
        val partial = Completion()
        transport.writeFeature(feature(0x87), partial)
        assertEquals(false, partial.await())

        channel.onControl = { -1 }
        val timedOut = Completion()
        transport.writeFeature(feature(0x87), timedOut)
        assertEquals(false, timedOut.await())

        val invalid = listOf(ByteArray(63).also { it[0] = 1 }, ByteArray(64), ByteArray(65).also { it[0] = 1 }, ByteArray(0))
        for (report in invalid) {
            val rejected = Completion()
            transport.writeFeature(report, rejected)
            assertEquals(listOf(false), rejected.results.toList())
        }

        transport.close()
        val afterClose = Completion()
        transport.writeFeature(feature(0x87), afterClose)
        assertEquals(listOf(false), afterClose.results.toList())
        assertEquals(2, channel.controls.size)
    }

    @Test
    fun rumbleKeepsOnlyTheLatestStateWhileOutputIsBusy() {
        val device = puck()
        val channel = Channel(device, device.getInterface(5))
        val transport = channel.transport()
        assertTrue(transport.start(Recorder()))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        channel.onControl = {
            entered.countDown()
            release.await()
            it.bytes.size
        }

        val busy = Completion()
        transport.writeFeature(feature(0x87), busy)
        assertTrue(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))

        transport.writeRumble(rumble(1))
        transport.writeRumble(rumble(2))
        transport.writeRumble(rumble(3))
        val reused = feature(0x87, 7)
        val next = Completion()
        transport.writeFeature(reused, next)
        reused[2] = 0x55
        channel.onControl = { it.bytes.size }
        release.countDown()

        assertEquals(true, busy.await())
        assertEquals(true, next.await())
        assertArrayEquals(rumble(3), channel.rumbles.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertNull(channel.rumbles.poll())
        assertArrayEquals(feature(0x87, 7), channel.controls.last().bytes)
    }

    @Test
    fun wiredRumbleUsesOutputSetReportAndDropsInvalidReports() {
        val device = usbDevice(WIRED, listOf(usbInterface(0, subclass = BOOT), usbInterface(1)))
        val channel = Channel(device, device.getInterface(1))
        val transport = channel.transport()
        assertTrue(transport.start(Recorder()))

        transport.writeRumble(ByteArray(9).also { it[0] = 0x80.toByte() })
        transport.writeRumble(ByteArray(10).also { it[0] = 0x81.toByte() })
        transport.writeRumble(rumble(4))
        val call = channel.controls.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        assertNotNull(call)
        assertEquals(0x21, call!!.requestType)
        assertEquals(0x09, call.request)
        assertEquals(0x0280, call.value)
        assertEquals(1, call.index)
        assertArrayEquals(rumble(4), call.bytes)

        val fence = Completion()
        transport.writeFeature(feature(0x87), fence)
        assertEquals(true, fence.await())
        assertEquals(0x0301, channel.controls.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)?.value)
        assertNull(channel.controls.poll())
    }

    @Test
    fun closeCancelsQueuedWritesOnceAndReleasesAfterTransfersReturn() {
        val device = puck()
        val channel = Channel(device, device.getInterface(2))
        val recorder = Recorder()
        val transport = channel.transport()
        assertTrue(transport.start(recorder))
        assertEquals("ready", recorder.next())

        val readerEntered = CountDownLatch(1)
        val readerRelease = CountDownLatch(1)
        channel.reads.add { buffer ->
            readerEntered.countDown()
            readerRelease.await()
            byteArrayOf(0x42, 9).copyInto(buffer)
            2
        }
        val writerEntered = CountDownLatch(1)
        val writerRelease = CountDownLatch(1)
        channel.onControl = {
            writerEntered.countDown()
            writerRelease.await()
            it.bytes.size
        }
        val inFlight = Completion()
        transport.writeFeature(feature(0x87, 1), inFlight)
        assertTrue(writerEntered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertTrue(readerEntered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val queued = listOf(Completion(), Completion())
        queued.forEach { transport.writeFeature(feature(0x87, 2), it) }

        transport.close()

        queued.forEach { assertEquals(listOf(false), it.results.toList()) }
        verify(channel.request, times(1)).cancel()
        verify(channel.request, never()).close()
        verify(channel.connection, never()).releaseInterface(any())
        verify(channel.connection, never()).close()

        readerRelease.countDown()
        writerRelease.countDown()
        assertEquals(true, inFlight.await())
        verify(channel.request, timeout(TIMEOUT_MS)).close()
        verify(channel.connection, timeout(TIMEOUT_MS)).close()
        verify(channel.connection).releaseInterface(channel.iface)
        verify(channel.request, times(1)).close()
        // The report that completed after close was dropped
        assertNull(recorder.quiet())

        transport.close()
        verify(channel.connection, times(1)).close()
        assertEquals(1, inFlight.calls.get())
        queued.forEach { assertEquals(1, it.calls.get()) }
    }

    @Test
    fun rumbleFailureCompletesTheFeatureDequeuedInTheSameOutputPass() {
        val device = usbDevice(WIRED, listOf(usbInterface(1)))
        val channel = Channel(device, device.getInterface(0))
        val transport = channel.transport(); val recorder = Recorder()
        assertTrue(transport.start(recorder)); assertEquals("ready", recorder.next())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val count = AtomicInteger()
        channel.onControl = { call ->
            if (call.value == 0x0280) throw SecurityException("revoked during rumble")
            if (count.incrementAndGet() == 1) {
                entered.countDown()
                assertTrue(release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            }
            call.bytes.size
        }
        val busy = Completion(); transport.writeFeature(feature(0x87), busy)
        assertTrue(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        transport.writeRumble(rumble(12))
        val dequeued = Completion(); transport.writeFeature(feature(0x87, 9), dequeued)
        release.countDown()
        assertEquals(true, busy.await())
        assertEquals("lost", recorder.next()); assertEquals("closed:PERMISSION", recorder.next())
        assertEquals(false, dequeued.await())
        assertEquals(1, dequeued.calls.get())
    }

    @Test
    fun closeFromReportCallbackStopsDeliveryWithoutDeadlock() {
        val device = puck()
        val channel = Channel(device, device.getInterface(3))
        channel.data(0x42, 1)
        channel.data(0x42, 2)
        val transport = channel.transport()
        val recorder = Recorder()
        recorder.onReportHook = { transport.close() }

        assertTrue(transport.start(recorder))

        assertEquals("ready", recorder.next())
        assertEquals("report:4201", recorder.next())
        verify(channel.connection, timeout(TIMEOUT_MS)).close()
        verify(channel.connection).releaseInterface(channel.iface)
        assertNull(recorder.quiet())
    }

    @Test
    fun closingOnePuckChannelLeavesAnotherRunning() {
        val device = puck()
        val first = Channel(device, device.getInterface(2))
        val second = Channel(device, device.getInterface(3))
        val firstRecorder = Recorder()
        val secondRecorder = Recorder()
        val firstTransport = first.transport()
        assertTrue(firstTransport.start(firstRecorder))
        assertTrue(second.transport().start(secondRecorder))
        assertEquals("ready", firstRecorder.next())
        assertEquals("ready", secondRecorder.next())

        firstTransport.close()
        verify(first.connection, timeout(TIMEOUT_MS)).close()
        second.data(0x42, 3)

        assertEquals("report:4203", secondRecorder.next())
        verify(first.connection).releaseInterface(first.iface)
        verify(first.connection, never()).claimInterface(same(second.iface), anyBoolean())
        verify(second.connection, never()).claimInterface(same(first.iface), anyBoolean())
        verify(second.connection, never()).releaseInterface(any())
        verify(second.connection, never()).close()
    }

    private class ControlCall(val requestType: Int, val request: Int, val value: Int, val index: Int, val bytes: ByteArray)

    /** One channel's own asynchronous request; scripted bytes stand in for kernel completions. */
    private inner class Channel(val device: UsbDevice, val iface: UsbInterface) {
        val connection: UsbDeviceConnection = mock(UsbDeviceConnection::class.java)
        val request: UsbRequest = mock(UsbRequest::class.java)
        val buffers = CopyOnWriteArrayList<ByteBuffer>()
        private val pending = AtomicReference<ByteBuffer?>()
        private val cancelled = AtomicBoolean()
        val clock = AtomicLong()
        val reads = LinkedBlockingQueue<(ByteArray) -> Int>()
        val controls = LinkedBlockingQueue<ControlCall>()
        val rumbles = LinkedBlockingQueue<ByteArray>()

        @Volatile
        var onControl: (ControlCall) -> Int = { it.bytes.size }

        val idle: (ByteArray) -> Int = {
            clock.addAndGet(1000)
            -1
        }
        val error: (ByteArray) -> Int = { -1 }

        init {
            val input = iface.getEndpoint(0)
            val output = if (iface.endpointCount > 1) iface.getEndpoint(1) else null

            `when`(connection.claimInterface(any(), anyBoolean())).thenReturn(true)
            `when`(connection.releaseInterface(any())).thenReturn(true)
            `when`(request.initialize(connection, input)).thenReturn(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                doAnswer { invocation ->
                    submit(invocation.getArgument(0))
                }.`when`(request).queue(any(ByteBuffer::class.java))
                doAnswer { invocation ->
                    assertEquals(0L, invocation.getArgument<Long>(0))
                    awaitInput(true)
                }.`when`(connection).requestWait(anyLong())
            }
            @Suppress("DEPRECATION")
            doAnswer { invocation ->
                val buffer = invocation.getArgument<ByteBuffer>(0)
                assertEquals(buffer.capacity(), invocation.getArgument<Int>(1))
                submit(buffer)
            }.`when`(request).queue(any(ByteBuffer::class.java), anyInt())
            doAnswer { awaitInput(false) }.`when`(connection).requestWait()
            doAnswer { cancelled.set(true); true }.`when`(request).cancel()
            if (output != null) {
                doAnswer { invocation ->
                    val bytes = invocation.getArgument<ByteArray>(1).copyOf(invocation.getArgument(2))
                    rumbles.add(bytes)
                    bytes.size
                }.`when`(connection).bulkTransfer(same(output), any(), anyInt(), anyInt())
            }
            doAnswer { invocation ->
                val call = ControlCall(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument<ByteArray>(4).copyOf(invocation.getArgument(5))
                )
                val result = onControl(call)
                controls.add(call)
                result
            }.`when`(connection).controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt())
        }

        private fun submit(buffer: ByteBuffer): Boolean {
            assertTrue("one outstanding request per channel", pending.compareAndSet(null, buffer))
            buffers.add(buffer)
            return true
        }

        fun awaitInput(timed: Boolean): UsbRequest {
            val buffer = pending.get() ?: error("wait without a queued request")
            while (true) {
                if (cancelled.get()) {
                    pending.set(null)
                    return request
                }
                val begin = clock.get()
                val bytes = ByteArray(buffer.capacity())
                val count = (reads.poll(10, TimeUnit.MILLISECONDS) ?: idle)(bytes)
                if (count < 0 && clock.get() - begin >= 1000) {
                    if (timed) throw TimeoutException("idle")
                    continue // legacy requestWait blocks until completion or cancellation
                }
                if (count > 0) buffer.put(bytes, 0, count)
                pending.set(null)
                return request // zero-byte/error completion, not an idle timeout
            }
        }

        fun data(vararg bytes: Int) {
            reads.add { buffer ->
                bytes.forEachIndexed { i, b -> buffer[i] = b.toByte() }
                bytes.size
            }
        }

        fun transport(): SteamControllerUsbTransport =
            SteamControllerUsbTransport(device, connection, iface.id) { request }.also { transports.add(it) }
    }

    private class Recorder : SteamControllerTransport.Observer {
        val events = LinkedBlockingQueue<String>()
        val reports: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var onReportHook: (() -> Unit)? = null

        override fun onReady() {
            events.add("ready")
        }

        override fun onReport(report: ByteArray) {
            reports.add(report)
            events.add("report:" + report.joinToString("") { "%02x".format(it) })
            onReportHook?.invoke()
        }

        override fun onLinkLost() {
            events.add("lost")
        }

        override fun onClosed(failure: Failure) {
            events.add("closed:$failure")
        }

        fun next(): String? = events.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        fun quiet(): String? = events.poll(100, TimeUnit.MILLISECONDS)
    }

    private class Completion : (Boolean) -> Unit {
        val results = LinkedBlockingQueue<Boolean>()
        val calls = AtomicInteger()

        override fun invoke(written: Boolean) {
            calls.incrementAndGet()
            results.add(written)
        }

        fun await(): Boolean? = results.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun puck(): UsbDevice = usbDevice(
        PUCK,
        (0..5).map { id ->
            if (id < 2) usbInterface(id, subclass = BOOT) else usbInterface(id, endpoints = listOf(endpoint(IN), endpoint(OUT)))
        }
    )

    private fun usbDevice(productId: Int, interfaces: List<UsbInterface>, vendorId: Int = VALVE): UsbDevice =
        mock(UsbDevice::class.java).also { device ->
            `when`(device.vendorId).thenReturn(vendorId)
            `when`(device.productId).thenReturn(productId)
            `when`(device.interfaceCount).thenReturn(interfaces.size)
            interfaces.forEachIndexed { i, iface -> `when`(device.getInterface(i)).thenReturn(iface) }
        }

    private fun usbInterface(
        id: Int,
        interfaceClass: Int = UsbConstants.USB_CLASS_HID,
        subclass: Int = 0,
        endpoints: List<UsbEndpoint> = listOf(endpoint(IN))
    ): UsbInterface = mock(UsbInterface::class.java).also { iface ->
        `when`(iface.id).thenReturn(id)
        `when`(iface.interfaceClass).thenReturn(interfaceClass)
        `when`(iface.interfaceSubclass).thenReturn(subclass)
        `when`(iface.endpointCount).thenReturn(endpoints.size)
        endpoints.forEachIndexed { i, endpoint -> `when`(iface.getEndpoint(i)).thenReturn(endpoint) }
    }

    private fun endpoint(direction: Int, type: Int = UsbConstants.USB_ENDPOINT_XFER_INT): UsbEndpoint =
        mock(UsbEndpoint::class.java).also { endpoint ->
            `when`(endpoint.direction).thenReturn(direction)
            `when`(endpoint.type).thenReturn(type)
            `when`(endpoint.maxPacketSize).thenReturn(64)
        }

    private fun feature(vararg body: Int): ByteArray = ByteArray(64).also { report ->
        report[0] = 1
        body.forEachIndexed { i, b -> report[i + 1] = b.toByte() }
    }

    private fun rumble(strength: Int): ByteArray = ByteArray(10).also { report ->
        report[0] = 0x80.toByte()
        report[3] = strength.toByte()
    }

    private companion object {
        const val VALVE = 0x28de
        const val WIRED = 0x1302
        const val PUCK = 0x1304
        const val STEAM_DECK = 0x1205
        const val BOOT = 1
        const val IN = UsbConstants.USB_DIR_IN
        const val OUT = UsbConstants.USB_DIR_OUT
        const val TIMEOUT_MS = 2000L
    }
}
