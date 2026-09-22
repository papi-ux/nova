#pragma once

#include <array>
#include <cstdint>
#include <optional>

namespace nova::deck::runtime {

// Matches Android ConnectionResilienceManager / ReconnectRecoveryTracker.
// Only continuously advancing composition samples replenish the retry budget.
class DeckReconnectPolicy {
public:
    static constexpr std::array<int, 4> delaysMs{0, 1000, 3000, 7000};
    std::optional<int> nextDelay() {
        if (attempts_ == delaysMs.size()) return {};
        return delaysMs[attempts_++];
    }
    int attempts() const { return attempts_; }
    void beginStream(std::uint64_t frames, std::int64_t nowMs) {
        lastFrames_ = frames;
        lastSampleMs_ = nowMs;
        renderingSinceMs_ = -1;
    }
    void sample(std::uint64_t frames, std::int64_t nowMs) {
        if (nowMs - lastSampleMs_ < 1000) return;
        if (frames <= lastFrames_ || nowMs - lastSampleMs_ > 2000) renderingSinceMs_ = -1;
        else if (renderingSinceMs_ < 0) renderingSinceMs_ = nowMs;
        lastFrames_ = frames;
        lastSampleMs_ = nowMs;
        if (renderingSinceMs_ >= 0 && nowMs - renderingSinceMs_ >= 15000) attempts_ = 0;
    }
private:
    int attempts_ = 0;
    std::uint64_t lastFrames_ = 0;
    std::int64_t lastSampleMs_ = 0, renderingSinceMs_ = -1;
};

} // namespace nova::deck::runtime
