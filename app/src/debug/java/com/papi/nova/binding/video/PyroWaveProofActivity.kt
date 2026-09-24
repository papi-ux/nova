package com.papi.nova.binding.video

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import com.papi.nova.LimeLog

/**
 * Draws one decoded frame and says whether it worked.
 *
 * A debug build only screen, with no way to reach it from the app: it is started with
 * `adb shell am start -n <package>/com.papi.nova.binding.video.PyroWaveProofActivity`. It exists
 * because presentation is the one part of a video client that cannot be tested by asserting on a
 * return value, and a photograph of a screen is the only honest proof.
 */
private const val PYROWAVE_FORMAT = 0x10000

class PyroWaveProofActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var view: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = SurfaceView(this)
        view.holder.addCallback(this)
        setContentView(view)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Off the main thread: it makes a Vulkan device, a swapchain and a pipeline, none of which
        // belong in the callback that is holding up the first frame of the window.
        Thread {
            // The GPU path first, because it is the one a stream uses. The host memory path runs
            // after it on the same surface, so a disagreement between the two says where the fault
            // is: same picture and the decode targets are right, different and they are not.
            val uploaded = PyroWave.presentSelfTest(applicationContext, holder.surface)
            val decoded = PyroWave.gpuDecodeSelfTest(applicationContext, holder.surface)
            val session = driveSessionRenderer(holder.surface)
            val message = when {
                decoded == null || uploaded == null -> "PyroWave: no native library"
                decoded == 0 && uploaded == 0 && session -> "PyroWave: all three paths drew"
                else -> "PyroWave: GPU $decoded, upload $uploaded, session $session"
            }
            LimeLog.info(message)
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }.start()
    }

    /**
     * The renderer a session builds, driven the way a session drives it.
     *
     * The same class, the same calls in the same order and the same JNI, with the network taken
     * out and one frame read off disk in its place. It is the closest thing to a stream that can be
     * run without a host, and it is what catches a renderer that works when called directly and
     * falls over when called through the contract Game holds it by.
     */
    private fun driveSessionRenderer(surface: android.view.Surface): Boolean {
        val bitstream = try {
            assets.open("pyrowave/selftest-34x30.pw").use { it.readBytes() }
        } catch (e: Exception) {
            LimeLog.warning("PyroWave: no frame to drive the renderer with: " + e.message)
            return false
        }

        val renderer = PyroWaveDecoderRenderer()
        renderer.setRenderTarget(surface)
        if (renderer.setup(PYROWAVE_FORMAT, 34, 30, 60) != 0) {
            return false
        }

        renderer.start()
        // Once: this is one frame, and a frame is its sequence number. The decoder drops a sequence
        // it has already decoded, so pushing these bytes twice tests that it says no.
        val outcome = renderer.submitDecodeUnit(
            bitstream, bitstream.size, 0, 0, 2, 0.toChar(), 0L, 0L)
        val shown = renderer.framesShown
        val refused = renderer.framesRefused
        renderer.stop()
        renderer.cleanup()

        // The counts, not the return value. A frame that does not reach the screen is counted rather
        // than refused, because refusing asks the library for a keyframe and every frame of this
        // codec already is one, so the submit returns DR_OK either way.
        return outcome == com.papi.nova.nvstream.jni.MoonBridge.DR_OK && shown == 1L && refused == 0L
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
}
