#include "runtime/deck_frame_delivery.h"
#include "runtime/deck_source_delivery_policy.h"
#include "runtime/deck_source_delivery_status.h"
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
    std::shared_ptr<DeckSourceSubmissionFence> fence = std::make_shared<DeckSourceSubmissionFence>();
    std::uint64_t observationSequence = 0;
    std::deque<Frame> frames;
    DeckFrameCadence cadence;
    DeckFrameDelivery* receiver = nullptr;
    bool closed = false, queued = false, wakeQueued = false;
    bool configured = false, started = false;
    std::uint64_t dropped = 0, presentationGeneration = 0;
    std::uint64_t mapperEpoch = 0;
    std::optional<std::uint64_t> deadlineEpoch;
    bool sourceEnabled = false;
    DeckSourceDeliveryStatus sourceStatus;
    std::int64_t sourceUpdatedNs = 0;
    std::optional<DeckSourceClockPoint> lastSourceDelivery;
    void advanceEpoch(std::uint64_t epoch) {
        fence->advanceEpoch(epoch);
        dropped += frames.size(); frames.clear(); mapperEpoch = epoch;
        deadlineEpoch.reset(); lastSourceDelivery.reset();
        sourceStatus.invalidate(observationSequence, "clock_mapping_unstable");
    }
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
    state_->fence->close();
    state_->frames.clear();
    state_->receiver = nullptr;
}

void DeckFrameDelivery::close() {
    timer_.stop(); sourceDeadline_->cancel();
    const std::lock_guard lock(state_->mutex);
    state_->closed = true;
    state_->fence->close();
    state_->frames.clear();
}

bool DeckFrameDelivery::setSourceTimingEnabled(bool enabled) {
    const std::lock_guard lock(state_->mutex);
    if (state_->closed || state_->started || (enabled && !sourceDeadline_->available())) return false;
    state_->sourceEnabled = enabled;
    state_->sourceStatus.configure(enabled);
    state_->sourceUpdatedNs = nowNs(); return true;
}
void DeckFrameDelivery::resetTiming(std::uint64_t generation) {
    const std::lock_guard lock(state_->mutex);
    // OFF keeps the legacy queue/timer lifecycle across display changes.
    if (!state_->sourceEnabled) return;
    timer_.stop(); sourceDeadline_->cancel();
    state_->dropped += state_->frames.size();
    state_->frames.clear(); state_->queued = false; state_->wakeQueued = false;
    state_->deadlineEpoch.reset(); state_->lastSourceDelivery.reset();
    state_->fence->reset(generation);
    state_->presentationGeneration = generation;
    state_->sourceStatus.invalidate(state_->observationSequence, "clock_mapping_unstable");
}

DeckFrameDeliveryStats DeckFrameDelivery::stats() const {
    const std::lock_guard lock(state_->mutex);
    const bool stale = state_->sourceEnabled && nowNs() - state_->sourceUpdatedNs > 1'000'000'000;
    const auto fence = state_->fence->stats();
    const bool rejected = state_->sourceEnabled && (fence.epoch != state_->mapperEpoch ||
        (fence.rejectedSequence && fence.rejectedSequence >= state_->sourceStatus.sequence()));
    return {state_->dropped + fence.dropped, state_->frames.size(), rejected ? "stale_epoch" : stale ? "source_timestamp_missing" : state_->sourceStatus.fallback(),
        rejected || stale ? "pending" : state_->sourceStatus.effective(), state_->mapperEpoch};
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

std::shared_ptr<DeckSourceSubmissionFence> DeckFrameDelivery::submissionFence() const { return state_->fence; }

DeckFrameDelivery::EpochPublisher DeckFrameDelivery::epochPublisher() const {
    return [state = state_](std::uint64_t epoch) {
        const std::lock_guard lock(state->mutex);
        if (!state->closed && state->sourceEnabled && epoch > state->mapperEpoch)
            state->advanceEpoch(epoch);
    };
}

DeckFrameDelivery::Publisher DeckFrameDelivery::publisher() const {
    return [state = state_](const Frame& frame) {
        const std::lock_guard lock(state->mutex);
        if (state->closed || !state->receiver) return false;
        DeckSourceSubmissionFence::Lease ticket;
        if (state->sourceEnabled) {
            ticket = state->fence->ticket(frame.timing.clock.epoch, frame.timing.presentationGeneration);
            state->observationSequence = ticket->sequence;
        }
        if (state->sourceEnabled && frame.timing.presentationGeneration != state->presentationGeneration) {
            ++state->dropped; state->sourceStatus.invalidate(ticket->sequence, "stale_epoch"); return true;
        }
        if (state->sourceEnabled) {
            state->sourceUpdatedNs = nowNs();
            const auto epoch = frame.timing.clock.epoch;
            if (epoch < state->mapperEpoch || frame.timing.clock.reason == "stale_epoch") {
                ++state->dropped; state->sourceStatus.invalidate(ticket->sequence, "stale_epoch"); return true;
            }
            if (epoch > state->mapperEpoch) {
                // This runs even for a pending warmup descriptor. The GUI may
                // still own an old timer; its callback only rechecks this queue.
                state->advanceEpoch(epoch);
            }
        }
        if (state->sourceEnabled && !frame.timing.usable()) {
            state->sourceStatus.invalidate(ticket->sequence, sourceDeliveryFallback(frame.timing.clock.reason));
        }
        const auto admittedNs = nowNs();
        const auto decodedNs = frame.timing.decodedMonoNs > 0 ? frame.timing.decodedMonoNs : admittedNs;
        if (state->sourceEnabled && frame.timing.usable() &&
            sourceHoldExceedsCeiling(frame.timing.clock.targetUs, decodedNs)) {
            ++state->dropped; state->sourceStatus.invalidate(ticket->sequence, "hold_ceiling_exceeded"); return true;
        }
        if (state->sourceEnabled && frame.timing.usable() && state->lastSourceDelivery) {
            const auto& previous = *state->lastSourceDelivery;
            const auto delta = std::uint32_t(frame.timing.clock.frame - previous.frame);
            if (frame.timing.clock.sourceUs <= previous.sourceUs || !delta || delta >= 0x80000000u) {
                ++state->dropped; state->sourceStatus.invalidate(ticket->sequence, "stale_frame"); return true;
            }
        }
        state->started = true;
        // Only an empty queue may rebuffer after underflow. A new frame must
        // not move an overdue backlog's deadline forward and preserve stale work.
        if (state->frames.empty()) state->cadence.arm(nowNs());
        state->frames.push_back(frame);
        state->frames.back().timing.decodedMonoNs = decodedNs;
        if (state->sourceEnabled) {
            auto& timing = state->frames.back().timing;
            timing.submissionFence = state->fence;
            timing.submissionTicket = std::move(ticket);
        }
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
        // A deadline belongs to the mapper epoch that armed it. A stale
        // callback can wake this function, but cannot reuse that deadline.
        if (state_->deadlineEpoch && *state_->deadlineEpoch != state_->mapperEpoch) sourceDeadline_->cancel();
        state_->deadlineEpoch.reset();
        timer_.stop(); sourceDeadline_->cancel();
        const auto sourceFrame = [this](const Frame& f) {
            return state_->sourceEnabled && f.timing.usable() &&
                f.timing.clock.epoch == state_->mapperEpoch && f.timing.presentationGeneration == state_->presentationGeneration;
        };
        if (sourceFrame(state_->frames.front())) {
            if (sourceHoldExceedsCeiling(state_->frames.front().timing.clock.targetUs,
                    state_->frames.front().timing.decodedMonoNs)) {
                state_->sourceStatus.invalidate(state_->frames.front().timing.submissionTicket->sequence, "hold_ceiling_exceeded");
                state_->frames.pop_front(); ++state_->dropped;
                state_->queued = !state_->frames.empty();
                if (state_->queued) timer_.start(0);
                return;
            }
            // Select the newest overdue frame, keeping future work in its source slot.
            while (state_->frames.size() > 1 && sourceFrame(state_->frames[1]) &&
                state_->frames[1].timing.clock.targetUs * 1000 <= now) {
                state_->frames.pop_front(); ++state_->dropped;
            }
            const auto target = state_->frames.front().timing.clock.targetUs * 1000;
            if (target > now) {
                state_->deadlineEpoch = state_->mapperEpoch;
                if (sourceDeadline_->arm(target)) return;
                // Timer provider failure never delivers early or starts a busy retry.
                state_->dropped += state_->frames.size(); state_->frames.clear(); state_->queued = false;
                state_->sourceStatus.invalidate(state_->observationSequence, "renderer_not_participating"); return;
            }
            frame = std::move(state_->frames.front()); state_->frames.pop_front();
            state_->lastSourceDelivery = frame->timing.clock;
            state_->sourceStatus.complete(frame->timing.submissionTicket->sequence, true, "none");
        } else {
            const auto delay = state_->cadence.delay(now);
            if (delay > 0) { timer_.start(int((delay + 999'999) / 1'000'000)); return; }
            if (state_->cadence.missed(now))
                while (state_->frames.size() > 1) { state_->frames.pop_front(); ++state_->dropped; }
            frame = std::move(state_->frames.front()); state_->frames.pop_front();
            state_->cadence.advance(now);
            if (state_->sourceEnabled)
                state_->sourceStatus.complete(frame->timing.submissionTicket->sequence, false,
                    sourceDeliveryFallback(frame->timing.clock.reason));
        }
        state_->queued = !state_->frames.empty();
        if (state_->queued) {
            if (sourceFrame(state_->frames.front())) {
                state_->deadlineEpoch = state_->mapperEpoch;
                if (!sourceDeadline_->arm(std::max(now + 1, state_->frames.front().timing.clock.targetUs * 1000))) {
                    state_->dropped += state_->frames.size(); state_->frames.clear(); state_->queued = false;
                    state_->sourceStatus.invalidate(state_->observationSequence, "renderer_not_participating");
                }
            } else timer_.start(int((state_->cadence.delay(now) + 999'999) / 1'000'000));
        }
    }
    if (frame && frame->timing.withCurrentSubmission([] {})) consumer_(*frame);
}

} // namespace nova::deck::runtime
