#pragma once

#include <libplacebo/renderer.h>
#include <libplacebo/swapchain.h>
#include <string>
#include <array>
#include <optional>
#include "stream/deck_video_scale.h"

struct AVFrame;

namespace nova::deck::stream {

enum class DeckColorOutput { Srgb, Hdr10Pq };

// Classify the actual acquired surface, never the requested colorspace hint.
// Unsupported encodings and low-precision PQ surfaces stay unsupported.
std::optional<DeckColorOutput> deckSurfaceColorOutput(const pl_swapchain_frame& frame);

// Validate the exact NV12/P010 software format, separate DRM layers and object
// bounds before libplacebo's asserting import helper. No GPU or fd import here.
bool deckDrmFrameLayoutSupported(const AVFrame& frame);

// Color rendering into a caller-owned texture. This does not create a window,
// configure an HDR swapchain or establish display support. Use on the GPU's
// owning render thread, and destroy before the borrowed GPU.
class DeckPlaceboColorRenderer final {
public:
    explicit DeckPlaceboColorRenderer(pl_gpu gpu);
    ~DeckPlaceboColorRenderer();
    DeckPlaceboColorRenderer(const DeckPlaceboColorRenderer&) = delete;
    DeckPlaceboColorRenderer& operator=(const DeckPlaceboColorRenderer&) = delete;
    bool render(const AVFrame& frame, pl_tex target, DeckColorOutput output);
    // Uses the acquired surface's representation, colorimetry and metadata.
    // Scaling changes video only. Fit clears letterbox bars to black.
    bool renderToSurface(const AVFrame& frame, const pl_swapchain_frame& surface, const pl_overlay* overlay = nullptr,
        DeckVideoScaleMode scale = DeckVideoScaleMode::Fit);
    bool renderOverlayToSurface(const pl_swapchain_frame& surface, const pl_overlay& overlay);
    // Polling only. Pressure is temporary; callers may retry with the latest
    // frame before acquiring a swapchain image, without growing the source pool.
    bool canAcceptFrame();
    void retireFrames();
    size_t pendingFrames() const;
    const std::string& error() const { return error_; }
    const pl_color_space& inputColor() const { return inputColor_; }
    const pl_color_repr& inputRepresentation() const { return inputRepresentation_; }
private:
    bool renderFrame(const AVFrame& frame, pl_frame destination, DeckColorOutput output, std::optional<DeckVideoScaleMode> scale);
    pl_gpu gpu_ = nullptr;
    pl_renderer renderer_ = nullptr;
    struct MappedFrame {
        pl_frame frame{};
        pl_tex uploads[4]{};
        bool mapped = false;
    };
    std::array<MappedFrame, 3> pendingFrames_{};
    pl_color_space inputColor_{};
    pl_color_repr inputRepresentation_{};
    std::string error_;
};

} // namespace nova::deck::stream
