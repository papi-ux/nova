package com.papi.nova;

import com.papi.nova.api.PolarisApiClient;
import com.papi.nova.api.PolarisCapabilities;
import com.papi.nova.binding.input.capture.InputCaptureProvider;
import com.papi.nova.manager.FeatureFlagManager;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class GameCursorSyncTest {

    @After
    public void tearDown() {
        FeatureFlagManager.INSTANCE.reset();
        Game.isStreamActive = false;
    }

    @Test
    public void handleStreamStarted_syncsInitialHostCursorState() throws Exception {
        Game game = new Game();
        try {
            PolarisApiClient client = Mockito.mock(PolarisApiClient.class);
            when(client.setCursorVisibility(true)).thenReturn(true);

            setField(game, "novaApiClient", client);
            setField(game, "cursorVisible", false);
            setCapabilities(new PolarisCapabilities(
                    "polaris",
                    "1.0.0",
                    new PolarisCapabilities.Features(false, false, false, false, false, false, false, true),
                    new PolarisCapabilities.CaptureInfo()
            ));

            game.handleStreamStartedState();

            verify(client, timeout(1000)).setCursorVisibility(true);
            assertTrue(Game.isStreamActive);
            assertTrue(game.connected);
            assertFalse(getBooleanField(game, "connecting"));
        } finally {
            shutdownCursorVisibilitySync(game);
        }
    }

    @Test
    public void setLocalCursorVisible_appliesLatestHostCursorStateAfterSlowInitialSync() throws Exception {
        Game game = new Game();
        try {
            PolarisApiClient client = Mockito.mock(PolarisApiClient.class);
            InputCaptureProvider inputCaptureProvider = Mockito.mock(InputCaptureProvider.class);
            AtomicBoolean appliedHostCursorVisible = new AtomicBoolean(true);
            AtomicInteger callCount = new AtomicInteger();
            CountDownLatch firstCallStarted = new CountDownLatch(1);
            CountDownLatch allowFirstCallToFinish = new CountDownLatch(1);
            CountDownLatch bothCallsCompleted = new CountDownLatch(2);

            doAnswer(invocation -> {
                boolean visible = invocation.getArgument(0);
                if (callCount.getAndIncrement() == 0) {
                    firstCallStarted.countDown();
                    assertTrue(allowFirstCallToFinish.await(1, TimeUnit.SECONDS));
                }

                appliedHostCursorVisible.set(visible);
                bothCallsCompleted.countDown();
                return true;
            }).when(client).setCursorVisibility(anyBoolean());

            setField(game, "novaApiClient", client);
            setField(game, "inputCaptureProvider", inputCaptureProvider);
            setField(game, "cursorVisible", false);
            setCapabilities(new PolarisCapabilities(
                    "polaris",
                    "1.0.0",
                    new PolarisCapabilities.Features(false, false, false, false, false, false, false, true),
                    new PolarisCapabilities.CaptureInfo()
            ));

            game.handleStreamStartedState();
            assertTrue(firstCallStarted.await(1, TimeUnit.SECONDS));

            invokeSetLocalCursorVisible(game, true);
            allowFirstCallToFinish.countDown();

            assertTrue(bothCallsCompleted.await(1, TimeUnit.SECONDS));
            assertFalse(appliedHostCursorVisible.get());
        } finally {
            shutdownCursorVisibilitySync(game);
        }
    }

    private static void setCapabilities(PolarisCapabilities capabilities) throws Exception {
        Field field = FeatureFlagManager.class.getDeclaredField("capabilities");
        field.setAccessible(true);
        field.set(null, capabilities);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean getBooleanField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void invokeSetLocalCursorVisible(Game game, boolean visible) throws Exception {
        Method method = Game.class.getDeclaredMethod("setLocalCursorVisible", boolean.class);
        method.setAccessible(true);
        method.invoke(game, visible);
    }

    private static void shutdownCursorVisibilitySync(Game game) throws Exception {
        Field field = Game.class.getDeclaredField("cursorVisibilitySyncExecutor");
        field.setAccessible(true);
        ((ExecutorService) field.get(game)).shutdownNow();
    }
}
