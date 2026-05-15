package com.papi.nova.computers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Service;
import android.os.Binder;
import android.os.IBinder;

import com.papi.nova.nvstream.http.ComputerDetails;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.ScheduledFuture;

@RunWith(RobolectricTestRunner.class)
public class KotlinComputerServiceMigrationTest {
    @Test
    public void computerManagerServiceIsKotlinSource() {
        File javaFile = new File("src/main/java/com/papi/nova/computers/ComputerManagerService.java");
        File kotlinFile = new File("src/main/java/com/papi/nova/computers/ComputerManagerService.kt");

        assertFalse("ComputerManagerService should no longer be a Java source", javaFile.exists());
        assertTrue("ComputerManagerService should be migrated to Kotlin", kotlinFile.exists());
    }

    @Test
    public void computerManagerServiceKeepsJavaCompatibleBinderAndPollerApis() throws Exception {
        assertTrue(Service.class.isAssignableFrom(ComputerManagerService.class));
        ComputerManagerService.class.getConstructor();
        assertEquals(IBinder.class, ComputerManagerService.class.getMethod("onBind", android.content.Intent.class).getReturnType());
        assertEquals(boolean.class, ComputerManagerService.class.getMethod("onUnbind", android.content.Intent.class).getReturnType());
        ComputerManagerService.class.getMethod("addComputerBlocking", ComputerDetails.class);
        ComputerManagerService.class.getMethod("removeComputer", ComputerDetails.class);

        assertTrue(Binder.class.isAssignableFrom(ComputerManagerService.ComputerManagerBinder.class));
        ComputerManagerService.ComputerManagerBinder.class.getMethod("startPolling", ComputerManagerListener.class);
        ComputerManagerService.ComputerManagerBinder.class.getMethod("waitForReady");
        ComputerManagerService.ComputerManagerBinder.class.getMethod("waitForPollingStopped");
        ComputerManagerService.ComputerManagerBinder.class.getMethod("addComputerBlocking", ComputerDetails.class);
        ComputerManagerService.ComputerManagerBinder.class.getMethod("removeComputer", ComputerDetails.class);
        ComputerManagerService.ComputerManagerBinder.class.getMethod("stopPolling");
        assertEquals(ComputerManagerService.ApplistPoller.class,
                ComputerManagerService.ComputerManagerBinder.class.getMethod("createAppListPoller", ComputerDetails.class).getReturnType());
        assertEquals(String.class, ComputerManagerService.ComputerManagerBinder.class.getMethod("getUniqueId").getReturnType());
        assertEquals(ComputerDetails.class,
                ComputerManagerService.ComputerManagerBinder.class.getMethod("getComputer", String.class).getReturnType());
        ComputerManagerService.ComputerManagerBinder.class.getMethod("persistComputer", ComputerDetails.class);
        ComputerManagerService.ComputerManagerBinder.class.getMethod("persistComputerState", String.class);
        ComputerManagerService.ComputerManagerBinder.class.getMethod("invalidateStateForComputer", String.class);

        Constructor<ComputerManagerService.ApplistPoller> pollerConstructor =
                ComputerManagerService.ApplistPoller.class.getConstructor(ComputerManagerService.class, ComputerDetails.class);
        assertNotNull(pollerConstructor);
        ComputerManagerService.ApplistPoller.class.getMethod("pollNow");
        ComputerManagerService.ApplistPoller.class.getMethod("start");
        ComputerManagerService.ApplistPoller.class.getMethod("stop");
    }

    @Test
    public void binderAndPollerKeepFocusedLifecycleHelpers() {
        ComputerManagerService service = new ComputerManagerService();
        ComputerManagerService.ComputerManagerBinder binder =
                (ComputerManagerService.ComputerManagerBinder) service.onBind(null);

        assertNull(binder.getComputer("missing"));

        ComputerDetails computer = new ComputerDetails();
        computer.name = "Nova Test PC";
        ComputerManagerService.ApplistPoller poller = binder.createAppListPoller(computer);

        assertNotNull(poller);
        poller.pollNow();
        poller.stop();
    }

    @Test
    public void pollingTupleAndReachabilityTupleKeepJavaFieldShape() throws Exception {
        ComputerDetails computer = new ComputerDetails();
        computer.uuid = "service-test";

        Class<?> pollingTupleClass = Class.forName("com.papi.nova.computers.PollingTuple");
        Constructor<?> pollingTupleConstructor = pollingTupleClass.getConstructor(ComputerDetails.class);
        Object pollingTuple = pollingTupleConstructor.newInstance(computer);

        Field future = pollingTupleClass.getField("future");
        Field tupleComputer = pollingTupleClass.getField("computer");
        Field networkLock = pollingTupleClass.getField("networkLock");
        Field lastSuccessfulPollMs = pollingTupleClass.getField("lastSuccessfulPollMs");
        Field offlineCount = pollingTupleClass.getField("offlineCount");

        assertEquals(ScheduledFuture.class, future.getType());
        assertEquals(ComputerDetails.class, tupleComputer.getType());
        assertEquals(Object.class, networkLock.getType());
        assertEquals(long.class, lastSuccessfulPollMs.getType());
        assertEquals(int.class, offlineCount.getType());
        assertSame(computer, tupleComputer.get(pollingTuple));
        assertNotNull(networkLock.get(pollingTuple));

        Class<?> reachabilityTupleClass = Class.forName("com.papi.nova.computers.ReachabilityTuple");
        Constructor<?> reachabilityTupleConstructor =
                reachabilityTupleClass.getConstructor(ComputerDetails.class, String.class);
        Object reachabilityTuple = reachabilityTupleConstructor.newInstance(computer, "192.0.2.10");

        Field reachableAddress = reachabilityTupleClass.getField("reachableAddress");
        Field reachableComputer = reachabilityTupleClass.getField("computer");
        assertEquals(String.class, reachableAddress.getType());
        assertEquals(ComputerDetails.class, reachableComputer.getType());
        assertEquals("192.0.2.10", reachableAddress.get(reachabilityTuple));
        assertSame(computer, reachableComputer.get(reachabilityTuple));
    }
}
