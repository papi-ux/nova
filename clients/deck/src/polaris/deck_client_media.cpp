#include "polaris/deck_client_media.h"
#include <QJsonDocument>
#include <QJsonObject>
#include <chrono>
namespace nova::deck::polaris {
namespace { constexpr std::uint64_t maxJsonInteger = 9007199254740991ULL; }
qint64 clientMediaMonotonicMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
bool validClientMedia(const DeckClientMediaSample& sample) {
    const auto& c = sample.counts;
    return sample.atMs > 0 && sample.atMs <= qint64(maxJsonInteger) && c.lastFrameAtMs > 0 && c.lastFrameAtMs <= sample.atMs &&
        c.decoderGeneration > 0 && c.decoderGeneration <= maxJsonInteger && c.received > 0 &&
        c.expected <= maxJsonInteger && c.received <= c.expected && c.lost == c.expected - c.received;
}
bool freshClientMedia(const DeckClientMediaSample& sample, const qint64 nowMs) {
    return validClientMedia(sample) && nowMs >= sample.atMs && nowMs - sample.atMs <= 2500 && nowMs - sample.counts.lastFrameAtMs <= 2500;
}
bool clientMediaEpochReady(const DeckClientMediaSample& sample, const std::optional<DeckClientMediaSample>& previousAttempt) {
    // Polaris raw-counter ingest ignores decoder_generation and resets its
    // baseline on a client timestamp gap > 5 seconds. Never subtract across a
    // new decoder/sequence epoch, even if its new totals exceed the old totals.
    return !previousAttempt || sample.counts.decoderGeneration == previousAttempt->counts.decoderGeneration ||
        sample.atMs - previousAttempt->atMs > 5000;
}
std::optional<QByteArray> clientMediaBody(const DeckClientMediaSample& sample, const DeckClientMediaScope& scope) {
    if (!validClientMedia(sample) || scope.generation <= 0 || scope.generation > qint64(maxJsonInteger) ||
        scope.appSession.isEmpty() || scope.appSession.size() > 512 || scope.appSession.trimmed() != scope.appSession) return {};
    for (auto c : scope.appSession) if (c.unicode() < 32 || c.unicode() == 127) return {};
    const auto& c = sample.counts;
    return QJsonDocument(QJsonObject{{"app_session_id", scope.appSession}, {"session_generation", scope.generation},
        {"sample", QJsonObject{{"monotonic_timestamp_ms", sample.atMs}, {"decoder_generation", qint64(c.decoderGeneration)},
            {"frames_expected", qint64(c.expected)}, {"frames_received", qint64(c.received)}, {"frames_lost", qint64(c.lost)}}}}).toJson(QJsonDocument::Compact);
}
}
