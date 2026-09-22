#include "stream/deck_placebo_color_renderer.h"
extern "C" {
#include <libavutil/frame.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_drm.h>
}
#include <libdrm/drm_fourcc.h>
#include <cstdlib>
#include <iostream>
#include <limits>
using namespace nova::deck::stream;
namespace {
void require(bool ok, const char* why) { if (!ok) { std::cerr << why << '\n'; std::exit(1); } }
}
int main() {
    auto* frame = av_frame_alloc(); require(frame, "frame allocation");
    frame->format = AV_PIX_FMT_DRM_PRIME;
    frame->hw_frames_ctx = av_buffer_allocz(sizeof(AVHWFramesContext));
    frame->buf[0] = av_buffer_allocz(sizeof(AVDRMFrameDescriptor));
    require(frame->hw_frames_ctx && frame->buf[0], "fixture allocation");
    frame->data[0] = frame->buf[0]->data;
    auto& context = *reinterpret_cast<AVHWFramesContext*>(frame->hw_frames_ctx->data);
    auto& drm = *reinterpret_cast<AVDRMFrameDescriptor*>(frame->data[0]);
    unsigned checked = 0;
    for (bool ten : {false, true}) for (int width : {1, 127, 128, 8192}) {
        frame->width = width; frame->height = 73;
        context.sw_format = ten ? AV_PIX_FMT_P010LE : AV_PIX_FMT_NV12;
        drm = {}; drm.nb_objects = 2; drm.nb_layers = 2;
        for (int p = 0; p < 2; ++p) {
            auto& layer = drm.layers[p]; auto& object = drm.objects[p];
            const int rows = p ? 37 : 73;
            const int bytes = (ten ? 2 : 1) * (p ? ((width + 1) / 2) * 2 : width);
            layer.format = p ? (ten ? DRM_FORMAT_GR1616 : DRM_FORMAT_GR88) : (ten ? DRM_FORMAT_R16 : DRM_FORMAT_R8);
            layer.nb_planes = 1;
            layer.planes[0] = {p, 64, bytes + 32};
            // No fd is imported by this pure layout check.
            object.fd = 0; object.format_modifier = DRM_FORMAT_MOD_LINEAR;
            object.size = 64 + (rows - 1) * (bytes + 32) + bytes;
        }
        const auto valid = drm;
        require(deckDrmFrameLayoutSupported(*frame), "valid padded separate-plane layout refused");
        for (int p = 0; p < 2; ++p) {
            --drm.objects[p].size;
            require(!deckDrmFrameLayoutSupported(*frame), "one-byte short object accepted"); drm = valid;
            drm.layers[p].planes[0].object_index = 2;
            require(!deckDrmFrameLayoutSupported(*frame), "out-of-range object accepted"); drm = valid;
            drm.layers[p].planes[0].offset = -1;
            require(!deckDrmFrameLayoutSupported(*frame), "negative offset accepted"); drm = valid;
            drm.layers[p].planes[0].pitch = 0;
            require(!deckDrmFrameLayoutSupported(*frame), "zero stride accepted"); drm = valid;
            drm.layers[p].planes[0].pitch = 1;
            if (width > 1 || ten || p) require(!deckDrmFrameLayoutSupported(*frame), "short row accepted"); drm = valid;
            drm.objects[p].fd = -1;
            require(!deckDrmFrameLayoutSupported(*frame), "missing fd accepted"); drm = valid;
            drm.layers[p].nb_planes = 2;
            require(!deckDrmFrameLayoutSupported(*frame), "combined layer accepted"); drm = valid;
            drm.layers[p].format = DRM_FORMAT_XRGB8888;
            require(!deckDrmFrameLayoutSupported(*frame), "wrong component packing accepted"); drm = valid;
            drm.layers[p].planes[0].offset = std::numeric_limits<ptrdiff_t>::max();
            require(!deckDrmFrameLayoutSupported(*frame), "overflowing extent accepted"); drm = valid;
        }
        for (auto bad : {AV_PIX_FMT_YUV420P, AV_PIX_FMT_YUV420P10LE, AV_PIX_FMT_NV21, AV_PIX_FMT_P016LE, AV_PIX_FMT_YUV444P}) {
            context.sw_format = bad;
            require(!deckDrmFrameLayoutSupported(*frame), "depth-only or wrong chroma-order context accepted");
        }
        context.sw_format = ten ? AV_PIX_FMT_P010LE : AV_PIX_FMT_NV12;
        drm.nb_layers = 1;
        require(!deckDrmFrameLayoutSupported(*frame), "missing separate layers accepted"); drm = valid;
        drm.nb_objects = 5;
        require(!deckDrmFrameLayoutSupported(*frame), "oversized object table accepted"); drm = valid;
        // Both layers may share one allocation with independent offsets.
        drm.nb_objects = 1;
        drm.layers[1].planes[0].object_index = 0;
        drm.layers[1].planes[0].offset += drm.objects[0].size;
        drm.objects[0].size += drm.objects[1].size;
        require(deckDrmFrameLayoutSupported(*frame), "valid shared allocation refused");
        ++checked;
    }
    frame->height = 0; require(!deckDrmFrameLayoutSupported(*frame), "zero height accepted");
    frame->height = 73; frame->width = 8193; require(!deckDrmFrameLayoutSupported(*frame), "oversized frame accepted");
    frame->width = 128; av_buffer_unref(&frame->hw_frames_ctx);
    require(!deckDrmFrameLayoutSupported(*frame), "missing format context accepted");
    av_frame_free(&frame);
    std::cout << checked << " NV12/P010 odd/even/padded/shared allocation cases and malformed-layout boundaries passed; no hardware import attempted\n";
}
