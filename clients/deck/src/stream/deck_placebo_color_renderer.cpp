#include "stream/deck_placebo_color_renderer.h"
#include "stream/deck_video_color.h"
#define PL_LIBAV_IMPLEMENTATION 0
#include <libplacebo/utils/libav.h>
#include <algorithm>
#include <memory>
#include <libplacebo/swapchain.h>
extern "C" {
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_drm.h>
}
#include <libdrm/drm_fourcc.h>

namespace nova::deck::stream {
namespace {
struct FreeFrame { void operator()(AVFrame* frame) const { av_frame_free(&frame); } };
}

bool deckDrmFrameLayoutSupported(const AVFrame& frame) {
    if (frame.format != AV_PIX_FMT_DRM_PRIME || frame.width <= 0 || frame.height <= 0
            || frame.width > 8192 || frame.height > 8192 || !frame.hw_frames_ctx
            || !frame.hw_frames_ctx->data || frame.hw_frames_ctx->size < sizeof(AVHWFramesContext)) return false;
    const auto format = reinterpret_cast<const AVHWFramesContext*>(frame.hw_frames_ctx->data)->sw_format;
    // Bit depth alone does not identify the plane count, chroma order or bit
    // packing. A planar 4:2:0 context with two DRM layers would assert downstream.
    if (format != AV_PIX_FMT_NV12 && format != AV_PIX_FMT_P010LE) return false;
    const int depth = format == AV_PIX_FMT_P010LE ? 10 : 8;
    // FFmpeg's buffer may own a mapping context rather than the descriptor
    // itself, so its size is not the descriptor's size.
    if (!frame.data[0] || !frame.buf[0]) return false;
    const auto& drm = *reinterpret_cast<const AVDRMFrameDescriptor*>(frame.data[0]);
    // libplacebo's helper asserts on combined/missing layers. Reject these
    // valid-but-unsupported exports before entering that helper.
    if (drm.nb_objects < 1 || drm.nb_objects > 4 || drm.nb_layers != 2) return false;
    const uint32_t formats[]{depth == 10 ? DRM_FORMAT_R16 : DRM_FORMAT_R8,
                            depth == 10 ? DRM_FORMAT_GR1616 : DRM_FORMAT_GR88};
    for (int i = 0; i < 2; ++i) {
        const auto& layer = drm.layers[i];
        if (layer.nb_planes != 1 || layer.format != formats[i]) return false;
        const auto& plane = layer.planes[0];
        if (plane.object_index < 0 || plane.object_index >= drm.nb_objects || plane.offset < 0 || plane.pitch <= 0) return false;
        const auto& object = drm.objects[plane.object_index];
        const size_t rows = i == 0 ? frame.height : (frame.height + 1) / 2;
        const size_t rowBytes = (depth == 10 ? 2 : 1) * (i == 0 ? frame.width : ((frame.width + 1) / 2) * 2);
        if (object.fd < 0 || static_cast<size_t>(plane.pitch) < rowBytes || static_cast<size_t>(plane.offset) >= object.size) return false;
        const auto remaining = object.size - static_cast<size_t>(plane.offset);
        if (rowBytes > remaining || rows - 1 > (remaining - rowBytes) / static_cast<size_t>(plane.pitch)) return false;
    }
    return true;
}

std::optional<DeckColorOutput> deckSurfaceColorOutput(const pl_swapchain_frame& frame) {
    if (!frame.fbo || !frame.fbo->params.format || !frame.fbo->params.renderable
            || frame.fbo->params.w <= 0 || frame.fbo->params.h <= 0 || frame.fbo->params.d
            || frame.fbo->params.format->num_components != 4
            || frame.color_repr.sys != PL_COLOR_SYSTEM_RGB
            || frame.color_repr.levels != PL_COLOR_LEVELS_FULL) return std::nullopt;
    const auto& color = frame.color_space;
    if (color.primaries == PL_COLOR_PRIM_BT_709 && color.transfer == PL_COLOR_TRC_SRGB)
        return DeckColorOutput::Srgb;
    if (color.primaries != PL_COLOR_PRIM_BT_2020 || color.transfer != PL_COLOR_TRC_PQ)
        return std::nullopt;
    for (int i = 0; i < 3; ++i)
        if (frame.fbo->params.format->component_depth[i] < 10) return std::nullopt;
    if (frame.color_repr.bits.color_depth && frame.color_repr.bits.color_depth < 10) return std::nullopt;
    return DeckColorOutput::Hdr10Pq;
}

DeckPlaceboColorRenderer::DeckPlaceboColorRenderer(pl_gpu gpu) : gpu_(gpu) {
    if (gpu_) renderer_ = pl_renderer_create(nullptr, gpu_);
}

DeckPlaceboColorRenderer::~DeckPlaceboColorRenderer() {
    if (gpu_) {
        pl_gpu_finish(gpu_);
        for (auto& slot : pendingFrames_) {
            if (slot.mapped) pl_unmap_avframe(gpu_, &slot.frame);
            for (auto& texture : slot.uploads) pl_tex_destroy(gpu_, &texture);
        }
    }
    pl_renderer_destroy(&renderer_);
}

size_t DeckPlaceboColorRenderer::pendingFrames() const {
    return std::count_if(pendingFrames_.begin(), pendingFrames_.end(), [](const auto& slot) { return slot.mapped; });
}

bool DeckPlaceboColorRenderer::canAcceptFrame() {
    retireFrames();
    return pendingFrames() < pendingFrames_.size();
}

void DeckPlaceboColorRenderer::retireFrames() {
    for (auto& slot : pendingFrames_) {
        if (!slot.mapped) continue;
        bool busy = false;
        for (int i = 0; i < slot.frame.num_planes; ++i)
            if (slot.frame.planes[i].texture && pl_tex_poll(gpu_, slot.frame.planes[i].texture, 0)) busy = true;
        if (busy) continue;
        pl_unmap_avframe(gpu_, &slot.frame);
        slot.mapped = false;
    }
}

bool DeckPlaceboColorRenderer::render(const AVFrame& frame, pl_tex target, DeckColorOutput output) {
    pl_frame destination{};
    destination.num_planes = 1;
    destination.planes[0].texture = target;
    destination.planes[0].components = 4;
    for (int i = 0; i < 4; ++i) destination.planes[0].component_mapping[i] = i;
    destination.repr = pl_color_repr_rgb;
    destination.color = output == DeckColorOutput::Hdr10Pq ? pl_color_space_hdr10 : pl_color_space_srgb;
    if (output == DeckColorOutput::Hdr10Pq) {
        // This API's HDR output is an offscreen reference, never a monitor probe.
        destination.color.hdr.min_luma = 0.005f;
        destination.color.hdr.max_luma = 1000.0f;
        destination.color.hdr.prim = *pl_raw_primaries_get(PL_COLOR_PRIM_BT_2020);
    }
    return renderFrame(frame, destination, output, {});
}

bool DeckPlaceboColorRenderer::renderToSurface(const AVFrame& frame, const pl_swapchain_frame& surface, const pl_overlay* overlay, DeckVideoScaleMode scale) {
    const auto output = deckSurfaceColorOutput(surface);
    if (!output) {
        error_ = "Unsupported acquired surface color encoding or precision";
        inputColor_ = {}; inputRepresentation_ = {};
        return false;
    }
    pl_frame destination{};
    pl_frame_from_swapchain(&destination, &surface);
    destination.overlays = overlay; destination.num_overlays = overlay ? 1 : 0;
    return renderFrame(frame, destination, *output, scale);
}

bool DeckPlaceboColorRenderer::renderOverlayToSurface(const pl_swapchain_frame& surface, const pl_overlay& overlay) {
    error_.clear(); inputColor_ = {}; inputRepresentation_ = {};
    if (!renderer_ || !deckSurfaceColorOutput(surface)) {
        error_ = "Unsupported overlay output surface"; return false;
    }
    pl_frame destination{};
    pl_frame_from_swapchain(&destination, &surface);
    const float black[]{0, 0, 0};
    pl_frame_clear(gpu_, &destination, black);
    destination.overlays = &overlay; destination.num_overlays = 1;
    if (!pl_render_image(renderer_, nullptr, &destination, &pl_render_default_params)) {
        error_ = "Overlay composition failed"; return false;
    }
    return true;
}

bool DeckPlaceboColorRenderer::renderFrame(const AVFrame& frame, pl_frame destination, DeckColorOutput output, std::optional<DeckVideoScaleMode> scale) {
    error_.clear();
    inputColor_ = {};
    inputRepresentation_ = {};
    const auto fail = [&](const char* message) { error_ = message; return false; };
    const auto target = destination.planes[0].texture;
    if (!renderer_ || !target || !target->params.renderable || frame.width <= 0 || frame.height <= 0
            || frame.width > 8192 || frame.height > 8192) return fail("Invalid color-rendering source or target");
    if (!target->params.format || target->params.format->num_components != 4 || target->params.d)
        return fail("Color rendering requires an RGBA 2D target");
    if (scale && !target->params.blit_dst) return fail("Presentation surface cannot clear letterbox bars");
    if (output != DeckColorOutput::Srgb && output != DeckColorOutput::Hdr10Pq) return fail("Unsupported output encoding");
    const auto color = DeckVideoColorInfo::fromFrame(frame);
    if (!color.yuv420 || (color.bitDepth != 8 && color.bitDepth != 10)) return fail("Unsupported source bit depth");
    if (color.hdrSignaled() && !color.hdr10()) return fail("HDR requires explicit 10-bit BT.2020 NCL, PQ and range metadata");
    if (output == DeckColorOutput::Hdr10Pq) {
        if (!color.hdr10()) return fail("HDR10 output requires an HDR10 source");
        const auto* format = target->params.format;
        if (!format || format->num_components < 3) return fail("HDR10 output requires an RGB target");
        for (int i = 0; i < 3; ++i)
            if (format->component_depth[i] < 10) return fail("HDR10 output requires at least 10-bit target precision");
    }
    // Imported VAAPI memory must not return to the decoder's surface pool
    // while GPU reads are pending. Poll without blocking the render thread;
    // refuse additional work at the bounded limit. Teardown drains the GPU.
    retireFrames();
    const auto slot = std::find_if(pendingFrames_.begin(), pendingFrames_.end(), [](const auto& slot) { return !slot.mapped; });
    if (slot == pendingFrames_.end()) return fail("Previous color frames are still in use by the GPU");
    auto& source = slot->frame;
    std::unique_ptr<AVFrame, FreeFrame> mapped;
    const AVFrame* input = &frame;
    if (frame.format == AV_PIX_FMT_VAAPI) {
        if (!frame.hw_frames_ctx || !frame.hw_frames_ctx->data || frame.hw_frames_ctx->size < sizeof(AVHWFramesContext))
            return fail("Missing VAAPI frame context");
        const auto* context = reinterpret_cast<const AVHWFramesContext*>(frame.hw_frames_ctx->data);
        if (context->format != AV_PIX_FMT_VAAPI || (context->sw_format != AV_PIX_FMT_NV12 && context->sw_format != AV_PIX_FMT_P010LE))
            return fail("Unsupported VAAPI surface format");
        if (!context->device_ctx || !context->device_ref || context->device_ctx->type != AV_HWDEVICE_TYPE_VAAPI)
            return fail("Missing VAAPI device context");
        mapped.reset(av_frame_alloc());
        if (!mapped) return fail("Could not allocate a hardware mapping");
        mapped->format = AV_PIX_FMT_DRM_PRIME;
        mapped->width = frame.width; mapped->height = frame.height;
        mapped->hw_frames_ctx = av_buffer_ref(frame.hw_frames_ctx);
        if (!mapped->hw_frames_ctx || av_hwframe_map(mapped.get(), &frame, AV_HWFRAME_MAP_READ | AV_HWFRAME_MAP_DIRECT) < 0
                || av_frame_copy_props(mapped.get(), &frame) < 0) return fail("Direct VAAPI mapping is unavailable");
        input = mapped.get();
    }
    if (input->format == AV_PIX_FMT_DRM_PRIME) {
        if (!deckDrmFrameLayoutSupported(*input)) return fail("Unsupported DRM PRIME plane layout");
        if (!(gpu_->import_caps.tex & PL_HANDLE_DMA_BUF)) return fail("Vulkan DMA-BUF import is unavailable");
        const auto& drm = *reinterpret_cast<const AVDRMFrameDescriptor*>(input->data[0]);
        for (int i = 0; i < drm.nb_layers; ++i) {
            const auto& layer = drm.layers[i];
            const auto format = pl_find_fourcc(gpu_, layer.format);
            const auto& object = drm.objects[layer.planes[0].object_index];
            if (!format || !(format->caps & PL_FMT_CAP_SAMPLEABLE) || !pl_fmt_has_modifier(format, object.format_modifier))
                return fail("Vulkan cannot sample this DRM surface format and modifier");
        }
    }
    pl_avframe_params mapping{};
    mapping.frame = input;
    mapping.tex = slot->uploads;
    mapping.map_dovi = false;
    if (!pl_map_avframe_ex(gpu_, &source, &mapping)) return fail("Could not map the decoded frame to the color renderer");
    slot->mapped = true;
    inputColor_ = source.color;
    inputRepresentation_ = source.repr;
    // Never retain borrowed metadata pointers from the mapped AVFrame.
    inputRepresentation_.dovi = nullptr;
    if (scale) {
        const float black[]{0, 0, 0};
        pl_frame_clear(gpu_, &destination, black);
        const double pixelAspect = frame.sample_aspect_ratio.num > 0 && frame.sample_aspect_ratio.den > 0
            ? static_cast<double>(frame.sample_aspect_ratio.num) / frame.sample_aspect_ratio.den : 1;
        const auto layout = deckVideoScaleLayout({std::abs(source.crop.x1 - source.crop.x0), std::abs(source.crop.y1 - source.crop.y0)},
            {std::abs(destination.crop.x1 - destination.crop.x0), std::abs(destination.crop.y1 - destination.crop.y0)}, *scale, pixelAspect);
        const auto crop = [](pl_rect2df original, QRectF normalized) {
            const float w = original.x1 - original.x0, h = original.y1 - original.y0;
            return pl_rect2df{float(original.x0 + normalized.left()*w), float(original.y0 + normalized.top()*h),
                float(original.x0 + normalized.right()*w), float(original.y0 + normalized.bottom()*h)};
        };
        // Preserve an acquired surface's inverted Y axis and any source crop.
        source.crop = crop(source.crop, layout.source);
        destination.crop = crop(destination.crop, layout.destination);
    }
    auto params = pl_render_default_params;
    params.peak_detect_params = nullptr; // Per-frame static HDR10 metadata only.
    if (!pl_render_image(renderer_, &source, &destination, &params)) return fail("Color rendering failed");
    return true;
}

} // namespace nova::deck::stream
