#include "stream/deck_video_capabilities.h"
#include <Limelight.h>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_vaapi.h>
}
#include <algorithm>
#include <vector>
#ifdef NOVA_DECK_BUILD_PYROWAVE
#include "codec.h"
#endif

namespace nova::deck::stream {
int selectSdrVideoFormat(std::string_view preference, bool hostH264, bool hostHevc,
    const DeckVideoDecodeSupport& decoder, int width, int height, bool hostPyrowave) {
#ifdef NOVA_DECK_BUILD_PYROWAVE
    if (preference == "pyrowave" && hostPyrowave && decoder.pyrowave.supports(width, height)) return VIDEO_FORMAT_PYROWAVE;
#else
    (void)hostPyrowave;
#endif
    if ((preference == "auto" || preference == "hevc") && hostHevc && decoder.hevc.supports(width, height)) return VIDEO_FORMAT_H265;
    if ((preference == "auto" || preference == "h264") && hostH264 && decoder.h264.supports(width, height)) return VIDEO_FORMAT_H264;
    return 0;
}
namespace {
bool hasHardwareDecoder(AVCodecID id) {
    const auto* codec = avcodec_find_decoder(id);
    if (!codec) return false;
    for (int i = 0; const auto* config = avcodec_get_hw_config(codec, i); ++i)
        if (config->device_type == AV_HWDEVICE_TYPE_VAAPI && config->pix_fmt == AV_PIX_FMT_VAAPI &&
            (config->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX)) return true;
    return false;
}
DeckDecodeLimits profileLimits(VADisplay display, VAProfile profile, unsigned int format) {
    const int maximum = vaMaxNumEntrypoints(display);
    if (maximum <= 0 || maximum > 256) return {};
    std::vector<VAEntrypoint> entries(maximum);
    int count = 0;
    if (vaQueryConfigEntrypoints(display, profile, entries.data(), &count) != VA_STATUS_SUCCESS ||
        count < 0 || count > maximum ||
        std::find(entries.begin(), entries.begin() + count, VAEntrypointVLD) == entries.begin() + count) return {};
    VAConfigAttrib attribute{VAConfigAttribRTFormat, 0};
    if (vaGetConfigAttributes(display, profile, VAEntrypointVLD, &attribute, 1) != VA_STATUS_SUCCESS ||
        attribute.value == VA_ATTRIB_NOT_SUPPORTED || !(attribute.value & format)) return {};
    attribute.value = format;
    VAConfigID config = VA_INVALID_ID;
    if (vaCreateConfig(display, profile, VAEntrypointVLD, &attribute, 1, &config) != VA_STATUS_SUCCESS) return {};
    unsigned int size = 0;
    DeckDecodeLimits result;
    // Some drivers (including NVIDIA's VA-API bridge) publish valid maximum
    // picture dimensions here but leave surface-attribute GETTABLE flags unset.
    // Use the reported config limits, never a guessed GPU/vendor default. Keep
    // the stricter value when both config and surface limits are available.
    VAConfigAttrib dimensions[] = {
        {VAConfigAttribMaxPictureWidth, VA_ATTRIB_NOT_SUPPORTED},
        {VAConfigAttribMaxPictureHeight, VA_ATTRIB_NOT_SUPPORTED},
    };
    const auto constrain = [](int& limit, unsigned int value) {
        if (value > 0 && value <= 65536)
            limit = limit > 0 ? std::min(limit, static_cast<int>(value)) : static_cast<int>(value);
    };
    if (vaGetConfigAttributes(display, profile, VAEntrypointVLD, dimensions, 2) == VA_STATUS_SUCCESS) {
        constrain(result.maxWidth, dimensions[0].value);
        constrain(result.maxHeight, dimensions[1].value);
    }
    if (vaQuerySurfaceAttributes(display, config, nullptr, &size) == VA_STATUS_SUCCESS && size > 0 && size <= 256) {
        std::vector<VASurfaceAttrib> attributes(size);
        if (vaQuerySurfaceAttributes(display, config, attributes.data(), &size) == VA_STATUS_SUCCESS && size <= attributes.size()) {
            for (unsigned int i = 0; i < size; ++i) {
                const auto& value = attributes[i];
                if (!(value.flags & VA_SURFACE_ATTRIB_GETTABLE) || value.value.type != VAGenericValueTypeInteger ||
                    value.value.value.i <= 0 || value.value.value.i > 65536) continue;
                if (value.type == VASurfaceAttribMaxWidth) constrain(result.maxWidth, value.value.value.i);
                if (value.type == VASurfaceAttribMaxHeight) constrain(result.maxHeight, value.value.value.i);
            }
        }
    }
    vaDestroyConfig(display, config);
    return result;
}
}

bool DeckVideoDecodeSupport::supports(int format, int width, int height) const {
#ifdef NOVA_DECK_BUILD_PYROWAVE
    if (format == VIDEO_FORMAT_PYROWAVE) return pyrowave.supports(width, height);
#endif
    if (format == VIDEO_FORMAT_H264) return h264.supports(width, height);
    if (format == VIDEO_FORMAT_H265) return hevc.supports(width, height);
    if (format == VIDEO_FORMAT_H265_MAIN10) return main10.supports(width, height);
    return false;
}

namespace {
DeckVideoDecodeSupport probeVaapiDecodeSupport(AVBufferRef* device) {
    if (!device || !device->data) return {};
    const auto* context = reinterpret_cast<const AVHWDeviceContext*>(device->data);
    if (context->type != AV_HWDEVICE_TYPE_VAAPI || !context->hwctx) return {};
    const auto display = static_cast<const AVVAAPIDeviceContext*>(context->hwctx)->display;
    if (!display) return {};
    DeckVideoDecodeSupport result;
    if (hasHardwareDecoder(AV_CODEC_ID_H264)) result.h264 = profileLimits(display, VAProfileH264High, VA_RT_FORMAT_YUV420);
    if (hasHardwareDecoder(AV_CODEC_ID_HEVC)) {
        result.hevc = profileLimits(display, VAProfileHEVCMain, VA_RT_FORMAT_YUV420);
        result.main10 = profileLimits(display, VAProfileHEVCMain10, VA_RT_FORMAT_YUV420_10);
    }
    return result;
}
}

DeckVideoDecodeSupport probeVideoDecodeSupport(AVBufferRef* device) {
    auto support = probeVaapiDecodeSupport(device);
    // Startup review and stream launch must see the same codec capabilities.
    // PyroWave uses its own Vulkan device, even when VAAPI is unavailable.
#ifdef NOVA_DECK_BUILD_PYROWAVE
    nova::pyrowave::Codec decoder;
    if (decoder.open(128, 128, false)) {
        const int limit = decoder.probeGpuLimit();
        support.pyrowave = {limit, limit};
    }
#endif
    return support;
}

DeckVideoDecodeSupport detectVideoDecodeSupport() {
    AVBufferRef* device = nullptr;
    av_hwdevice_ctx_create(&device, AV_HWDEVICE_TYPE_VAAPI, nullptr, nullptr, 0);
    auto support = probeVideoDecodeSupport(device);
    av_buffer_unref(&device);
    return support;
}
} // namespace nova::deck::stream
