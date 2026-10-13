#pragma once

#include <android/native_window.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// #304: SGSR 1, an experimental spatial upscaler for SDR streams. Kept in step with
// PyroWaveScalerPolicy in Kotlin.
//
// What Kotlin asks for. It asks only when it has already sized the surface's buffer to the output
// rather than to the stream, so anything but NONE means the buffer is larger than a frame was meant
// to be shown 1:1. FIT also holds the frame's aspect against the buffer's; STRETCH does not.
#define PYROWAVE_SGSR_REQUEST_NONE 0
#define PYROWAVE_SGSR_REQUEST_FIT 1
#define PYROWAVE_SGSR_REQUEST_STRETCH 2

// What the renderer does with the buffer it was given.
//   DEFAULT: nothing was asked. The present shader draws the frame into the buffer Kotlin sized as
//     before, which for a stream is the stream's own size, and the compositor scales it.
//   SGSR1_ACTIVE: SGSR 1 draws the frame into the larger buffer.
//   OUTPUT_BUFFER_BILINEAR: SGSR 1 was asked for and refused here, so the present shader draws the
//     frame into the larger buffer with its LINEAR sampler. Not the default: the upscale moved from
//     the compositor into the shader. The reason says why.
#define PYROWAVE_SCALER_DEFAULT 0
#define PYROWAVE_SCALER_SGSR1_ACTIVE 1
#define PYROWAVE_SCALER_OUTPUT_BUFFER_BILINEAR 2

#define PYROWAVE_SCALER_REASON_NONE 0
#define PYROWAVE_SCALER_REASON_NOT_REQUESTED 1
#define PYROWAVE_SCALER_REASON_HDR_OR_10BIT 2
#define PYROWAVE_SCALER_REASON_NOT_UPSCALING 3
#define PYROWAVE_SCALER_REASON_ASPECT_MISMATCH 4
#define PYROWAVE_SCALER_REASON_PIPELINE_FAILED 5

typedef struct {
    int32_t state;
    int32_t reason;
    uint32_t frame_width;
    uint32_t frame_height;
    uint32_t buffer_width;
    uint32_t buffer_height;
} pyrowave_scaler_info;

void *pyrowave_renderer_create(ANativeWindow *window, uint32_t width, uint32_t height,
                               bool chroma_444, bool hdr, int32_t sgsr_request);

// What the renderer chose for the buffer at creation. False only for a null handle.
bool pyrowave_renderer_scaler_state(void *handle, pyrowave_scaler_info *info);

// Debug self test, never called by a stream: draw three planes from host memory into an offscreen
// image of the output size, with SGSR 1 or with the present shader, and read the pixels back as RGBA.
// No surface, no swapchain and no decoder. Planes are tightly packed; chroma is per four pixels
// unless chroma_444. False when anything could not be made or run.
bool pyrowave_renderer_sgsr_readback(uint32_t frame_width, uint32_t frame_height, bool chroma_444,
                                     const uint8_t *luma, const uint8_t *cb, const uint8_t *cr,
                                     uint32_t output_width, uint32_t output_height, bool sgsr,
                                     uint8_t *rgba);

// One encoded frame, decoded on the GPU and shown. What a stream uses.
bool pyrowave_renderer_decode_and_present(void *handle, const uint8_t *bitstream, size_t size);

// How many frames so far were drawn with blocks missing, because packets did not arrive.
uint64_t pyrowave_renderer_partial_frames(void *handle);

// Three planes from host memory, shown. The bring-up path, kept because it can be fed a known
// picture and checked pixel by pixel.
bool pyrowave_renderer_present(void *handle, const uint8_t *luma, const uint8_t *cb, const uint8_t *cr);

void pyrowave_renderer_destroy(void *handle);

#ifdef __cplusplus
}
#endif
