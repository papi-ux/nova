#pragma once

#include <android/native_window.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void *pyrowave_renderer_create(ANativeWindow *window, uint32_t width, uint32_t height);
bool pyrowave_renderer_present(void *handle, const uint8_t *luma, const uint8_t *cb, const uint8_t *cr);
void pyrowave_renderer_destroy(void *handle);

#ifdef __cplusplus
}
#endif
