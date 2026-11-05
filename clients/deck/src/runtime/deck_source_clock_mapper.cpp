#include "runtime/deck_source_clock_mapper.h"
#include <algorithm>
#include <limits>
#include <stdexcept>
#include <vector>

namespace nova::deck::runtime {
namespace {
constexpr auto maxUs = std::numeric_limits<std::int64_t>::max() / 1000;
template<class Sample>
void prune(std::deque<Sample>& samples, std::int64_t now, std::int64_t window, std::int64_t count) {
    while (!samples.empty() && (samples.size() > static_cast<std::size_t>(count) || now - samples.front().at > window))
        samples.pop_front();
}
int directionBeyond(std::int64_t value, std::int64_t bound) {
    return value > bound ? 1 : value < -bound ? -1 : 0;
}
void confirm(int direction, int& previous, std::int64_t& frames) {
    frames = direction == 0 ? 0 : direction == previous ? frames + 1 : 1;
    previous = direction;
}
}
DeckSourceClockMapper::DeckSourceClockMapper(DeckSourceClockPolicy policy, unsigned capacity, std::int64_t period)
    : policy_(policy), capacity_(capacity), admittedPeriod_(period), period_(period) {
    if ((capacity != 1 && capacity != 2) || period <= 0 || period > 1'000'000 ||
        policy.offsetWindowUs <= 0 || policy.offsetWindowUs > 3'000'000 ||
        policy.offsetSamples <= 0 || policy.offsetSamples > 1024 || policy.warmupFrames <= 0 || policy.warmupFrames > 1024 ||
        policy.offsetSlewUsPerSecond < 0 || policy.offsetSlewUsPerSecond > 1000 ||
        policy.offsetMaxStepUs < 0 || policy.offsetMaxStepUs > 100 ||
        policy.anchorResolutionUs <= 0 || policy.anchorResolutionUs > 1'000'000 ||
        policy.offsetDivergenceToleranceUs < 0 || policy.offsetDivergenceToleranceUs > 1'000'000 ||
        policy.offsetDivergenceFrames <= 0 || policy.offsetDivergenceFrames > 1024 ||
        policy.offsetBacklogLimitUs <= 0 || policy.offsetBacklogLimitUs > 1'000'000 ||
        policy.offsetBacklogHoldUs <= 0 || policy.offsetBacklogHoldUs > 3'000'000 ||
        policy.offsetStepThresholdUs <= 0 || policy.offsetStepThresholdUs > 1'000'000 ||
        policy.offsetStepFrames <= 0 || policy.offsetStepFrames > 1024 ||
        policy.reserveStartUs < 0 || policy.reserveMaximumUs < 0 || policy.reserveMaximumUs > 12000 ||
        policy.readinessWindowUs <= 0 || policy.readinessWindowUs > 3'000'000 ||
        policy.readinessSamples <= 0 || policy.readinessSamples > 1024 ||
        policy.readinessPercentile < 1 || policy.readinessPercentile > 100 || policy.readinessMarginUs < 0 || policy.readinessMarginUs > 12000 ||
        policy.reserveAttackUs < 0 || policy.reserveAttackUs > 500 || policy.cleanHoldUs < 0 ||
        policy.reserveReleaseUsPerSecond < 0 || policy.reserveReleaseUsPerSecond > 250 ||
        policy.discontinuityUs <= 0 || policy.discontinuityUs > 1'000'000 || policy.hitchExclusionUs <= 0)
        throw std::invalid_argument("invalid bounded source-clock policy");
    reset();
}
void DeckSourceClockMapper::reset() {
    ++epoch_; have_ = false; ticks_ = observations_ = 0; offset_ = lastAnchor_ = lastTarget_ = 0;
    lastReady_ = slewRemainder_ = releaseRemainder_ = 0; cleanSince_ = -1; pressure_ = false;
    backlogSince_ = -1; divergenceFrames_ = stepFrames_ = 0;
    divergenceDirection_ = stepDirection_ = backlogDirection_ = 0;
    offsets_.clear(); envelopes_.clear(); ready_.clear(); periods_.clear(); period_ = admittedPeriod_;
    reserve_ = std::min(policy_.reserveStartUs, reserveCapUs());
}
std::int64_t DeckSourceClockMapper::reserveCapUs() const {
    return std::min(policy_.reserveMaximumUs, static_cast<std::int64_t>(capacity_) * period_);
}
DeckSourceClockPoint DeckSourceClockMapper::observe(std::uint32_t frame, std::uint32_t rtp, std::int64_t anchor) {
    DeckSourceClockPoint point; point.frame = frame; point.rtp = rtp; point.epoch = epoch_;
    if (anchor < 0 || anchor > maxUs - policy_.reserveMaximumUs) { reset(); point.epoch = epoch_; point.reason = "clock_mapping_unstable"; return point; }
    std::string_view phase = "warmup";
    if (have_) {
        const std::uint32_t df = frame - frame_, dt = rtp - rtp_;
        if (df == 0 || df >= 0x80000000U) { point.reason = "stale_frame"; return point; }
        if (anchor < lastAnchor_ || dt >= 0x80000000U || std::uint64_t(dt) * 1000 / 90 > std::uint64_t(policy_.discontinuityUs)) {
            phase = anchor < lastAnchor_ ? "clock_mapping_unstable" : "source_discontinuity";
            reset(); point.epoch = epoch_;
        } else if (dt == 0) {
            frame_ = frame; point.reason = "duplicate_timestamp"; return point;
        } else {
            ticks_ += dt; // Each admitted delta is <=1 second; overflow checked below before conversion.
            periods_.push_back(static_cast<std::int64_t>(std::uint64_t(dt) * 1000 / 90 / df));
            if (periods_.size() > 31) periods_.pop_front();
            auto sorted = std::vector<std::int64_t>(periods_.begin(), periods_.end());
            std::sort(sorted.begin(), sorted.end()); period_ = std::max<std::int64_t>(1, sorted[sorted.size()/2]);
            reserve_ = std::min(reserve_, reserveCapUs());
        }
    }
    if (!have_) {
        if (rtp == 0) { point.reason = "source_timestamp_missing"; return point; }
        have_ = true; ticks_ = 0; offset_ = anchor; lastAnchor_ = anchor;
    }
    if (ticks_ > std::uint64_t(maxUs) * 90 / 1000) { reset(); point.epoch = epoch_; point.reason = "clock_mapping_unstable"; return point; }
    point.sourceUs = static_cast<std::int64_t>(ticks_ * 1000 / 90);
    offsets_.push_back({anchor, anchor - point.sourceUs});
    prune(offsets_, anchor, policy_.offsetWindowUs, policy_.offsetSamples);
    auto minimum = offsets_.front().value;
    for (auto sample : offsets_) minimum = std::min(minimum, sample.value);
    ++observations_;
    if (observations_ <= static_cast<std::size_t>(policy_.warmupFrames)) offset_ = std::min(offset_, minimum);
    else {
        // Startup may replace an initially late offset while fitting warmup.
        // Only steady estimates seed the divergence reference; that expected
        // startup convergence must not become a later instability vote.
        envelopes_.push_back({anchor, minimum});
        prune(envelopes_, anchor, policy_.offsetWindowUs, policy_.offsetSamples);
        // Elapsed-time credit prevents per-frame rate from changing the slew budget.
        const auto numerator = (anchor - lastAnchor_) * policy_.offsetSlewUsPerSecond + slewRemainder_;
        const auto step = std::min(policy_.offsetMaxStepUs, numerator / 1'000'000);
        slewRemainder_ = numerator % 1'000'000;
        const auto correction = minimum - offset_;
        const auto applied = std::clamp(correction, -step, step);

        // Resolution/jitter can move the estimate by much more than one frame's
        // slew credit. Smoothing debt alone is never an instability signal.
        const auto tolerance = std::max(policy_.offsetDivergenceToleranceUs, 4 * policy_.anchorResolutionUs);
        const auto reference = envelopes_.front().value;
        const auto movement = directionBeyond(minimum - reference, tolerance);
        const auto sampleMovement = directionBeyond(anchor - point.sourceUs - reference, tolerance);
        confirm(movement == sampleMovement ? movement : 0, divergenceDirection_, divergenceFrames_);

        // Positive steps would otherwise hide behind old low samples for up
        // to the entire envelope window. Require forward samples in one
        // direction, not one jitter spike, before re-anchoring them promptly.
        confirm(directionBeyond(anchor - point.sourceUs - offset_, policy_.offsetStepThresholdUs),
            stepDirection_, stepFrames_);
        const auto backlog = directionBeyond(correction - applied, policy_.offsetBacklogLimitUs);
        if (!backlog) backlogSince_ = -1;
        else if (backlog != backlogDirection_ || backlogSince_ < 0) backlogSince_ = anchor;
        backlogDirection_ = backlog;
        if (divergenceFrames_ >= policy_.offsetDivergenceFrames || stepFrames_ >= policy_.offsetStepFrames ||
            (backlogSince_ >= 0 && anchor - backlogSince_ > policy_.offsetBacklogHoldUs)) {
            // Cancel the old epoch and readiness history. Exactly this forward
            // sample seeds the new warmup, including with warmupFrames == 1.
            reset();
            auto rewarming = observe(frame, rtp, anchor);
            rewarming.active = false;
            rewarming.reason = "clock_mapping_unstable";
            return rewarming;
        }
        offset_ += applied;
    }
    point.baseUs = point.sourceUs + offset_;
    if (point.baseUs < 0 || point.baseUs > maxUs - reserve_) { reset(); point.epoch = epoch_; point.reason = "clock_mapping_unstable"; return point; }
    point.targetUs = std::max(point.baseUs + reserve_, lastTarget_ + 1);
    if (point.targetUs > maxUs) { reset(); point.epoch = epoch_; point.reason = "clock_mapping_unstable"; return point; }
    point.valid = true; point.active = observations_ >= static_cast<std::size_t>(policy_.warmupFrames);
    point.reason = point.active ? "none" : phase;
    // Preserve a discontinuity reason for its first re-anchored frame, even with warmup=1 in a fixture.
    if (phase != "warmup") point.reason = phase;
    frame_ = frame; rtp_ = rtp; lastAnchor_ = anchor; lastTarget_ = point.targetUs;
    return point;
}
void DeckSourceClockMapper::ready(const DeckSourceClockPoint& point, std::int64_t decoded) {
    if (!point.valid || point.epoch != epoch_ || decoded < point.baseUs || decoded < lastReady_ || decoded > maxUs) return;
    const auto elapsed = lastReady_ ? decoded - lastReady_ : 0; lastReady_ = decoded;
    const auto lateness = decoded - point.baseUs;
    if (lateness > policy_.hitchExclusionUs) { pressure_ = true; cleanSince_ = -1; return; }
    ready_.push_back({decoded, lateness}); prune(ready_, decoded, policy_.readinessWindowUs, policy_.readinessSamples);
    std::vector<std::int64_t> values; for (auto s : ready_) values.push_back(s.value);
    std::sort(values.begin(), values.end());
    const auto rank = (values.size() * policy_.readinessPercentile + 99) / 100 - 1;
    const auto desired = values[rank] + policy_.readinessMarginUs, cap = reserveCapUs();
    pressure_ = desired > cap;
    const auto bounded = std::min(desired, cap);
    if (bounded > reserve_) { reserve_ += std::min(policy_.reserveAttackUs, bounded - reserve_); cleanSince_ = -1; }
    else {
        if (cleanSince_ < 0) cleanSince_ = decoded;
        if (decoded - cleanSince_ >= policy_.cleanHoldUs) {
            const auto releaseElapsed = std::min(elapsed, decoded - cleanSince_ - policy_.cleanHoldUs);
            const auto credit = releaseElapsed * policy_.reserveReleaseUsPerSecond + releaseRemainder_;
            reserve_ -= std::min(reserve_ - bounded, credit / 1'000'000); releaseRemainder_ = credit % 1'000'000;
        }
    }
    reserve_ = std::clamp(reserve_, std::int64_t(0), cap);
}
}
