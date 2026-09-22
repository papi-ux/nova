#include "runtime/deck_frame_delivery.h"
#include <mutex>
#include <optional>
#include <deque>
#include <chrono>

namespace nova::deck::runtime {
namespace {
std::int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
}

struct DeckFrameDelivery::State {
    std::mutex mutex;
    std::deque<Frame> frames;
    DeckFrameCadence cadence;
    DeckFrameDelivery* receiver = nullptr;
    bool closed = false, queued = false;
    bool configured = false, started = false;
};

DeckFrameDelivery::DeckFrameDelivery(Consumer consumer, QObject* parent)
    : QObject(parent), state_(std::make_shared<State>()), consumer_(std::move(consumer)) {
    state_->receiver = this;
    timer_.setSingleShot(true);
    timer_.setTimerType(Qt::PreciseTimer);
    connect(&timer_, &QTimer::timeout, this, &DeckFrameDelivery::deliver);
}

DeckFrameDelivery::~DeckFrameDelivery() {
    const std::lock_guard lock(state_->mutex);
    state_->closed = true;
    state_->frames.clear();
    state_->receiver = nullptr;
}

void DeckFrameDelivery::close() {
    const std::lock_guard lock(state_->mutex);
    state_->closed = true;
    state_->frames.clear();
}

DeckFrameDelivery::Configure DeckFrameDelivery::configurator() const {
    return [state = state_](DeckFramePacing mode, int fps) {
        const std::lock_guard lock(state->mutex);
        if (state->closed || !state->receiver || state->configured || state->started) return false;
        if (!state->cadence.configure(mode, fps)) return false;
        state->configured = true;
        return true;
    };
}

DeckFrameDelivery::Publisher DeckFrameDelivery::publisher() const {
    return [state = state_](const Frame& frame) {
        const std::lock_guard lock(state->mutex);
        if (state->closed || !state->receiver) return false;
        state->started = true;
        // Only an empty queue may rebuffer after underflow. A new frame must
        // not move an overdue backlog's deadline forward and preserve stale work.
        if (state->frames.empty()) state->cadence.arm(nowNs());
        state->frames.push_back(frame);
        while (state->frames.size() > state->cadence.capacity()) state->frames.pop_front();
        if (state->queued) return true;
        state->queued = true;
        auto* receiver = state->receiver;
        // Posting under the lock prevents racing receiver destruction. The
        // explicitly queued callback never runs the consumer on the producer.
        if (!QMetaObject::invokeMethod(receiver, [receiver] { receiver->deliver(); }, Qt::QueuedConnection)) {
            state->queued = false;
            state->frames.clear();
            return false;
        }
        return true;
    };
}

void DeckFrameDelivery::deliver() {
    std::optional<Frame> frame;
    {
        const std::lock_guard lock(state_->mutex);
        if (state_->closed || state_->frames.empty()) { state_->queued = false; return; }
        const auto now = nowNs();
        const auto delay = state_->cadence.delay(now);
        if (delay > 0) {
            timer_.start(int((delay + 999'999) / 1'000'000));
            return;
        }
        if (state_->cadence.missed(now))
            while (state_->frames.size() > 1) state_->frames.pop_front();
        frame = std::move(state_->frames.front()); state_->frames.pop_front();
        state_->cadence.advance(now);
        state_->queued = !state_->frames.empty();
        if (state_->queued) timer_.start(int((state_->cadence.delay(now) + 999'999) / 1'000'000));
    }
    if (frame) consumer_(*frame);
}

} // namespace nova::deck::runtime
