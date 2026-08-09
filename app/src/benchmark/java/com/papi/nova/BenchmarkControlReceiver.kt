package com.papi.nova

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.papi.nova.binding.video.MediaCodecDecoderRenderer

/**
 * Nordstern P0-4A (measurement-spec-v1.md 7.2) adb-driven benchmark
 * control path. Only present in the `benchmark` build type (this file
 * lives under app/src/benchmark, not app/src/main) - structurally absent
 * from release, dirty, and preRelease, so it is never a production
 * control surface no matter its manifest flags.
 *
 * Must be android:exported="true" in the benchmark manifest overlay:
 * verified empirically on a real device (RP6, a `user`-build, unrooted
 * target - see nordstern-nova-benchmark-control-path-adb-reachability
 * memory) that `adb shell am broadcast` cannot reach a non-exported
 * manifest receiver even when explicitly targeted by component name, and
 * that implicit (`-a <action> -p <package>`, no `-n`) broadcasts don't
 * reach a manifest receiver at all regardless of exported - Android's
 * post-O implicit-broadcast background restrictions block that
 * independently of the exported flag. Callers must use explicit
 * component targeting: `-n <applicationId>/com.papi.nova.BenchmarkControlReceiver`.
 */
class BenchmarkControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
        }
    }

    private fun handleStart(intent: Intent) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID)
        if (runId.isNullOrEmpty()) {
            Log.w(TAG, "benchmark start ignored: missing $EXTRA_RUN_ID")
            return
        }
        val expectedDurationNs = intent.getLongExtra(EXTRA_EXPECTED_DURATION_NS, -1L)
        if (expectedDurationNs <= 0L) {
            Log.w(TAG, "benchmark start ignored: missing/invalid $EXTRA_EXPECTED_DURATION_NS")
            return
        }
        val armed = Game.armBenchmarkCapture(runId, expectedDurationNs)
        Log.i(TAG, "benchmark start runId=$runId expectedDurationNs=$expectedDurationNs armed=$armed")
    }

    private fun handleStop() {
        val result = Game.stopBenchmarkCapture()
        lastResult = result
        Log.i(TAG, "benchmark stop runId=${result?.runId} stopped=${result != null}")
    }

    companion object {
        private const val TAG = "NovaBenchmarkCtrl"

        const val ACTION_START = "com.papi.nova.action.BENCHMARK_START"
        const val ACTION_STOP = "com.papi.nova.action.BENCHMARK_STOP"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_EXPECTED_DURATION_NS = "expected_duration_ns"

        // Stashed here for piece 5 (frozen JSON export + adb run-as
        // extraction) to read and serialize. Overwritten by the next
        // stop; piece 5 owns exporting before the next run starts.
        @Volatile
        var lastResult: MediaCodecDecoderRenderer.BenchmarkRunResult? = null
            private set
    }
}
