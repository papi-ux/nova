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
}
DeckSourceClockMapper::DeckSourceClockMapper(DeckSourceClockPolicy policy, unsigned capacity, std::int64_t period)
    : policy_(policy), capacity_(capacity), admittedPeriod_(period), period_(period) {
    if ((capacity != 1 && capacity != 2) || period <= 0 || period > 1'000'000 ||
        policy.offsetWindowUs <= 0 || policy.offsetWindowUs > 3'000'000 ||
        policy.offsetSamples <= 0 || policy.offsetSamples > 1024 || policy.warmupFrames <= 0 || policy.warmupFrames > 1024 ||
        policy.offsetSlewUsPerSecond < 0 || policy.offsetSlewUsPerSecond > 1000 ||
        policy.offsetMaxStepUs < 0 || policy.offsetMaxStepUs > 100 ||
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
    offsets_.clear(); ready_.clear(); periods_.clear(); period_ = admittedPeriod_;
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
        // Elapsed-time credit prevents per-frame rate from changing the slew budget.
        const auto numerator = (anchor - lastAnchor_) * policy_.offsetSlewUsPerSecond + slewRemainder_;
        const auto step = std::min(policy_.offsetMaxStepUs, numerator / 1'000'000);
        slewRemainder_ = numerator % 1'000'000;
        const auto correction = minimum - offset_;
        if (correction < -step || correction > step) {
            // An unfunded correction is not clock drift we can keep tracking.
            // Start a fresh epoch from this observation so queued old targets
            // and readiness samples cannot qualify the new mapping.
            reset();
            auto rewarming = observe(frame, rtp, anchor);
            rewarming.active = false;
            rewarming.reason = "clock_mapping_unstable";
            return rewarming;
        }
        offset_ += correction;
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
