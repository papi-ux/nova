package com.papi.nova

import android.content.Context
import android.content.Intent
import com.papi.nova.shadows.ShadowMoonBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
class PyroWaveLaunchAvailabilityTest {
    @Test fun restoredPerGamePyrowaveIsRefusedBeforeHostInitializationAndKeepsThePreference() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
        val key = "video_codec_override_4:host:uuid:game"
        prefs.edit().putString(key, "forcepyrowave").commit()
        val intent = Intent(context, Game::class.java)
            .putExtra(Game.EXTRA_PC_UUID, "host").putExtra(Game.EXTRA_APP_UUID, "game")
            .putExtra(Game.EXTRA_APP_ID, 1)
        val controller = Robolectric.buildActivity(Game::class.java, intent).create()
        try {
            assertTrue(controller.get().isFinishing)
            assertEquals(context.getString(R.string.nova_pyrowave_library_unavailable), ShadowToast.getTextOfLatestToast())
            assertEquals("forcepyrowave", prefs.getString(key, null))
        } finally {
            controller.destroy()
            prefs.edit().clear().commit()
        }
    }
}
