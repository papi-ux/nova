#include "runtime/deck_stream_recap.h"
#include <cmath>
#include <cstdlib>
#include <iostream>
using namespace nova::deck::runtime;
namespace {
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
bool near(const QVariant& value, double expected) { return value.isValid() && std::abs(value.toDouble() - expected) < 1e-9; }
// Cumulative counters since decoder setup, as the worker publishes them once a second.
DeckHudSample sample(qint64 atMs, std::uint64_t frames, std::uint64_t generation = 1) {
    DeckHudSample s;
    s.atMs = atMs; s.width = 1280; s.height = 800; s.targetFps = 60;
    s.codec = "HEVC"; s.decoderBackend = "VA-API"; s.frameTransferPath = "DMA-BUF";
    s.incoming = frames; s.decoded = frames - frames / 100; s.bytes = frames * 40000;
    s.videoWorkSamples = frames; s.videoWorkMicros = frames * 1250; s.refused = frames / 1000;
    s.hostLatencySamples = frames; s.hostLatencyTenths = frames * 42;
    s.media = nova::deck::polaris::DeckClientMediaSample{atMs + 1, {frames + frames / 50, frames, frames / 50, generation, atMs}};
    return s;
}
QVariantMap recap(const DeckStreamRecapSpan& span, DeckStreamRecapEnd end = DeckStreamRecapEnd::EndGame) {
    return deckStreamRecap(span, end, 7, "host-a", "game-a");
}
DeckStreamRecapSpan span(std::initializer_list<DeckHudSample> samples, bool ended = true, bool current = true) {
    DeckStreamRecapSpan out;
    for (const auto& s : samples) out.accept(s);
    if (ended) out.end(current);
    return out;
}
}

int main() {
    // Known deltas: the first sample's startup counts are excluded from every rate.
    const auto continuous = span({sample(0, 90), sample(1000, 150), sample(2000, 210), sample(3000, 270)});
    const auto r = recap(continuous);
    require(r["generation"] == "7" && r["hostId"] == "host-a" && r["gameId"] == "game-a" && r["end"] == "end_game",
        "recap lost its run identity or end");
    require(r["measuredForMs"].toLongLong() == 3000 && !r["restarted"].toBool(), "measured lower bound or restart flag wrong");
    const auto rates = r["rates"].toMap();
    require(rates["spanMs"].toLongLong() == 3000 && rates["receivedFrames"].toLongLong() == 180 && near(rates["receivedFps"], 60.0),
        "received rate is not the exact first-to-last delta");
    require(rates["decodedFrames"].toLongLong() == (270 - 2) - (90 - 0) && near(rates["decodedFps"], 178.0 / 3.0),
        "decoded rate is not its own counter's delta");
    require(near(rates["receivedVideoMbps"], 180.0 * 40000 * 8 / 3e6), "received video payload rate wrong");
    require(r["framesReceived"].toLongLong() == 270 && r["refusedFrames"].toLongLong() == 0, "cumulative counts wrong");
    const auto host = r["hostProcessing"].toMap(), work = r["videoWork"].toMap();
    require(near(host["meanMs"], 4.2) && host["frames"].toLongLong() == 270, "host processing mean or coverage wrong");
    require(near(work["meanMs"], 1.25) && work["frames"].toLongLong() == 270, "video work mean or coverage wrong");
    const auto missing = r["missingFrames"].toMap();
    require(missing["lost"].toLongLong() == 5 && missing["expected"].toLongLong() == 275 && near(missing["percent"], 500.0 / 275) &&
        !missing["partial"].toBool(), "frame-sequence loss wrong");
    const auto video = r["video"].toMap();
    require(video["codec"] == "HEVC" && video["width"] == 1280 && video["height"] == 800 && video["decoder"] == "VA-API", "video format wrong");
    require(recap(continuous, DeckStreamRecapEnd::Disconnect)["end"] == "disconnect", "disconnect end lost");

    // One sample, a stop on a stale sample, or no recorded stop: no rate, counts remain.
    const auto one = recap(span({sample(0, 90)}));
    require(!one["rates"].isValid() && one["framesReceived"].toLongLong() == 90 && one["measuredForMs"].toLongLong() == 0,
        "one sample produced a rate or lost its counts");
    require(!recap(span({sample(0, 90), sample(1000, 150)}, true, false))["rates"].isValid(), "stale endpoint produced a rate");
    require(!recap(span({sample(0, 90), sample(1000, 150)}, false))["rates"].isValid(), "unended span produced a rate");

    // A decoder setup reset, a bridged gap, a sequence restart or a repeated clock: no rate.
    const auto reset = recap(span({sample(0, 900), sample(1000, 960), sample(2000, 30), sample(3000, 2000)}));
    require(!reset["rates"].isValid() && reset["restarted"].toBool() && reset["framesReceived"].toLongLong() == 2000,
        "counter reset bridged into a rate or wasn't flagged");
    auto resized = sample(2000, 210); resized.width = 1920; resized.height = 1080;
    require(!recap(span({sample(0, 90), sample(1000, 150), resized}))["rates"].isValid(), "decoder format change bridged into a rate");
    const auto gap = recap(span({sample(0, 90), sample(1000, 150), sample(4000, 330)}));
    require(!gap["rates"].isValid() && !gap["restarted"].toBool(), "stalled gap produced a rate or claimed a restart");
    const auto sequence = recap(span({sample(0, 90), sample(1000, 150, 1), sample(2000, 210, 2)}));
    require(!sequence["rates"].isValid() && sequence["missingFrames"].toMap()["partial"].toBool(),
        "sequence restart produced a rate or a whole-run loss");
    // Regular clocks and monotonic counters cannot hide a sequence epoch across absent media.
    auto absent = sample(1000, 150); absent.media.reset();
    require(recap(span({sample(0, 90), absent, sample(2000, 210)}))["rates"].isValid(),
        "an absent media reading alone broke a continuous counter span");
    auto startup = sample(0, 0); startup.media.reset();
    require(near(recap(span({startup, sample(1000, 60)}))["rates"].toMap()["receivedFps"], 60),
        "healthy startup absence invented an epoch change or a zero rate");
    const auto hiddenSequence = recap(span({sample(0, 90, 1), absent, sample(2000, 210, 2)}));
    require(!hiddenSequence["rates"].isValid() && hiddenSequence["missingFrames"].toMap()["partial"].toBool(),
        "sequence restart across absent media produced a rate");
    require(!recap(span({sample(1000, 90), sample(1000, 90)}))["rates"].isValid(), "repeated clock produced a rate");
    require(!recap(span({sample(1000, 90), sample(500, 150)}))["rates"].isValid(), "backwards clock produced a rate");

    // Unknown stays unknown: no media is not zero loss, no host latency is not 0 ms.
    auto silent = sample(1000, 150); silent.media.reset(); silent.hostLatencySamples = silent.hostLatencyTenths = 0;
    const auto unknown = recap(span({sample(0, 90), silent}));
    require(!unknown["missingFrames"].isValid() && !unknown["hostProcessing"].isValid() && unknown["videoWork"].isValid(),
        "missing readings became zero or hid a measured one");
    auto unset = sample(1000, 0); unset.width = unset.height = 0; unset.videoWorkSamples = 0;
    const auto noDecoder = recap(span({unset}));
    require(!noDecoder.isEmpty() && !noDecoder["framesReceived"].isValid() && !noDecoder["refusedFrames"].isValid() &&
        !noDecoder["video"].isValid() && !noDecoder["missingFrames"].isValid(), "counts before decoder setup were reported");

    // A recap needs its run and an accepted sample; reset leaves nothing behind.
    require(deckStreamRecap(continuous, DeckStreamRecapEnd::EndGame, 0, "host-a", "game-a").isEmpty() &&
        deckStreamRecap(continuous, DeckStreamRecapEnd::EndGame, 7, "", "game-a").isEmpty() &&
        recap(DeckStreamRecapSpan{}).isEmpty(), "unidentified recap was built");
    auto cleared = continuous; cleared.reset();
    require(cleared.empty() && recap(cleared).isEmpty(), "reset kept a retired run's samples");
    // The line follows each continuous step's received rate, in order (#423).
    const auto line = r["fpsTrail"].toList();
    require(line.size() == 3 && near(line[0], 60.0) && near(line[2], 60.0), "FPS line lost its steps");
    const auto uneven = recap(span({sample(0, 0), sample(1000, 60), sample(2000, 90)}))["fpsTrail"].toList();
    require(uneven.size() == 2 && near(uneven[0], 60.0) && near(uneven[1], 30.0), "FPS line is not per-step");
    require(recap(span({sample(0, 90), sample(1000, 150)}))["fpsTrail"].toList().isEmpty(), "one step drew a line");
    // A step the counters can't answer for stays off the line.
    const auto gapped = recap(span({sample(0, 0), sample(1000, 60), sample(9000, 500), sample(10000, 560)}))["fpsTrail"].toList();
    require(gapped.size() == 2 && near(gapped[0], 60.0) && near(gapped[1], 60.0), "a long gap reached the FPS line");
    // However long the stream, the line stays bounded and keeps its shape.
    DeckStreamRecapSpan longRun; std::uint64_t counted = 0;
    for (int i = 0; i < 10000; ++i) { counted += i < 5000 ? 60 : 30; longRun.accept(sample(i * 1000, counted)); }
    longRun.end(true);
    const auto bounded = recap(longRun)["fpsTrail"].toList();
    require(bounded.size() >= 60 && bounded.size() <= 121, "FPS line was not bounded");
    require(near(bounded.first(), 60.0) && near(bounded.last(), 30.0), "FPS line lost its shape in the averaging");
    std::cout << "stream recap deltas, coverage, unknowns and continuity fencing checks passed\n";
}
