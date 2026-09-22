#pragma once

#include "stream/deck_stream_media_adapters.h"
#include "runtime/deck_frame_pacing.h"
#include <QObject>
#include <QTimer>
#include <functional>
#include <memory>

namespace nova::deck::runtime {

// Latency mode holds the newest frame; Balanced holds at most two and spaces
// delivery at the admitted stream rate. One GUI notification/timer is outstanding.
// Publishers can outlive the receiver; close/destruction release pending leases.
class DeckFrameDelivery final : public QObject {
public:
    using Frame = stream::DeckQrhiVaapiPresentationDescriptor;
    using Consumer = std::function<void(const Frame&)>;
    using Publisher = std::function<bool(const Frame&)>;
    using Configure = std::function<bool(DeckFramePacing, int)>;
    explicit DeckFrameDelivery(Consumer consumer, QObject* parent = nullptr);
    ~DeckFrameDelivery() override;
    Publisher publisher() const;
    // Worker-safe, once before the first decoded frame, with the admitted FPS.
    Configure configurator() const;
    void close();
private:
    struct State;
    void deliver();
    std::shared_ptr<State> state_;
    Consumer consumer_;
    QTimer timer_;
};

} // namespace nova::deck::runtime
