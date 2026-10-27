#pragma once
#include "polaris/deck_host_telemetry.h"
#include <optional>

namespace nova::deck::runtime {
struct DeckTuningLine { quint64 serial = 0; QString kind, text; int appliedKbps = 0; qint64 emittedAtMs = 0; };
// Rare HUD lines from the Live Tuning readings the reducer already accepted (#384). Only an
// acknowledged applied rate settles a level: applying (requested != applied) and measuring
// readings discard a waiting candidate and keep the announced level. The host's reason is not
// parsed, so a line never claims a cause. Serials grow across the process, so a new stream's
// reducer cannot repeat one an open HUD has already shown.
class DeckTuningLines {
public:
    static constexpr qint64 visibleMs = 4000, easeDwellMs = 2000, fullDwellMs = 10000, spacingMs = 30000;
    // The observer's stale limit; a longer silence starts over.
    static constexpr qint64 readingGapMs = 3500;
    // Returns a line only when this reading emits one. videoUnits: the codec's Live Tuning rate is
    // the encoder's video bitrate (h264, hevc, av1); without it nothing is announced.
    std::optional<DeckTuningLine> observe(const polaris::DeckLiveTuningTelemetry* live, bool videoUnits, qint64 nowMs);
    // A failed, foreign or missing reading: the next one becomes a new baseline.
    void interrupt();
    const std::optional<DeckTuningLine>& line() const { return line_; }
private:
    struct Scope {
        QString instance, appSession, revision;
        qint64 generation = 0;
        int qualityLimit = 0;
        bool operator==(const Scope&) const = default;
    };
    // Applied kbps below the quality limit, or full.
    std::optional<int> announced_, candidate_;
    std::optional<Scope> scope_;
    std::optional<qint64> lastReadingAtMs_, lastLineAtMs_;
    qint64 candidateSinceMs_ = 0;
    bool eased_ = false;
    std::optional<DeckTuningLine> line_;
};
}
