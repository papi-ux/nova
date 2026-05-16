package com.papi.nova

import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisCapabilities
import com.papi.nova.binding.input.capture.InputCaptureProvider
import com.papi.nova.manager.FeatureFlagManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class GameCursorSyncTest {

    @After
    fun tearDown() {
        FeatureFlagManager.reset()
        Game.isStreamActive = false
    }

    @Test
    fun handleStreamStarted_syncsInitialHostCursorState() {
        val game = Game()
        try {
            val client = Mockito.mock(PolarisApiClient::class.java)
            Mockito.`when`(client.setCursorVisibility(true)).thenReturn(true)

            setField(game, "novaApiClient", client)
            setField(game, "cursorVisible", false)
            setCapabilities(
                PolarisCapabilities(
                    server = "polaris",
                    version = "1.0.0",
                    features = PolarisCapabilities.Features(cursorVisibilityControl = true),
                    capture = PolarisCapabilities.CaptureInfo()
                )
            )

            game.handleStreamStartedState()

            verify(client, timeout(1000)).setCursorVisibility(true)
            assertTrue(Game.isStreamActive)
            assertTrue(game.connected)
            assertFalse(getBooleanField(game, "connecting"))
        } finally {
            shutdownCursorVisibilitySync(game)
        }
    }

    @Test
    fun setLocalCursorVisible_appliesLatestHostCursorStateAfterSlowInitialSync() {
        val game = Game()
        try {
            val client = Mockito.mock(PolarisApiClient::class.java)
            val inputCaptureProvider = Mockito.mock(InputCaptureProvider::class.java)
            val appliedHostCursorVisible = AtomicBoolean(true)
            val callCount = AtomicInteger()
            val firstCallStarted = CountDownLatch(1)
            val allowFirstCallToFinish = CountDownLatch(1)
            val bothCallsCompleted = CountDownLatch(2)

            doAnswer { invocation ->
                val visible = invocation.getArgument<Boolean>(0)
                if (callCount.getAndIncrement() == 0) {
                    firstCallStarted.countDown()
                    assertTrue(allowFirstCallToFinish.await(1, TimeUnit.SECONDS))
                }

                appliedHostCursorVisible.set(visible)
                bothCallsCompleted.countDown()
                true
            }.`when`(client).setCursorVisibility(anyBoolean())

            setField(game, "novaApiClient", client)
            setField(game, "inputCaptureProvider", inputCaptureProvider)
            setField(game, "cursorVisible", false)
            setCapabilities(
                PolarisCapabilities(
                    server = "polaris",
                    version = "1.0.0",
                    features = PolarisCapabilities.Features(cursorVisibilityControl = true),
                    capture = PolarisCapabilities.CaptureInfo()
                )
            )

            game.handleStreamStartedState()
            assertTrue(firstCallStarted.await(1, TimeUnit.SECONDS))

            invokeSetLocalCursorVisible(game, true)
            allowFirstCallToFinish.countDown()

            assertTrue(bothCallsCompleted.await(1, TimeUnit.SECONDS))
            assertFalse(appliedHostCursorVisible.get())
        } finally {
            shutdownCursorVisibilitySync(game)
        }
    }

    private fun setCapabilities(capabilities: PolarisCapabilities) {
        val field = FeatureFlagManager::class.java.getDeclaredField("capabilities")
        field.isAccessible = true
        field.set(null, capabilities)
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getBooleanField(target: Any, fieldName: String): Boolean {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.getBoolean(target)
    }

    private fun invokeSetLocalCursorVisible(game: Game, visible: Boolean) {
        val method = Game::class.java.getDeclaredMethod(
            "setLocalCursorVisible",
            Boolean::class.javaPrimitiveType!!
        )
        method.isAccessible = true
        method.invoke(game, visible)
    }

    private fun shutdownCursorVisibilitySync(game: Game) {
        val field = Game::class.java.getDeclaredField("cursorVisibilitySyncExecutor")
        field.isAccessible = true
        (field.get(game) as ExecutorService).shutdownNow()
    }
}
