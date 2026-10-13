#ifndef NOVA_PYROWAVE_READBACK_GUARD_H
#define NOVA_PYROWAVE_READBACK_GUARD_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/*
 * The longest side the debug SGSR 1 readback takes, for the frame and for the output alike. At this
 * size the largest plane is 8192 * 8192 bytes and the largest output 8192 * 8192 * 4 bytes, and both
 * still fit a Java array length.
 */
#define PYROWAVE_READBACK_MAX_SIDE 8192

/*
 * Whether a debug readback request is within this entry point's contract, decided before any memory
 * or GPU work. Every side is positive and no longer than PYROWAVE_READBACK_MAX_SIDE, which is this
 * entry point's own cap and not the device's image limit (Vulkan guarantees 4096; the self test draws
 * far smaller pictures). A 4:2:0 frame needs two pixels each way, or its chroma planes would be
 * empty. Each plane is exactly as long as create_planes makes it: the frame for luma, and
 * frame_width >> shift by frame_height >> shift for each chroma plane. Products are taken in 64 bits,
 * so no size overflows before it is compared.
 *
 * On success *output_bytes is the size of the RGBA pixels; on refusal it is left as it was.
 */
static inline bool pyrowave_readback_request_ok(int32_t frame_width, int32_t frame_height, bool chroma_444,
                                                int32_t output_width, int32_t output_height,
                                                int64_t luma_length, int64_t cb_length, int64_t cr_length,
                                                size_t *output_bytes) {
    if (output_bytes == NULL) return false;
    if (frame_width <= 0 || frame_height <= 0 || output_width <= 0 || output_height <= 0) return false;
    if (frame_width > PYROWAVE_READBACK_MAX_SIDE || frame_height > PYROWAVE_READBACK_MAX_SIDE ||
        output_width > PYROWAVE_READBACK_MAX_SIDE || output_height > PYROWAVE_READBACK_MAX_SIDE) {
        return false;
    }
    const int shift = chroma_444 ? 0 : 1;
    const int64_t chroma_width = frame_width >> shift;
    const int64_t chroma_height = frame_height >> shift;
    if (chroma_width == 0 || chroma_height == 0) return false;
    const int64_t chroma_length = chroma_width * chroma_height;
    if (luma_length != (int64_t)frame_width * frame_height || cb_length != chroma_length ||
        cr_length != chroma_length) {
        return false;
    }
    *output_bytes = (size_t)output_width * (size_t)output_height * 4;
    return true;
}

#endif
