package com.papi.nova.binding.video

import com.papi.nova.LimeLog

/**
 * The GPU half of the SGSR 1 checks (#304, L2), in debug builds only.
 *
 * Draws known pictures offscreen at an output size, once with SGSR 1 and once with the present shader,
 * reads the pixels back and compares them with [PyroWaveSgsrReference]. Nothing starts it but
 * [PyroWaveProofActivity]; a result is an observation of one device, not a claim about others.
 */
internal object PyroWaveSgsrSelfTest {

    /** One picture at one frame size, drawn into one output size. */
    data class Case(
        val name: String,
        val planes: PyroWaveSgsrReference.Planes,
        val outputWidth: Int,
        val outputHeight: Int,
    )

    /**
     * How one draw compared, in 8 bit steps. A null error means the GPU work could not be made or run.
     */
    data class Result(val case: String, val sgsr: Boolean, val maxError: Int?, val edgePixels: Int)

    /**
     * Bounded on purpose: small frames, so the CPU model keeps up, at the scales and shapes the device
     * plan names, in 4:2:0 and 4:4:4.
     */
    fun cases(): List<Case> {
        val shapes = listOf(
            Triple("1.5x", 64 to 36, 96 to 54),
            Triple("2x", 48 to 28, 96 to 56),
            Triple("16:10", 64 to 40, 96 to 60),
            Triple("portrait", 36 to 64, 54 to 96),
        )
        val p = PyroWaveSgsrReference.Patterns
        return buildList {
            for (chroma444 in listOf(false, true)) {
                val sampling = if (chroma444) "444" else "420"
                for ((shape, frame, output) in shapes) {
                    val (w, h) = frame
                    val pictures = listOf(
                        "grey16" to p.neutral(w, h, chroma444, 16),
                        "grey128" to p.neutral(w, h, chroma444, 128),
                        "grey235" to p.neutral(w, h, chroma444, 235),
                        "bars75" to p.colourBars(w, h, chroma444, 0.75),
                        "bars100" to p.colourBars(w, h, chroma444, 1.0),
                        "ramp" to p.ramp(w, h, chroma444, 0.2, 0.3, 1, 1),
                        "step" to p.step(w, h, chroma444, w / 2 + 1, 0.2, 0.8),
                        "lines" to p.lines(w, h, chroma444, 5),
                        "borders" to p.borders(w, h, chroma444),
                        "orientation" to p.orientation(w, h, chroma444),
                        "row" to p.line(w, h, chroma444, h / 3, true),
                        "column" to p.line(w, h, chroma444, w / 3, false),
                        "chroma" to p.chromaStep(w, h, chroma444, w / 2, true, true),
                        "noise" to p.noise(w, h, chroma444, 304L),
                    )
                    for ((name, planes) in pictures) {
                        add(Case("$sampling/$shape/$name", planes, output.first, output.second))
                    }
                }
            }
        }
    }

    /** Every case, both pipelines. Empty when the native library is missing. */
    fun run(): List<Result> {
        if (!PyroWave.libraryLoaded) return emptyList()
        val matrix = PyroWaveSgsrReference.REC709
        return cases().flatMap { case ->
            listOf(false, true).map { sgsr ->
                val planes = case.planes
                val stats = PyroWaveSgsrReference.Stats()
                val expected = PyroWaveSgsrReference.render(planes, matrix, case.outputWidth, case.outputHeight, sgsr, stats = stats)
                val rgba = nativeReadback(
                    planes.width, planes.height, planes.chroma444, planes.luma, planes.cb, planes.cr,
                    case.outputWidth, case.outputHeight, sgsr,
                )
                val error = rgba?.let { PyroWaveSgsrReference.maxReadbackError(it, expected, case.outputWidth, case.outputHeight) }
                Result(case.name, sgsr, error, stats.edgePixels).also {
                    LimeLog.info("PyroWave SGSR 1 readback: ${it.case} ${if (sgsr) "sgsr1" else "present"} " +
                        "max error ${it.maxError ?: "unavailable"} (edge pixels ${it.edgePixels})")
                }
            }
        }
    }

    @JvmStatic
    private external fun nativeReadback(
        frameWidth: Int,
        frameHeight: Int,
        chroma444: Boolean,
        luma: ByteArray,
        cb: ByteArray,
        cr: ByteArray,
        outputWidth: Int,
        outputHeight: Int,
        sgsr: Boolean,
    ): ByteArray?
}
