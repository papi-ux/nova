package com.papi.nova

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.papi.nova.binding.video.MediaCodecDecoderRenderer
import com.papi.nova.binding.video.buildBenchmarkRunJson
import java.io.File
import java.io.IOException

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
 * The same memory also notes the app must not be in Android's "stopped
 * state" (e.g. just force-stopped) or the broadcast won't wake it at all.
 */
class BenchmarkControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_EXPORT -> handleExport(context)
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
        val durationToleranceNs = intent.getLongExtra(EXTRA_DURATION_TOLERANCE_NS, -1L)
        if (durationToleranceNs <= 0L) {
            Log.w(TAG, "benchmark start ignored: missing/invalid $EXTRA_DURATION_TOLERANCE_NS")
            return
        }
        // Nova has no drain step of its own (see
        // MediaCodecDecoderRenderer.BenchmarkRunState's doc comment) - this
        // is pure pass-through for schema completeness, so an
        // absent/negative value is coerced rather than rejected.
        val drainGraceNs = intent.getLongExtra(EXTRA_DRAIN_GRACE_NS, 0L).coerceAtLeast(0L)
        val manifestSha256 = intent.getStringExtra(EXTRA_MANIFEST_SHA256)

        val armed = Game.armBenchmarkCapture(runId, expectedDurationNs, durationToleranceNs, drainGraceNs, manifestSha256)
        Log.i(
            TAG,
            "benchmark start runId=$runId expectedDurationNs=$expectedDurationNs " +
                "durationToleranceNs=$durationToleranceNs drainGraceNs=$drainGraceNs armed=$armed",
        )
    }

    private fun handleStop() {
        val result = Game.stopBenchmarkCapture()
        lastResult = result
        Log.i(TAG, "benchmark stop runId=${result?.runId} stopped=${result != null}")
    }

    /**
     * Serializes the most recently stopped run (handleStop's lastResult,
     * piece 4) to measurement-spec-v1.md 7.2's JSON schema and writes it
     * under this app's private files dir, where `adb run-as
     * <applicationId> cat files/benchmark_runs/<run_id>.json` can pull it
     * (run-as works because the benchmark build type is debuggable - see
     * app/build.gradle's `initWith release` override for this buildType).
     * A separate action from stop rather than folded into it, so the
     * harness can retry extraction without needing to re-run anything.
     */
    private fun handleExport(context: Context) {
        val result = lastResult
        if (result == null) {
            Log.w(TAG, "benchmark export ignored: no stopped run available")
            return
        }
        val json = buildBenchmarkRunJson(result, "nova-" + BuildConfig.VERSION_NAME)
        val dir = File(context.filesDir, "benchmark_runs")
        dir.mkdirs()
        val file = File(dir, "${result.runId}.json")
        try {
            file.writeText(json)
            Log.i(
                TAG,
                "benchmark export wrote runId=${result.runId} path=${file.absolutePath} bytes=${json.length}",
            )
        } catch (e: IOException) {
            Log.e(TAG, "benchmark export failed runId=${result.runId}", e)
        }
    }

    companion object {
        private const val TAG = "NovaBenchmarkCtrl"

        const val ACTION_START = "com.papi.nova.action.BENCHMARK_START"
        const val ACTION_STOP = "com.papi.nova.action.BENCHMARK_STOP"
        const val ACTION_EXPORT = "com.papi.nova.action.BENCHMARK_EXPORT"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_EXPECTED_DURATION_NS = "expected_duration_ns"
        const val EXTRA_DURATION_TOLERANCE_NS = "duration_tolerance_ns"
        const val EXTRA_DRAIN_GRACE_NS = "drain_grace_ns"
        const val EXTRA_MANIFEST_SHA256 = "manifest_sha256"

        // Stashed here by handleStop for handleExport (or a future retry)
        // to read and serialize. Overwritten by the next stop; the caller
        // owns exporting before starting the next run.
        @Volatile
        var lastResult: MediaCodecDecoderRenderer.BenchmarkRunResult? = null
            private set
    }
}
