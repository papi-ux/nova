#include "runtime/deck_hud_metrics.h"
#include <cstdlib>
#include <iostream>
using namespace nova::deck::runtime;
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
int main() {
    DeckHudMetrics metrics;
    DeckHudSample s;
    s.width = 1280; s.height = 800; s.targetFps = 90; s.codec = "H.264"; s.compositionAvailable = true;
    require(metrics.sample(s).value("fps") == "--", "target FPS became an observed rate");
    s.atMs = 1000; s.incoming = 60; s.decoded = 58; s.composed = 55; s.bytes = 2500000;
    s.rttMs = 12; s.rttVariationMs = 2; s.hostLatencyTenths = 120; s.hostLatencySamples = 6;
    auto view = metrics.sample(s);
    require(view.value("fps") == "55.0" && view.value("incoming") == "60.0" && view.value("decoded") == "58.0", "distinct frame stages collapsed");
    require(view.value("bitrate") == "20.0M" && view.value("host") == "2.0ms" && view.value("rtt") == "12ms", "wrong sample units");
    require(!view.contains("loss") && !view.contains("lowOnePercent"), "unsupported loss or one-percent-low was invented");
    s.atMs = 2000; s.rttMs.reset(); s.rttVariationMs = 8;
    view = metrics.sample(s);
    require(view.value("fps") == "0.0" && view.value("host") == "--" && view.value("rtt") == "--" && view.value("jitter") == "--", "stalled video or missing telemetry kept stale values");
    s.atMs = 8000;
    require(metrics.sample(s).value("fps") == "--", "long gap became a valid average");
    s.atMs += 1000; s.composed = 0;
    require(metrics.sample(s).value("history").toList().isEmpty(), "surface counter reset retained old sparkline");
    s.atMs += 1000; s.compositionAvailable = false;
    metrics.sample(s); s.atMs += 1000;
    require(metrics.sample(s).value("fps") == "--", "missing surface became zero display FPS");
    metrics.reset(); s = {};
    require(metrics.sample(s).value("codec") == "--", "reconnect retained the old stream codec");
    s.compositionAvailable = true; metrics.sample(s);
    for (int i = 1; i <= 80; ++i) { s.atMs = i * 1000; s.composed += 60; view = metrics.sample(s); }
    require(view.value("history").toList().size() == 60, "sparkline memory grew without bound");
    std::cout << "HUD metrics passed: provenance, cadence, gaps, resets, unavailable values and bounded history\n";
}
