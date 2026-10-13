#include "pyrowave_readback_guard.h"
#include <assert.h>
#include <stdio.h>

#define SENTINEL ((size_t)0x5a5a5a5a)

/* Refused, and the output size left alone. */
static void refused(int32_t w, int32_t h, bool chroma_444, int32_t ow, int32_t oh,
                    int64_t luma, int64_t cb, int64_t cr) {
    size_t bytes = SENTINEL;
    assert(!pyrowave_readback_request_ok(w, h, chroma_444, ow, oh, luma, cb, cr, &bytes));
    assert(bytes == SENTINEL);
}

/* Taken, with the RGBA size the output asks for. */
static void taken(int32_t w, int32_t h, bool chroma_444, int32_t ow, int32_t oh,
                  int64_t luma, int64_t chroma) {
    size_t bytes = SENTINEL;
    assert(pyrowave_readback_request_ok(w, h, chroma_444, ow, oh, luma, chroma, chroma, &bytes));
    assert(bytes == (size_t)ow * (size_t)oh * 4);
}

int main(void) {
    const int32_t sides[] = {0, -1, -8192, INT32_MIN};
    const int32_t too_long[] = {8193, 65536, INT32_MAX};

    /* Small frames of both samplings, odd sides rounding chroma down as create_planes does. */
    taken(4, 3, true, 7, 5, 12, 12);
    taken(34, 30, false, 68, 60, 1020, 255);
    taken(35, 31, false, 70, 62, 1085, 255);
    taken(1, 1, true, 1, 1, 1, 1);
    taken(2, 2, false, 3, 3, 4, 1);

    /* A 4:2:0 frame one pixel across or down has empty chroma planes. */
    refused(1, 1, false, 2, 2, 1, 0, 0);
    refused(1, 30, false, 2, 60, 30, 0, 0);
    refused(34, 1, false, 68, 2, 34, 0, 0);

    /* Zero and negative sides, each in turn. */
    for (size_t i = 0; i < sizeof(sides) / sizeof(sides[0]); ++i) {
        refused(sides[i], 30, false, 68, 60, 1020, 255, 255);
        refused(34, sides[i], false, 68, 60, 1020, 255, 255);
        refused(34, 30, false, sides[i], 60, 1020, 255, 255);
        refused(34, 30, false, 68, sides[i], 1020, 255, 255);
    }

    /* Sides past the limit, with the lengths their products would claim. 65536 * 65536 overflows a
     * 32-bit product, which is what the guard has to refuse before multiplying. */
    for (size_t i = 0; i < sizeof(too_long) / sizeof(too_long[0]); ++i) {
        const int64_t n = too_long[i];
        refused(too_long[i], too_long[i], true, 64, 64, n * n, n * n, n * n);
        refused(too_long[i], 30, true, 64, 64, n * 30, n * 30, n * 30);
        refused(34, too_long[i], true, 64, 64, 34 * n, 34 * n, 34 * n);
        refused(34, 30, true, too_long[i], 64, 1020, 1020, 1020);
        refused(34, 30, true, 64, too_long[i], 1020, 1020, 1020);
    }
    refused(65536, 65536, true, 64, 64, 0, 0, 0);

    /* The limit itself, where every length and the output still fit a Java array. */
    taken(8192, 8192, true, 8192, 8192, 67108864, 67108864);
    taken(8192, 8192, false, 8192, 8192, 67108864, 16777216);
    {
        size_t bytes = 0;
        assert(pyrowave_readback_request_ok(8192, 8192, true, 8192, 8192, 67108864, 67108864, 67108864, &bytes));
        assert(bytes <= (size_t)INT32_MAX);
    }

    /* Planes that do not match the frame. */
    refused(34, 30, false, 68, 60, 1019, 255, 255);
    refused(34, 30, false, 68, 60, 1021, 255, 255);
    refused(34, 30, false, 68, 60, 1020, 254, 255);
    refused(34, 30, false, 68, 60, 1020, 255, 256);
    refused(34, 30, false, 68, 60, 1020, 1020, 1020); /* 4:4:4 planes for a 4:2:0 frame */
    refused(34, 30, true, 68, 60, 1020, 255, 255);    /* 4:2:0 planes for a 4:4:4 frame */
    refused(34, 30, false, 68, 60, -1020, 255, 255);
    refused(34, 30, false, 68, 60, 1020, -255, -255);
    refused(34, 30, false, 68, 60, (int64_t)1020 + ((int64_t)1 << 32), 255, 255);

    /* Nowhere to put the size. */
    assert(!pyrowave_readback_request_ok(4, 3, true, 7, 5, 12, 12, 12, NULL));

    /* Every small frame of both samplings against lengths worked out separately. */
    for (int32_t w = 1; w <= 9; ++w) {
        for (int32_t h = 1; h <= 9; ++h) {
            for (int s = 0; s < 2; ++s) {
                const bool chroma_444 = s == 1;
                const int64_t cw = chroma_444 ? w : w / 2;
                const int64_t ch = chroma_444 ? h : h / 2;
                if (cw == 0 || ch == 0) {
                    refused(w, h, chroma_444, w, h, (int64_t)w * h, cw * ch, cw * ch);
                } else {
                    taken(w, h, chroma_444, 2 * w, 2 * h, (int64_t)w * h, cw * ch);
                    refused(w, h, chroma_444, 2 * w, 2 * h, (int64_t)w * h + 1, cw * ch, cw * ch);
                    refused(w, h, chroma_444, 2 * w, 2 * h, (int64_t)w * h, cw * ch + 1, cw * ch);
                    refused(w, h, chroma_444, 2 * w, 2 * h, (int64_t)w * h, cw * ch, cw * ch - 1);
                }
            }
        }
    }

    puts("all readback request cases passed");
    return 0;
}
