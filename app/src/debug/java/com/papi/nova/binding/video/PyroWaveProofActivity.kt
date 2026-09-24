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
            val message = when {
                decoded == null || uploaded == null -> "PyroWave: no native library"
                decoded == 0 && uploaded == 0 -> "PyroWave: both paths put a frame on the screen"
                decoded == 0 -> "PyroWave: GPU decode drew, the upload path failed ($uploaded)"
                uploaded == 0 -> "PyroWave: the upload path drew, GPU decode failed ($decoded)"
                else -> "PyroWave: neither path drew (GPU $decoded, upload $uploaded)"
            }
            LimeLog.info(message)
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
}
