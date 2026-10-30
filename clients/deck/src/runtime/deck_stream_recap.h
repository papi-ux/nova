#pragma once
#include "runtime/deck_hud_metrics.h"
#include <QVariantList>
#include <QVariantMap>
#include <array>
#include <cstdint>
#include <optional>

namespace nova::deck::runtime {
// Which explicit exit the user chose. Neither proves the game quit on the PC.
enum class DeckStreamRecapEnd { EndGame, Disconnect };

// The first and last worker samples the GUI accepted for one run, and whether
// every step between them was continuous, retaining the last reported frame
// epoch across absent media. The existing poll hands it samples it already accepted.
class DeckStreamRecapSpan {
public:
    void reset() { *this = {}; }
    // One new accepted sample of the current run, in receipt order.
    void accept(const DeckHudSample& sample);
    // At the user's stop request: whether the last accepted sample was still current.
    void end(bool lastSampleCurrent) { ended_ = true; endCurrent_ = lastSampleCurrent; }
    bool empty() const { return !last_; }
private:
    friend QVariantMap deckStreamRecap(const DeckStreamRecapSpan&, DeckStreamRecapEnd, std::uint64_t,
        const QString&, const QString&);
    std::optional<DeckHudSample> first_, last_;
    std::optional<std::uint64_t> decoderGeneration_;
    int samples_ = 0;
    bool continuous_ = true, restarted_ = false, ended_ = false, endCurrent_ = false;
    // The received frame rate over the run, for the recap's line (#423). Constant memory:
    // when it fills, neighbours average into one slot and each slot covers twice as many.
    void addTrail(double fps);
    QVariantList trail() const;
    std::array<double, 120> trail_{};
    int trailUsed_ = 0, trailPerSlot_ = 1, trailPending_ = 0;
    double trailPendingSum_ = 0;
};

// Last Stream (#383 Linux): presentation only, frozen once from the ended run's
// span. Counts and per-frame means cover their own frames since decoder setup.
// Rates cover only the continuous span between the first and last samples, so
// startup before the first sample is excluded. A missing reading is null, never
// zero. Nothing is stored, exported or sent. Empty when the run can't be identified.
QVariantMap deckStreamRecap(const DeckStreamRecapSpan& span, DeckStreamRecapEnd end, std::uint64_t generation,
    const QString& hostId, const QString& gameId);
}
