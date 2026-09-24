#pragma once

// The C++ device, reachable from the C entry points.
#include <pyrowave/pyrowave.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Make a Vulkan device Nova owns and hand back the codec handle borrowing it.
 *
 * @param want_presentation Ask for the surface and swapchain extensions too. A decode that only
 *        reads pixels back does not need them, and asking for them on a device that cannot present
 *        would fail for the wrong reason.
 * @return an opaque handle to release with pyrowave_device_release, or NULL.
 */
void *pyrowave_device_acquire(bool want_presentation, pyrowave_device *out_codec_device);

void pyrowave_device_release(void *handle);

#ifdef __cplusplus
}
#endif
