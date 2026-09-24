#pragma once

#include <android/native_window.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void *pyrowave_renderer_create(ANativeWindow *window, uint32_t width, uint32_t height);

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
