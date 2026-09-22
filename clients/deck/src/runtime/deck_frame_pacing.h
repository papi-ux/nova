#pragma once

#include <QString>
#include <algorithm>
#include <cstdint>
#include <cstddef>
#include <optional>

namespace nova::deck::runtime {
enum class DeckFramePacing { Latency, Balanced };
inline std::optional<DeckFramePacing> deckFramePacing(const QString& value) {
    if (value == "latency") return DeckFramePacing::Latency;
    if (value == "balanced") return DeckFramePacing::Balanced;
    return {};
}
inline QString deckFramePacingName(DeckFramePacing mode) {
    return mode == DeckFramePacing::Balanced ? "balanced" : "latency";
}

// Monotonic decoded-frame delivery deadlines, not measured display scanout.
// The display compositor still owns vsync. Missed slots never cause catch-up
// bursts. A two-frame queue bounds decoder leases and added client latency.
class DeckFrameCadence {
public:
    bool configure(DeckFramePacing mode, int fps) {
        if ((mode != DeckFramePacing::Latency && mode != DeckFramePacing::Balanced) || fps < 30 || fps > 90) return false;
        mode_ = mode; period_ = 1'000'000'000LL / fps; next_ = -1;
        return true;
    }
    void arm(std::int64_t now) {
        if (mode_ == DeckFramePacing::Balanced && (next_ < 0 || now > next_ + 2 * period_))
            next_ = now + period_;
    }
    std::int64_t delay(std::int64_t now) const {
        return mode_ == DeckFramePacing::Latency || next_ < 0 ? 0 : std::max<std::int64_t>(0, next_ - now);
    }
    bool missed(std::int64_t now) const { return next_ >= 0 && now >= next_ + period_; }
    void advance(std::int64_t now) {
        if (mode_ == DeckFramePacing::Balanced) next_ = missed(now) ? now + period_ : next_ + period_;
    }
    std::size_t capacity() const { return mode_ == DeckFramePacing::Balanced ? 2 : 1; }
private:
    DeckFramePacing mode_ = DeckFramePacing::Latency;
    std::int64_t period_ = 1'000'000'000LL / 60, next_ = -1;
};
}
