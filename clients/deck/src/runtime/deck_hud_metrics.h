#pragma once
#include <QVariantMap>
#include "polaris/deck_client_media.h"
#include <cstdint>
#include <optional>

namespace nova::deck::runtime {
// Counters are per connection. No endpoint, game identity or host tokens enter
// the HUD. Composed frames are successful scenegraph draws, not panel flips.
struct DeckHudSample {
    qint64 atMs = 0;
    std::uint64_t incoming = 0, bytes = 0, decoded = 0, composed = 0, submitted = 0, cpuUploadCompositions = 0;
    std::uint64_t videoWorkMicros = 0, videoWorkSamples = 0, refused = 0;
    std::uint64_t hostLatencyTenths = 0, hostLatencySamples = 0;
    int width = 0, height = 0, targetFps = 0;
    QString codec, decoderBackend, frameTransferPath;
    bool cpuUploadsAvailable = false;
    std::uint64_t deliveryDrops = 0;
    std::size_t deliveryQueueDepth = 0;
    bool deliveryAvailable = false;
    std::optional<polaris::DeckClientMediaSample> media;
    bool compositionAvailable = false;
    std::optional<unsigned> rttMs, rttVariationMs;
};
class DeckHudMetrics {
public:
    static QVariantMap empty();
    QVariantMap sample(const DeckHudSample& sample);
    void reset() { previous_.reset(); history_.clear(); }
private:
    std::optional<DeckHudSample> previous_;
    QVariantList history_;
};
}
