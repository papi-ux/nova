#pragma once

#include "stream/deck_stream_media_adapters.h"
#include "runtime/deck_frame_pacing.h"
#include <QObject>
#include <QTimer>
#include "runtime/deck_presentation_deadline.h"
#include <functional>
#include <memory>
#include <cstdint>
#include <cstddef>
#include <string_view>

namespace nova::deck::runtime {

struct DeckFrameDeliveryStats {
    std::uint64_t dropped = 0;
    std::size_t queued = 0;
    std::string_view fallback = "not_requested", effective = "legacy";
    std::uint64_t mapperEpoch = 0;
};

// Latency mode holds the newest frame; Balanced holds at most two and spaces
// delivery at the admitted stream rate. One GUI notification/timer is outstanding.
// Publishers can outlive the receiver; close/destruction release pending leases.
class DeckFrameDelivery final : public QObject {
public:
    using Frame = stream::DeckQrhiVaapiPresentationDescriptor;
    using Consumer = std::function<void(const Frame&)>;
    using Publisher = std::function<bool(const Frame&)>;
    using EpochPublisher = std::function<void(std::uint64_t)>;
    using Configure = std::function<bool(DeckFramePacing, int)>;
    explicit DeckFrameDelivery(Consumer consumer, QObject* parent = nullptr);
    ~DeckFrameDelivery() override;
    Publisher publisher() const;
    // Decoder-owner notification, before waiting for new-epoch output.
    EpochPublisher epochPublisher() const;
    // Worker-safe, once before the first decoded frame, with the admitted FPS.
    Configure configurator() const;
    DeckFrameDeliveryStats stats() const;
    void close();
    // Defaults off (ROOT D4); configure once from the stream preference.
    bool setSourceTimingEnabled(bool enabled);
    void resetTiming(std::uint64_t presentationGeneration);
private:
    struct State;
    void deliver();
    std::shared_ptr<State> state_;
    Consumer consumer_;
    QTimer timer_;
    std::unique_ptr<DeckPresentationDeadline> sourceDeadline_;
};

} // namespace nova::deck::runtime
