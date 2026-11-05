#pragma once
#include "runtime/deck_source_submission_fence.h"
#include <cstdint>
#include <deque>
#include <string_view>

namespace nova::deck::runtime {
// Shared source-time contract v1, ROOT D1-D4, instability revision 2. Values are provisional: retain
// policy/version and every constant in replay/trace headers before qualification.
struct DeckSourceClockPolicy {
    std::int64_t offsetWindowUs = 3'000'000, offsetSamples = 1024, warmupFrames = 8;
    std::int64_t offsetSlewUsPerSecond = 1000, offsetMaxStepUs = 100;
    std::int64_t anchorResolutionUs = 1000, offsetDivergenceToleranceUs = 5000, offsetDivergenceFrames = 8;
    std::int64_t offsetBacklogLimitUs = 20000, offsetBacklogHoldUs = 1'000'000;
    std::int64_t offsetStepThresholdUs = 20000, offsetStepFrames = 8;
    std::int64_t reserveStartUs = 3000, reserveMaximumUs = 12000;
    std::int64_t readinessWindowUs = 3'000'000, readinessSamples = 1024;
    std::int64_t readinessPercentile = 98, readinessMarginUs = 500;
    std::int64_t reserveAttackUs = 500, cleanHoldUs = 2'000'000, reserveReleaseUsPerSecond = 250;
    std::int64_t discontinuityUs = 1'000'000, hitchExclusionUs = 25000;
};
struct DeckSourceClockPoint {
    std::uint64_t epoch = 0;
    std::uint32_t frame = 0, rtp = 0;
    std::int64_t sourceUs = 0, baseUs = 0, targetUs = 0;
    bool valid = false, active = false;
    std::string_view reason = "source_timestamp_missing";
};
class DeckSourceClockMapper {
public:
    explicit DeckSourceClockMapper(DeckSourceClockPolicy policy = {}, unsigned capacity = 1,
        std::int64_t admittedPeriodUs = 16666);
    DeckSourceClockPoint observe(std::uint32_t frame, std::uint32_t rtp, std::int64_t anchorUs);
    void ready(const DeckSourceClockPoint& point, std::int64_t decodedUs);
    void reset();
    void setSubmissionFence(std::shared_ptr<DeckSourceSubmissionFence> fence);
    std::int64_t reserveUs() const { return reserve_; }
    std::int64_t reserveCapUs() const;
    std::size_t historySize() const { return offsets_.size(); }
    std::uint64_t epoch() const { return epoch_; }
    bool pressure() const { return pressure_; }
private:
    void resetUnlocked();
    DeckSourceClockPoint observeUnlocked(std::uint32_t frame, std::uint32_t rtp, std::int64_t anchorUs);
    std::shared_ptr<DeckSourceSubmissionFence> submissionFence_;
    struct Sample { std::int64_t at, value; };
    DeckSourceClockPolicy policy_;
    unsigned capacity_;
    std::int64_t admittedPeriod_, period_, reserve_ = 0, offset_ = 0, lastAnchor_ = 0;
    std::int64_t lastTarget_ = 0, slewRemainder_ = 0, lastReady_ = 0, cleanSince_ = -1, releaseRemainder_ = 0;
    std::int64_t backlogSince_ = -1, divergenceFrames_ = 0, stepFrames_ = 0;
    int divergenceDirection_ = 0, stepDirection_ = 0, backlogDirection_ = 0;
    std::uint64_t epoch_ = 0, ticks_ = 0;
    std::uint32_t frame_ = 0, rtp_ = 0;
    std::size_t observations_ = 0;
    bool have_ = false, pressure_ = false;
    std::deque<Sample> offsets_, envelopes_, ready_;
    std::deque<std::int64_t> periods_;
};
}
