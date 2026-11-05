#include "runtime/deck_frame_delivery.h"
#include <mutex>
#include <optional>
#include <deque>
#include <chrono>
#ifdef __linux__
#include <time.h>
#endif

namespace nova::deck::runtime {
namespace {
std::int64_t nowNs() {
#ifdef __linux__
    timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC, &now) == 0) return std::int64_t(now.tv_sec) * 1'000'000'000 + now.tv_nsec;
#endif
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
}

struct DeckFrameDelivery::State {
    std::mutex mutex;
    std::deque<Frame> frames;
    DeckFrameCadence cadence;
    DeckFrameDelivery* receiver = nullptr;
    bool closed = false, queued = false, wakeQueued = false;
    bool configured = false, started = false;
    std::uint64_t dropped = 0, presentationGeneration = 0;
    bool sourceEnabled = false;
    std::int64_t lastSourceDeliveryNs = -1;
};

DeckFrameDelivery::DeckFrameDelivery(Consumer consumer, QObject* parent)
    : QObject(parent), state_(std::make_shared<State>()), consumer_(std::move(consumer)) {
    state_->receiver = this;
    sourceDeadline_ = std::make_unique<DeckPresentationDeadline>([this] { deliver(); }, this);
    timer_.setSingleShot(true);
    timer_.setTimerType(Qt::PreciseTimer);
    connect(&timer_, &QTimer::timeout, this, &DeckFrameDelivery::deliver);
}

DeckFrameDelivery::~DeckFrameDelivery() {
    sourceDeadline_->cancel();
    const std::lock_guard lock(state_->mutex);
    state_->closed = true;
    state_->frames.clear();
    state_->receiver = nullptr;
}

void DeckFrameDelivery::close() {
    timer_.stop(); sourceDeadline_->cancel();
    const std::lock_guard lock(state_->mutex);
    state_->closed = true;
    state_->frames.clear();
}

bool DeckFrameDelivery::setSourceTimingEnabled(bool enabled) {
    const std::lock_guard lock(state_->mutex);
    if (state_->closed || state_->started || (enabled && !sourceDeadline_->available())) return false;
    state_->sourceEnabled = enabled; return true;
}
void DeckFrameDelivery::resetTiming(std::uint64_t generation) {
    timer_.stop(); sourceDeadline_->cancel();
    const std::lock_guard lock(state_->mutex);
    state_->frames.clear(); state_->queued = false; state_->wakeQueued = false;
    state_->presentationGeneration = generation; state_->lastSourceDeliveryNs = -1;
}

DeckFrameDeliveryStats DeckFrameDelivery::stats() const {
    const std::lock_guard lock(state_->mutex);
    return {state_->dropped, state_->frames.size()};
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
        if (frame.timing.presentationGeneration < state->presentationGeneration) { ++state->dropped; return true; }
        if (state->sourceEnabled && frame.timing.usable() &&
            frame.timing.clock.targetUs * 1000 <= state->lastSourceDeliveryNs) { ++state->dropped; return true; }
        state->started = true;
        // Only an empty queue may rebuffer after underflow. A new frame must
        // not move an overdue backlog's deadline forward and preserve stale work.
        if (state->frames.empty()) state->cadence.arm(nowNs());
        state->frames.push_back(frame);
        while (state->frames.size() > state->cadence.capacity()) {
            state->frames.pop_front(); ++state->dropped;
        }
        if (state->queued) {
            if (state->sourceEnabled && !state->wakeQueued) {
                auto* receiver = state->receiver;
                state->wakeQueued = true;
                if (!QMetaObject::invokeMethod(receiver, [receiver] { receiver->deliver(); }, Qt::QueuedConnection)) {
                    state->wakeQueued = state->queued = false; state->frames.clear(); return false;
                }
            }
            return true;
        }
        state->queued = state->wakeQueued = true;
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
        state_->wakeQueued = false;
        if (state_->closed || state_->frames.empty()) { state_->queued = false; return; }
        const auto now = nowNs();
        timer_.stop(); sourceDeadline_->cancel();
        const auto sourceFrame = [this](const Frame& f) {
            return state_->sourceEnabled && f.timing.usable() && f.timing.presentationGeneration == state_->presentationGeneration;
        };
        if (sourceFrame(state_->frames.front())) {
            // Select the newest overdue frame, keeping future work in its source slot.
            while (state_->frames.size() > 1 && sourceFrame(state_->frames[1]) &&
                state_->frames[1].timing.clock.targetUs * 1000 <= now) {
                state_->frames.pop_front(); ++state_->dropped;
            }
            const auto target = state_->frames.front().timing.clock.targetUs * 1000;
            if (target > now) {
                if (sourceDeadline_->arm(target)) return;
                // Timer provider failure never delivers early or starts a busy retry.
                state_->frames.clear(); state_->queued = false; ++state_->dropped; return;
            }
            frame = std::move(state_->frames.front()); state_->frames.pop_front();
            state_->lastSourceDeliveryNs = now;
        } else {
            const auto delay = state_->cadence.delay(now);
            if (delay > 0) { timer_.start(int((delay + 999'999) / 1'000'000)); return; }
            if (state_->cadence.missed(now))
                while (state_->frames.size() > 1) { state_->frames.pop_front(); ++state_->dropped; }
            frame = std::move(state_->frames.front()); state_->frames.pop_front();
            state_->cadence.advance(now);
        }
        state_->queued = !state_->frames.empty();
        if (state_->queued) {
            if (sourceFrame(state_->frames.front())) {
                if (!sourceDeadline_->arm(std::max(now + 1, state_->frames.front().timing.clock.targetUs * 1000))) {
                    state_->frames.clear(); state_->queued = false; ++state_->dropped;
                }
            } else timer_.start(int((state_->cadence.delay(now) + 999'999) / 1'000'000));
        }
    }
    if (frame) consumer_(*frame);
}

} // namespace nova::deck::runtime
