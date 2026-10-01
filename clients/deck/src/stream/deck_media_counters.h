#pragma once
#include "polaris/deck_client_media.h"
#include <optional>
namespace nova::deck::stream {
// Counts unique received video frame numbers. Missing numbers measure video
// delivery gaps, including frames discarded by transport; decoder refusals do
// not enter loss. Duplicate parameter/decode units cannot count a frame twice.
class DeckVideoFrameCounters {
public:
    void reset() { const auto generation = counts_.decoderGeneration + 1; counts_ = {}; counts_.decoderGeneration = generation; last_.reset(); }
    void receive(std::uint32_t frame, qint64 atMs) {
        if (atMs <= 0) return;
        if (!counts_.decoderGeneration || (last_ && frame < *last_)) reset();
        if (last_ && frame == *last_) return;
        const std::uint64_t gap = last_ ? std::uint64_t(frame) - *last_ - 1 : 0;
        counts_.expected += gap + 1; counts_.received++; counts_.lost += gap;
        counts_.lastFrameAtMs = atMs; last_ = frame;
    }
    polaris::DeckMediaCounts counts() const { return counts_; }
private:
    polaris::DeckMediaCounts counts_;
    std::optional<std::uint32_t> last_;
};
}
