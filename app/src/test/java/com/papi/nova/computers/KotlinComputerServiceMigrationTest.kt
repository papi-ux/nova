package com.papi.nova.computers

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvHTTP
import java.io.File
import java.util.concurrent.ScheduledFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class KotlinComputerServiceMigrationTest {
    @Test
    fun computerManagerServiceIsKotlinSource() {
        val javaFile = File("src/main/java/com/papi/nova/computers/ComputerManagerService.java")
        val kotlinFile = File("src/main/java/com/papi/nova/computers/ComputerManagerService.kt")

        assertFalse("ComputerManagerService should no longer be a Java source", javaFile.exists())
        assertTrue("ComputerManagerService should be migrated to Kotlin", kotlinFile.exists())
    }

    @Test
    fun computerManagerServiceKeepsJavaCompatibleBinderAndPollerApis() {
        val booleanType = Boolean::class.javaPrimitiveType!!

        assertTrue(Service::class.java.isAssignableFrom(ComputerManagerService::class.java))
        ComputerManagerService::class.java.getConstructor()
        assertEquals(IBinder::class.java, ComputerManagerService::class.java.getMethod("onBind", Intent::class.java).returnType)
        assertEquals(booleanType, ComputerManagerService::class.java.getMethod("onUnbind", Intent::class.java).returnType)
        ComputerManagerService::class.java.getMethod("addComputerBlocking", ComputerDetails::class.java)
        ComputerManagerService::class.java.getMethod("removeComputer", ComputerDetails::class.java)

        assertTrue(Binder::class.java.isAssignableFrom(ComputerManagerService.ComputerManagerBinder::class.java))
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("startPolling", ComputerManagerListener::class.java)
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("waitForReady")
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("waitForPollingStopped")
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("addComputerBlocking", ComputerDetails::class.java)
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("removeComputer", ComputerDetails::class.java)
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("stopPolling")
        assertEquals(
            ComputerManagerService.ApplistPoller::class.java,
            ComputerManagerService.ComputerManagerBinder::class.java.getMethod(
                "createAppListPoller",
                ComputerDetails::class.java
            ).returnType
        )
        assertEquals(
            String::class.java,
            ComputerManagerService.ComputerManagerBinder::class.java.getMethod("getUniqueId").returnType
        )
        assertEquals(
            ComputerDetails::class.java,
            ComputerManagerService.ComputerManagerBinder::class.java.getMethod("getComputer", String::class.java).returnType
        )
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("persistComputer", ComputerDetails::class.java)
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("persistComputerState", String::class.java)
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("invalidateStateForComputer", String::class.java)

        val pollerConstructor = ComputerManagerService.ApplistPoller::class.java.getConstructor(
            ComputerManagerService::class.java,
            ComputerDetails::class.java
        )
        assertNotNull(pollerConstructor)
        ComputerManagerService.ApplistPoller::class.java.getMethod("pollNow")
        ComputerManagerService.ApplistPoller::class.java.getMethod("start")
        ComputerManagerService.ApplistPoller::class.java.getMethod("stop")
    }

    @Test
    fun binderAndPollerKeepFocusedLifecycleHelpers() {
        val service = ComputerManagerService()
        val binder = service.onBind(null) as ComputerManagerService.ComputerManagerBinder

        assertNull(binder.getComputer("missing"))

        val computer = ComputerDetails()
        computer.name = "Nova Test PC"
        val poller = binder.createAppListPoller(computer)

        assertNotNull(poller)
        poller.pollNow()
        poller.stop()
    }

    @Test
    fun blankUuidManualPollAcceptsReturnedComputerUuid() {
        val service = ComputerManagerService()
        val matcher = ComputerManagerService::class.java.getDeclaredMethod(
            "isExpectedComputerUuid",
            String::class.java,
            String::class.java
        )
        matcher.isAccessible = true

        assertEquals(true, matcher.invoke(service, null, "server-uuid"))
        assertEquals(true, matcher.invoke(service, "", "server-uuid"))
        assertEquals(true, matcher.invoke(service, "server-uuid", "server-uuid"))
        assertEquals(false, matcher.invoke(service, "other-uuid", "server-uuid"))
        assertEquals(false, matcher.invoke(service, "", null))
    }

    @Test
    fun parallelPollingRetriesSavedLocalAddressOnDefaultHttpPort() {
        val details = ComputerDetails()
        details.localAddress = ComputerDetails.AddressTuple("10.0.0.232", 49000)

        val addresses = ComputerManagerService.buildParallelPollAddresses(details)

        assertEquals(ComputerDetails.AddressTuple("10.0.0.232", 49000), addresses[0])
        assertEquals(
            ComputerDetails.AddressTuple("10.0.0.232", NvHTTP.DEFAULT_HTTP_PORT),
            addresses[1]
        )
    }

    @Test
    fun parallelPollingDoesNotDuplicateDefaultLocalAddress() {
        val details = ComputerDetails()
        details.localAddress = ComputerDetails.AddressTuple("10.0.0.232", NvHTTP.DEFAULT_HTTP_PORT)

        val addresses = ComputerManagerService.buildParallelPollAddresses(details)

        assertEquals(1, addresses.size)
        assertEquals(
            ComputerDetails.AddressTuple("10.0.0.232", NvHTTP.DEFAULT_HTTP_PORT),
            addresses[0]
        )
    }

    @Test
    fun pollingTupleAndReachabilityTupleKeepJavaFieldShape() {
        val computer = ComputerDetails()
        computer.uuid = "service-test"

        val pollingTupleClass = Class.forName("com.papi.nova.computers.PollingTuple")
        val pollingTupleConstructor = pollingTupleClass.getConstructor(ComputerDetails::class.java)
        val pollingTuple = pollingTupleConstructor.newInstance(computer)

        val future = pollingTupleClass.getField("future")
        val tupleComputer = pollingTupleClass.getField("computer")
        val networkLock = pollingTupleClass.getField("networkLock")
        val lastSuccessfulPollMs = pollingTupleClass.getField("lastSuccessfulPollMs")
        val offlineCount = pollingTupleClass.getField("offlineCount")

        assertEquals(ScheduledFuture::class.java, future.type)
        assertEquals(ComputerDetails::class.java, tupleComputer.type)
        assertEquals(Any::class.java, networkLock.type)
        assertEquals(Long::class.javaPrimitiveType!!, lastSuccessfulPollMs.type)
        assertEquals(Int::class.javaPrimitiveType!!, offlineCount.type)
        assertSame(computer, tupleComputer.get(pollingTuple))
        assertNotNull(networkLock.get(pollingTuple))

        val reachabilityTupleClass = Class.forName("com.papi.nova.computers.ReachabilityTuple")
        val reachabilityTupleConstructor = reachabilityTupleClass.getConstructor(ComputerDetails::class.java, String::class.java)
        val reachabilityTuple = reachabilityTupleConstructor.newInstance(computer, "192.0.2.10")

        val reachableAddress = reachabilityTupleClass.getField("reachableAddress")
        val reachableComputer = reachabilityTupleClass.getField("computer")
        assertEquals(String::class.java, reachableAddress.type)
        assertEquals(ComputerDetails::class.java, reachableComputer.type)
        assertEquals("192.0.2.10", reachableAddress.get(reachabilityTuple))
        assertSame(computer, reachableComputer.get(reachabilityTuple))
    }
}
