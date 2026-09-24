#pragma once

#include <string>
#include <vector>
#include <cmath>

namespace nova::deck {

// Bounds of the current Polaris resolved-profile request contract. These are
// shared by storage, review, planning and frame delivery, not a Deck hardware cap.
inline constexpr int deckMinProfileFps = 15;
inline constexpr int deckMaxProfileFps = 240;
inline constexpr int deckMinProfileBitrateKbps = 1000;
inline constexpr int deckMaxProfileBitrateKbps = 300000;
inline bool supportedDeckProfileRate(int fps) { return fps >= deckMinProfileFps && fps <= deckMaxProfileFps; }
inline bool supportedDeckProfileBitrate(int bitrate) { return bitrate >= deckMinProfileBitrateKbps && bitrate <= deckMaxProfileBitrateKbps; }

inline int deckDisplayRateLimit(double refreshHz) {
    if (!std::isfinite(refreshHz) || refreshHz <= 0 || refreshHz > 1000) return 60;
    // Preserve nominal rates for standard fractional-refresh modes, extending
    // the existing 60/90 tolerance to desktop displays. Other modes retain their
    // own limit; a 360 Hz monitor must never be reported as a 90 Hz display.
    for (const int nominal : {60, 90, 120, 144, 165, 240, 360})
        if (refreshHz >= nominal - 2 && refreshHz <= nominal) return nominal;
    return static_cast<int>(std::floor(refreshHz));
}

// Codec-specific decoder and presenter support is checked separately from rates.
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
    bool pyrowave = false; // Requires the exact pinned native extension.
    bool supports(int width, int height, int fps) const {
        return valid && (h264 || hevc || pyrowave) && supportedDeckResolution(width, height) &&
            supportedDeckProfileRate(fps) && (maxFps == 0 || fps <= maxFps) &&
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
