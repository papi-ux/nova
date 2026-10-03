#pragma once
#include <QByteArray>
#include <QString>
#include <cstdint>
#include <optional>
namespace nova::deck::polaris {
struct DeckMediaCounts {
    std::uint64_t expected = 0, received = 0, lost = 0, decoderGeneration = 0;
    qint64 lastFrameAtMs = 0;
};
struct DeckClientMediaSample { qint64 atMs = 0; DeckMediaCounts counts; };
struct DeckClientMediaScope {
    QString appSession;
    qint64 generation = 0;
    bool operator==(const DeckClientMediaScope&) const = default;
};
qint64 clientMediaMonotonicMs();
bool validClientMedia(const DeckClientMediaSample& sample);
bool freshClientMedia(const DeckClientMediaSample& sample, qint64 nowMs);
bool clientMediaEpochReady(const DeckClientMediaSample& sample, const std::optional<DeckClientMediaSample>& previousAttempt);
std::optional<QByteArray> clientMediaBody(const DeckClientMediaSample& sample, const DeckClientMediaScope& scope);
}
