#pragma once

#include <string>
#include <vector>
#include <cmath>

namespace nova::deck {

inline int deckDisplayRateLimit(double refreshHz) {
    if (!std::isfinite(refreshHz) || refreshHz <= 0 || refreshHz > 1000) return 60;
    // Retain the existing two-Hz tolerance for standard 60/90 modes. Other
    // current modes (40, 45, 50, 72, 75 Hz) have their own whole-number limit.
    if (refreshHz >= 88) return 90;
    if (refreshHz >= 58 && refreshHz < 60) return 60;
    return static_cast<int>(std::floor(refreshHz));
}

// The current native media profile. HDR, AV1 and rates above 90 must
// extend the decoder/presenter path before becoming selectable preferences.
inline bool supportedDeckResolution(int width, int height) {
    // Storage/protocol bounds. Actual codec-specific hardware limits are
    // checked during review and again on the native launch worker.
    return width >= 320 && width <= 4096 && height >= 240 && height <= 4096 &&
        width % 2 == 0 && height % 2 == 0;
}

struct DeckStreamCapabilities {
    bool valid = true;
    bool h264 = true; // Missing legacy metadata keeps GameStream's H.264 default.
    double maxFps = 0; // Zero means not advertised, never inferred from a game HDR badge.
    bool hevc = false; // Explicit 8-bit HEVC support only.
    bool supports(int width, int height, int fps) const {
        return valid && (h264 || hevc) && supportedDeckResolution(width, height) &&
            (fps >= 30 && fps <= 90) && (maxFps == 0 || fps <= maxFps) &&
            (fps <= 60 || maxFps >= fps); // High rates require an advertised host limit.
    }
};

struct DeckDisplayRecommendation {
    int width = 0, height = 0;
    std::string title, detail;
    bool recommended = false;
    bool advanced = false, custom = false;
};

struct DeckDisplayPlanner {
    bool available = false;
    std::vector<DeckDisplayRecommendation> choices;
};

} // namespace nova::deck
