#pragma once
#include <algorithm>
#include <cstdint>
#include <memory>
#include <mutex>
#include <utility>

namespace nova::deck::runtime {
// One authority from mapper mutation through the last unsent render job.
// Lock order: decoder lifecycle -> delivery -> fence, or lifecycle -> fence.
// A fenced callback never enters delivery, the decoder, or a GUI synchronization.
class DeckSourceSubmissionFence {
public:
    struct Ticket {
        std::uint64_t epoch, generation, sequence;
        bool rejected = false, submitted = false; // fence mutex; shared by mailbox/job copies
    };
    using Lease = std::shared_ptr<Ticket>;
    struct Stats { std::uint64_t dropped, rejectedSequence, epoch; };
    template<class Update> void updateEpoch(Update&& update) {
        const std::lock_guard lock(mutex_);
        epoch_ = std::max(epoch_, update(epoch_));
    }
    void advanceEpoch(std::uint64_t epoch) {
        updateEpoch([epoch](auto) { return epoch; });
    }
    void reset(std::uint64_t generation) {
        const std::lock_guard lock(mutex_); generation_ = generation;
    }
    void close() { const std::lock_guard lock(mutex_); closed_ = true; }
    Lease ticket(std::uint64_t epoch, std::uint64_t generation) {
        const std::lock_guard lock(mutex_);
        return std::make_shared<Ticket>(Ticket{epoch, generation, ++sequence_});
    }
    template<class Submit> bool whileCurrent(const Lease& ticket, Submit&& submit) {
        const std::lock_guard lock(mutex_);
        if (!ticket || closed_ || ticket->epoch != epoch_ || ticket->generation != generation_) {
            if (ticket && !ticket->rejected) {
                ticket->rejected = true;
                if (!ticket->submitted) {
                    ++dropped_;
                    rejectedSequence_ = std::max(rejectedSequence_, ticket->sequence);
                }
            }
            return false;
        }
        std::forward<Submit>(submit)();
        return true;
    }
    bool wasRejected(const Lease& ticket) const {
        const std::lock_guard lock(mutex_); return ticket && ticket->rejected;
    }
    Stats stats() const {
        const std::lock_guard lock(mutex_); return {dropped_, rejectedSequence_, epoch_};
    }
private:
    mutable std::mutex mutex_;
    std::uint64_t epoch_ = 0, generation_ = 0, sequence_ = 0, dropped_ = 0, rejectedSequence_ = 0;
    bool closed_ = false;
};
}
