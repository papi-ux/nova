#include "runtime/deck_hud_metrics.h"
#include "runtime/deck_hud_host.h"
#include <algorithm>

namespace nova::deck::runtime {
QVariantMap DeckHudMetrics::empty() {
    auto result = DeckHudHostReducer::unavailable();
    const QVariantMap local{{"fps", "--"}, {"incoming", "--"}, {"decoded", "--"}, {"target", ""},
        {"host", "--"}, {"rtt", "--"}, {"jitter", "--"}, {"bitrate", "--"},
        {"resolution", "--"}, {"codec", "--"}, {"history", QVariantList{}},
        {"fresh", false}, {"truth", "Waiting for stream readings"}};
    for (auto it = local.cbegin(); it != local.cend(); ++it) result.insert(it.key(), it.value());
    return result;
}
QVariantMap DeckHudMetrics::sample(const DeckHudSample& s) {
    auto out = empty();
    out["codec"] = s.codec.isEmpty() ? "--" : s.codec;
    if (s.width > 0 && s.height > 0) out["resolution"] = QString("%1×%2").arg(s.width).arg(s.height);
    if (s.targetFps > 0) out["target"] = QString("/ %1 target").arg(s.targetFps);
    const auto previous = previous_;
    previous_ = s;
    // Do not bridge a stall, reconnect, counter reset or surface replacement.
    const auto elapsed = previous ? s.atMs - previous->atMs : 0;
    if (!previous || elapsed < 500 || elapsed > 2500 || s.incoming < previous->incoming ||
        s.bytes < previous->bytes || s.decoded < previous->decoded || s.composed < previous->composed ||
        s.hostLatencySamples < previous->hostLatencySamples || s.hostLatencyTenths < previous->hostLatencyTenths ||
        s.compositionAvailable != previous->compositionAvailable) {
        history_.clear(); return out;
    }
    const auto rate = [elapsed](std::uint64_t delta) { return delta * 1000.0 / elapsed; };
    const auto fps = rate(s.composed - previous->composed);
    out["fresh"] = true;
    out["truth"] = "Composed FPS · video payload bitrate";
    out["incoming"] = QString::number(rate(s.incoming - previous->incoming), 'f', 1);
    out["decoded"] = QString::number(rate(s.decoded - previous->decoded), 'f', 1);
    out["bitrate"] = QString::number(rate(s.bytes - previous->bytes) * 8 / 1000000, 'f', 1) + "M";
    if (s.compositionAvailable) {
        out["fps"] = QString::number(fps, 'f', 1);
        history_.append(fps);
        while (history_.size() > 60) history_.removeFirst();
        out["history"] = history_;
    }
    const auto hostCount = s.hostLatencySamples - previous->hostLatencySamples;
    if (hostCount) out["host"] = QString::number((s.hostLatencyTenths - previous->hostLatencyTenths) / (10.0 * hostCount), 'f', 1) + "ms";
    if (s.rttMs) {
        out["rtt"] = QString::number(*s.rttMs) + "ms";
        if (s.rttVariationMs) out["jitter"] = QString::number(*s.rttVariationMs) + "ms";
    }
    return out;
}
}
