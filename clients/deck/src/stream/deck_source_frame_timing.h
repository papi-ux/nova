#pragma once
#include "runtime/deck_source_clock_mapper.h"
#include <cstdint>
#include <map>
#include <limits>
#include <optional>
namespace nova::deck::stream {
struct DeckSourceFrameTiming {
    runtime::DeckSourceClockPoint clock;
    std::uint64_t decoderGeneration = 0, presentationGeneration = 0;
    std::uint64_t receiveTimeMs = 0, enqueueTimeMs = 0;
    std::int64_t callbackMonoNs = 0, anchorMonoNs = 0, decodedMonoNs = 0;
    bool usable() const { return clock.valid && clock.active && clock.targetUs >= 0 &&
        clock.targetUs <= std::numeric_limits<std::int64_t>::max() / 1000; }
};
// PTS association, not submit order: output A may be drained while accepting B.
// Ambiguous duplicate PTS never borrows a newer frame's timing. Decoder owner only.
class DeckFrameTimingStore {
public:
    std::optional<std::int64_t> packetPts(const runtime::DeckSourceClockPoint& point) {
        const auto maximum = std::numeric_limits<std::int64_t>::max();
        if (!point.valid || point.sourceUs < 0 || point.sourceUs > maximum / 1000 || lastPts_ >= maximum - 1) return {};
        if (mapperEpoch_ != point.epoch) {
            mapperEpoch_ = point.epoch;
            offset_ = lastPts_ < 0 ? 0 : lastPts_ + 1 - point.sourceUs;
        }
        if (offset_ > 0 && point.sourceUs > maximum - offset_) return {};
        lastPts_ = point.sourceUs + offset_;
        return lastPts_;
    }
    bool put(std::int64_t pts, const DeckSourceFrameTiming& timing) {
        auto existing = entries_.find(pts);
        if (existing != entries_.end()) { existing->second.ambiguous = true; return false; }
        if (entries_.size() >= 64) return false;
        entries_.emplace(pts, Entry{timing, false}); return true;
    }
    std::optional<DeckSourceFrameTiming> take(std::int64_t pts, std::int64_t nowNs) {
        auto it = entries_.find(pts); if (it == entries_.end()) return {};
        auto entry = it->second; entries_.erase(it);
        if (entry.ambiguous || nowNs < entry.timing.callbackMonoNs ||
            nowNs - entry.timing.callbackMonoNs > 1'000'000'000LL) return {};
        entry.timing.decodedMonoNs = nowNs; return entry.timing;
    }
    void expire(std::int64_t nowNs) {
        for (auto it = entries_.begin(); it != entries_.end();) {
            const auto at = it->second.timing.callbackMonoNs;
            if (nowNs < at || nowNs - at > 1'000'000'000LL) it = entries_.erase(it); else ++it;
        }
    }
    // Surface/mapper reset: retain PTS chronology while old decoder output exists.
    void clear() { entries_.clear(); }
    void resetDecoder() { clear(); mapperEpoch_ = 0; offset_ = 0; lastPts_ = -1; }
    std::size_t size() const { return entries_.size(); }
private:
    struct Entry { DeckSourceFrameTiming timing; bool ambiguous; };
    std::map<std::int64_t, Entry> entries_;
    std::uint64_t mapperEpoch_ = 0;
    std::int64_t offset_ = 0, lastPts_ = -1;
};
}
