#pragma once
#include <cstdint>
#include <string_view>
namespace nova::deck::runtime {
// Ticket sequence is decoded admission chronology, including untimed output
// whose frame ID/PTS is unavailable. Copies keep the same identity at completion.
// Owner serializes this reducer with the delivery mutex.
class DeckSourceDeliveryStatus {
public:
    void configure(bool enabled) {
        effective_ = enabled ? "pending" : "legacy";
        fallback_ = enabled ? "clock_mapping_unstable" : "not_requested";
    }
    void invalidate(std::uint64_t sequence, std::string_view reason) {
        commit(sequence, reason == "renderer_not_participating" ? "unavailable" : "pending", reason);
    }
    void complete(std::uint64_t sequence, bool sourceTimed, std::string_view reason) {
        if (sourceTimed) commit(sequence, "source_time_fixed", "none");
        else invalidate(sequence, reason);
    }
    std::uint64_t sequence() const { return sequence_; }
    std::string_view effective() const { return effective_; }
    std::string_view fallback() const { return fallback_; }
private:
    void commit(std::uint64_t sequence, std::string_view effective, std::string_view fallback) {
        if (sequence < sequence_) return;
        sequence_ = sequence; effective_ = effective; fallback_ = fallback;
    }
    std::uint64_t sequence_ = 0;
    std::string_view effective_ = "legacy", fallback_ = "not_requested";
};
}
