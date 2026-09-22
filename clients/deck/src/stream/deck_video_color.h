#pragma once

#include <optional>
#include <string_view>

struct AVFrame;

namespace nova::deck::stream {

struct DeckVideoDecoderSpec {
    int codecId;
    int bitDepth;
    std::string_view name;
};

// Exact Moonlight format values only; masks, 4:4:4 and other codecs are rejected.
std::optional<DeckVideoDecoderSpec> videoDecoderSpec(int videoFormat);

struct DeckVideoColorInfo {
    int bitDepth = 0;
    int primaries = 0, transfer = 0, matrix = 0, range = 0;
    bool yuv420 = false;
    bool hdrSignaled() const;
    bool hdr10() const;
    bool legacySdrCompatible() const;
    static DeckVideoColorInfo fromFrame(const AVFrame& frame);
};

} // namespace nova::deck::stream
