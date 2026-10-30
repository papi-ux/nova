#include "runtime/deck_stream_recap.h"
#include <cmath>

namespace nova::deck::runtime {
namespace {
// Largest integer QML numbers carry exactly.
constexpr std::uint64_t kMaxCount = (std::uint64_t(1) << 53) - 1;
// Same bridging limit as the live HUD: a longer gap could hide a counter reset.
constexpr qint64 kMaxSampleGapMs = 2500;
QVariant count(std::uint64_t value) { return value <= kMaxCount ? QVariant(qint64(value)) : QVariant(); }
// A mean over the frames that carried the reading, with those frames as its coverage.
QVariant mean(std::uint64_t total, std::uint64_t frames, double unitsPerMs) {
    if (!frames || frames > kMaxCount || total > kMaxCount) return {};
    const double value = double(total) / (unitsPerMs * double(frames));
    if (!std::isfinite(value) || value < 0 || value > 1e6) return {};
    return QVariantMap{{"meanMs", value}, {"frames", qint64(frames)}};
}
// Decoder setup records the format and zeroes every counter used here.
bool setUp(const DeckHudSample& s) { return s.width > 0 && s.height > 0; }
}

void DeckStreamRecapSpan::accept(const DeckHudSample& s) {
    ++samples_;
    // A stalled frame count can disappear from the HUD without ending its epoch.
    const bool sequence = s.media && decoderGeneration_ && s.media->counts.decoderGeneration != *decoderGeneration_;
    if (s.media) decoderGeneration_ = s.media->counts.decoderGeneration;
    if (!last_) { first_ = last_ = s; return; }
    const auto& p = *last_;
    // A repeated decoder setup zeroes the counters, or changes the format it records.
    const bool reset = s.incoming < p.incoming || s.bytes < p.bytes || s.decoded < p.decoded || s.refused < p.refused ||
        s.videoWorkMicros < p.videoWorkMicros || s.videoWorkSamples < p.videoWorkSamples ||
        s.hostLatencyTenths < p.hostLatencyTenths || s.hostLatencySamples < p.hostLatencySamples ||
        s.width != p.width || s.height != p.height || s.codec != p.codec;
    const auto elapsed = s.atMs - p.atMs;
    if (reset) restarted_ = true;
    if (reset || sequence || elapsed <= 0 || elapsed > kMaxSampleGapMs) continuous_ = false;
    // Only a step the counters can answer for goes on the line.
    else if (setUp(s)) addTrail(double(s.incoming - p.incoming) * 1000.0 / double(elapsed));
    last_ = s;
}

void DeckStreamRecapSpan::addTrail(double fps) {
    trailPendingSum_ += fps;
    if (++trailPending_ < trailPerSlot_) return;
    trail_[std::size_t(trailUsed_++)] = trailPendingSum_ / trailPending_;
    trailPendingSum_ = 0; trailPending_ = 0;
    if (std::size_t(trailUsed_) == trail_.size()) {
        for (int i = 0; i < trailUsed_ / 2; ++i)
            trail_[std::size_t(i)] = (trail_[std::size_t(2 * i)] + trail_[std::size_t(2 * i + 1)]) / 2.0;
        trailUsed_ /= 2; trailPerSlot_ *= 2;
    }
}

QVariantList DeckStreamRecapSpan::trail() const {
    QVariantList values;
    for (int i = 0; i < trailUsed_; ++i) values << trail_[std::size_t(i)];
    if (trailPending_ > 0) values << trailPendingSum_ / trailPending_;
    // One point is no line.
    return values.size() >= 2 ? values : QVariantList{};
}

QVariantMap deckStreamRecap(const DeckStreamRecapSpan& span, DeckStreamRecapEnd end, std::uint64_t generation,
        const QString& hostId, const QString& gameId) {
    if (!span.last_ || !generation || hostId.isEmpty() || gameId.isEmpty() || span.last_->atMs < 0) return {};
    const auto& s = *span.last_;
    const bool setup = setUp(s);
    QVariant missing;
    if (setup && s.media && polaris::validClientMedia(*s.media) && s.media->counts.expected > 0) {
        const auto& c = s.media->counts;
        // A later sequence generation restarted the count: it covers only that part.
        missing = QVariantMap{{"percent", double(c.lost) * 100.0 / double(c.expected)}, {"lost", qint64(c.lost)},
            {"expected", qint64(c.expected)}, {"partial", c.decoderGeneration > 1}};
    }
    QVariant video;
    if (setup && !s.codec.isEmpty()) video = QVariantMap{{"codec", s.codec}, {"width", s.width}, {"height", s.height},
        {"decoder", s.decoderBackend == "Unavailable" ? QString() : s.decoderBackend}};
    QVariant rates;
    const auto& f = *span.first_;
    const auto spanMs = s.atMs - f.atMs;
    // Only exact deltas of one continuous decoder run, ending on a sample that was
    // current when the user stopped. These are not presented, target or encoder rates.
    if (span.samples_ >= 2 && span.continuous_ && span.ended_ && span.endCurrent_ && setUp(f) && setup && spanMs > 0 &&
            s.incoming >= f.incoming && s.decoded >= f.decoded && s.bytes >= f.bytes) {
        const auto perSecond = [spanMs](std::uint64_t delta) { return double(delta) * 1000.0 / double(spanMs); };
        rates = QVariantMap{{"spanMs", spanMs}, {"receivedFrames", count(s.incoming - f.incoming)},
            {"receivedFps", perSecond(s.incoming - f.incoming)}, {"decodedFrames", count(s.decoded - f.decoded)},
            {"decodedFps", perSecond(s.decoded - f.decoded)},
            {"receivedVideoMbps", double(s.bytes - f.bytes) * 8.0 / (1000.0 * double(spanMs))}};
    }
    return {{"generation", QString::number(generation)}, {"hostId", hostId}, {"gameId", gameId},
        {"end", end == DeckStreamRecapEnd::EndGame ? "end_game" : "disconnect"},
        // Active to the last accepted 1 s sample on the worker clock: a lower bound of the connection.
        {"measuredForMs", s.atMs},
        // The decoder restarted during the run; counts and means cover the part after it.
        {"restarted", span.restarted_},
        {"framesReceived", setup ? count(s.incoming) : QVariant()},
        {"refusedFrames", setup && s.videoWorkSamples ? count(s.refused) : QVariant()},
        {"missingFrames", missing},
        {"hostProcessing", setup ? mean(s.hostLatencyTenths, s.hostLatencySamples, 10.0) : QVariant()},
        {"videoWork", setup ? mean(s.videoWorkMicros, s.videoWorkSamples, 1000.0) : QVariant()},
        {"rates", rates}, {"video", video}, {"fpsTrail", span.trail()}};
}
}
