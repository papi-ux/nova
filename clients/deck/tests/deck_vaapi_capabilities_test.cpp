#include "stream/deck_video_capabilities.h"
extern "C" {
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_vaapi.h>
}
#include <cstdlib>
#include <iostream>

// Exercise the production probe against retained driver response shapes. These
// VA entrypoints replace libva only in this executable; FFmpeg's codec support
// detection and AVHWDeviceContext are real. No GPU is needed for the regression.
namespace {
struct Driver {
    unsigned width = 4096, height = 4096;
    int surfaceWidth = 4096, surfaceHeight = 4096;
    unsigned flags = 0; // NVIDIA VA-API bridge's observed response.
    bool entrypoint = true, format = true, config = true, dimensions = true;
    int created = 0, destroyed = 0;
} driver;
void check(bool ok, const char* reason) {
    if (!ok) { std::cerr << reason << '\n'; std::exit(1); }
}
}
extern "C" {
int vaMaxNumEntrypoints(VADisplay) { return 1; }
VAStatus vaQueryConfigEntrypoints(VADisplay, VAProfile, VAEntrypoint* entries, int* count) {
    *count = 1; entries[0] = driver.entrypoint ? VAEntrypointVLD : VAEntrypointEncSlice;
    return VA_STATUS_SUCCESS;
}
VAStatus vaGetConfigAttributes(VADisplay, VAProfile, VAEntrypoint, VAConfigAttrib* attrs, int count) {
    for (int i = 0; i < count; ++i) {
        if (attrs[i].type == VAConfigAttribRTFormat)
            attrs[i].value = driver.format ? VA_RT_FORMAT_YUV420 | VA_RT_FORMAT_YUV420_10 : VA_ATTRIB_NOT_SUPPORTED;
        else {
            if (!driver.dimensions) return VA_STATUS_ERROR_OPERATION_FAILED;
            attrs[i].value = attrs[i].type == VAConfigAttribMaxPictureWidth ? driver.width : driver.height;
        }
    }
    return VA_STATUS_SUCCESS;
}
VAStatus vaCreateConfig(VADisplay, VAProfile, VAEntrypoint, VAConfigAttrib*, int, VAConfigID* config) {
    if (!driver.config) return VA_STATUS_ERROR_UNSUPPORTED_PROFILE;
    *config = ++driver.created; return VA_STATUS_SUCCESS;
}
VAStatus vaQuerySurfaceAttributes(VADisplay, VAConfigID, VASurfaceAttrib* attrs, unsigned* count) {
    if (attrs) {
        check(*count >= 2, "surface query allocation too small");
        attrs[0] = {VASurfaceAttribMaxWidth, driver.flags, {VAGenericValueTypeInteger, {driver.surfaceWidth}}};
        attrs[1] = {VASurfaceAttribMaxHeight, driver.flags, {VAGenericValueTypeInteger, {driver.surfaceHeight}}};
    }
    *count = 2; return VA_STATUS_SUCCESS;
}
VAStatus vaDestroyConfig(VADisplay, VAConfigID) { ++driver.destroyed; return VA_STATUS_SUCCESS; }
}
int main() {
    using namespace nova::deck::stream;
    auto* device = av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_VAAPI);
    check(device != nullptr, "FFmpeg VAAPI device allocation failed");
    auto* hw = reinterpret_cast<AVHWDeviceContext*>(device->data);
    auto* va = static_cast<AVVAAPIDeviceContext*>(hw->hwctx);
    va->display = &driver; // Uninitialized FFmpeg device; only our query stubs use this.
    const auto probe = [&] {
        const auto result = probeVideoDecodeSupport(device);
        check(driver.created == driver.destroyed, "capability probe leaked a config");
        return result;
    };
    auto result = probe();
    check(result.h264.supports(1280, 800) && result.hevc.supports(3840, 2160) &&
          result.main10.supports(3840, 2160), "valid config limits rejected with unflagged surfaces");
    check(!result.h264.supports(4097, 800), "config maximum was exceeded");

    driver = {}; driver.width = driver.height = VA_ATTRIB_NOT_SUPPORTED;
    driver.flags = VA_SURFACE_ATTRIB_GETTABLE;
    result = probe();
    check(result.h264.supports(4096, 4096), "surface-only driver lost support");
    driver.dimensions = false;
    check(probe().h264.supports(4096, 4096), "optional config query error masked valid surfaces");

    driver = {}; driver.flags = VA_SURFACE_ATTRIB_GETTABLE;
    driver.surfaceWidth = 1920; driver.surfaceHeight = 2160; driver.height = 1080;
    result = probe();
    check(result.h264.supports(1920, 1080) && !result.h264.supports(1921, 1080) &&
          !result.h264.supports(1920, 1081), "conflicting limits did not use the stricter dimensions");

    for (unsigned invalid : {0u, 65537u, VA_ATTRIB_NOT_SUPPORTED}) {
        driver = {}; driver.width = invalid;
        check(!probe().h264.supports(1280, 800), "missing/invalid width was guessed from unflagged surfaces");
        driver = {}; driver.height = invalid;
        check(!probe().h264.supports(1280, 800), "missing/invalid height was guessed from unflagged surfaces");
    }
    driver = {}; driver.dimensions = false;
    check(!probe().h264.supports(1280, 800), "failed config query trusted unflagged surface limits");
    driver = {}; driver.entrypoint = false;
    check(!probe().h264.supports(1280, 800), "non-decode entrypoint admitted");
    driver = {}; driver.format = false;
    check(!probe().hevc.supports(1280, 800), "missing render format admitted");
    driver = {}; driver.config = false;
    check(!probe().h264.supports(1280, 800), "failed decoder config admitted");
    va->display = nullptr;
    av_buffer_unref(&device);
    std::cout << "VAAPI config/surface capability regressions passed\n";
}
