/**
 * @file tools/pyrowave_rate_fixture.c
 * @brief Prints upstream's own bitrate estimates over a grid, for the Kotlin port to be checked against.
 *
 * PyroWave's author publishes an objective model of the bitrate the codec needs as a generated C header,
 * eval-results/pyrowave_regression_results.h in Themaister/pyrowave. Nova carries a Kotlin port of it
 * (PyroWaveRateModel), and a port is only worth having if it gives the same answers. So this program
 * calls the upstream function itself, compiled from the unmodified header, and the Kotlin test compares
 * every row it prints with what the port computes. The two share nothing but the header they were made
 * from: the numbers here come from a C compiler reading it, and the port's table comes from a script
 * reading the same text.
 *
 * Regenerate the fixture with tools/pyrowave_rate_model.py, which checks the header's sha256 against
 * the one the Kotlin table was generated from, compiles this, and adds the provenance lines. By hand:
 *
 *   cc -std=c11 -O2 -ffp-contract=off -Wall -Wextra -Werror \
 *      -isystem <pyrowave>/eval-results tools/pyrowave_rate_fixture.c -o fixture -lm && ./fixture
 *
 * The header comes in with -isystem because it trips -Wsign-compare, which is upstream's to fix and not
 * a reason to relax the warnings on this file. -ffp-contract=off keeps the compiler from fusing a
 * multiply and an add into one rounding, which the JVM never does, so the two evaluate the same
 * polynomial the same way. NDEBUG is left undefined on purpose: every input printed here must satisfy
 * the header's own asserts, and a grid that strayed out of the model would abort rather than print a
 * number upstream never meant to give.
 *
 * 1280x800 (16:10) and 2560x1080 (64:27) are in the grid although the model was only sampled at 16:9.
 * Their pixel counts are inside its range, so upstream answers for them, and the port must give that
 * same answer while flagging it as extrapolated.
 */
#include "pyrowave_regression_results.h"

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>

static const int psnrs[] = {30, 31, 33, 35, 37, 40, 45, 50};
static const int sizes[][2] = {
    {1280, 720}, {1600, 900}, {1920, 1080}, {2560, 1440}, {3200, 1800}, {3840, 2160},
    {1280, 800}, {2560, 1080},
};
static const int frame_rates[] = {30, 60, 90, 120};

#define COUNT(array) (sizeof(array) / sizeof((array)[0]))

static bool emit(int psnr, int factor, int chroma444, int width, int height, int fps) {
    const double mbits = pyrowave_psnr_hvs_m_h_estimate_mbits(
        psnr, width, height, (enum pyrowave_height_factor)factor, chroma444, (double)fps);
    // The upstream function answers 0.0 for a bucket it has no row for. Every bucket asked for here
    // exists, so a zero is a header that changed shape, and printing it would make the port look wrong
    // instead.
    if (!(mbits > 0.0)) {
        fprintf(stderr, "no estimate for psnr %d factor %d chroma444 %d %dx%d\n", psnr, factor, chroma444,
                width, height);
        return false;
    }
    // %.17g round trips a double exactly, so the fixture carries every bit the C evaluation produced.
    printf("%d,%d,%d,%d,%d,%d,%.17g\n", psnr, factor, chroma444, width, height, fps, mbits);
    return true;
}

static bool in_grid(int psnr) {
    for (size_t p = 0; p < COUNT(psnrs); p++) {
        if (psnrs[p] == psnr) {
            return true;
        }
    }
    return false;
}

int main(void) {
    printf("psnr,height_factor,chroma444,width,height,fps,mbits\n");
    for (size_t p = 0; p < COUNT(psnrs); p++) {
        for (int factor = PYROWAVE_HEIGHT_FACTOR_1_00; factor <= PYROWAVE_HEIGHT_FACTOR_2_87; factor++) {
            for (int chroma444 = 0; chroma444 <= 1; chroma444++) {
                for (size_t s = 0; s < COUNT(sizes); s++) {
                    for (size_t f = 0; f < COUNT(frame_rates); f++) {
                        if (!emit(psnrs[p], factor, chroma444, sizes[s][0], sizes[s][1], frame_rates[f])) {
                            return EXIT_FAILURE;
                        }
                    }
                }
            }
        }
    }

    // The grid reaches eight of the twenty one quality levels. The rest of the table is checked too,
    // at the smallest and the largest picture the model covers, so that no bucket the port carries goes
    // unchecked: a row filed under the wrong quality would otherwise pass.
    for (int psnr = PYROWAVE_REGRESSION_MIN_PSNR_HVS_M_H; psnr <= PYROWAVE_REGRESSION_MAX_PSNR_HVS_M_H; psnr++) {
        if (in_grid(psnr)) {
            continue;
        }
        for (int factor = PYROWAVE_HEIGHT_FACTOR_1_00; factor <= PYROWAVE_HEIGHT_FACTOR_2_87; factor++) {
            for (int chroma444 = 0; chroma444 <= 1; chroma444++) {
                if (!emit(psnr, factor, chroma444, 1280, 720, 60) || !emit(psnr, factor, chroma444, 3840, 2160, 60)) {
                    return EXIT_FAILURE;
                }
            }
        }
    }
    return EXIT_SUCCESS;
}
