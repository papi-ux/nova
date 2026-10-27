#include "runtime/deck_tuning_lines.h"
#include <QtMath>
#include <atomic>

namespace nova::deck::runtime {
namespace {
constexpr int full = -1;
std::atomic<quint64> serials {0};
bool settled(const QString& state) { return state == "adjusting" || state == "stable"; }
bool held(const QString& state) { return state == "applying" || state == "measuring"; }
// Android's StreamPolicyUiState.formatMbps, so both clients name a rate the same way.
QString mbps(int kbps) {
    return kbps >= 10000 || kbps % 1000 == 0 ? QString::number(qRound(kbps / 1000.0)) + " Mbps"
        : QString::number(kbps / 1000.0, 'f', 1) + " Mbps";
}
}
void DeckTuningLines::interrupt() {
    announced_.reset(); candidate_.reset(); scope_.reset();
    lastReadingAtMs_.reset(); lastLineAtMs_.reset(); eased_ = false; line_.reset();
}
std::optional<DeckTuningLine> DeckTuningLines::observe(const polaris::DeckLiveTuningTelemetry* live, bool videoUnits, qint64 nowMs) {
    if (lastReadingAtMs_ && (nowMs < *lastReadingAtMs_ || nowMs - *lastReadingAtMs_ > readingGapMs)) interrupt();
    // A reading without video units says nothing and drops the baseline, so no ease outlives the switch.
    if (!live || !videoUnits || !live->enabled || !live->supported || live->qualityLimit <= 0 ||
        (!settled(live->state) && !held(live->state))) {
        interrupt(); return {};
    }
    lastReadingAtMs_ = nowMs;
    const Scope next {live->instance, live->appSession, live->revision, live->generation, live->qualityLimit};
    if (scope_ != next) { interrupt(); scope_ = next; lastReadingAtMs_ = nowMs; }
    if (!settled(live->state) || live->applied <= 0) { candidate_.reset(); return {}; }
    const int level = live->applied >= live->qualityLimit ? full : live->applied;
    if (!announced_) { announced_ = level; return {}; }
    const int current = *announced_;
    QString kind;
    if (current == full && level != full) kind = "eased";
    else if (current != full && level == full) kind = "full";
    else if (current != full && level > current) { announced_ = level; candidate_.reset(); return {}; }
    else if (current != full && qint64(level) * 100 <= qint64(current) * 90) kind = "eased";
    else { candidate_.reset(); return {}; }
    if (candidate_ != level) { candidate_ = level; candidateSinceMs_ = nowMs; }
    if (nowMs - candidateSinceMs_ < (level == full ? fullDwellMs : easeDwellMs)) return {};
    if (lastLineAtMs_ && nowMs - *lastLineAtMs_ < spacingMs) return {};
    candidate_.reset(); announced_ = level;
    if (kind == "full" && !eased_) return {};
    eased_ = kind == "eased";
    lastLineAtMs_ = nowMs;
    line_ = DeckTuningLine{++serials, kind, kind == "full" ? QStringLiteral("Back to full quality") : "Eased to " + mbps(live->applied),
        live->applied, nowMs};
    return line_;
}
}
