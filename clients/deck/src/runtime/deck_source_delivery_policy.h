#pragma once
#include <cstdint>
#include <string_view>
#include <initializer_list>

namespace nova::deck::runtime {
// Final decoded-frame admission bound; independent of future observations.
inline constexpr std::int64_t sourceReserveMaximumUs = 12'000;
inline constexpr std::int64_t sourceMaximumMappingErrorUs = 5'000;
inline constexpr std::int64_t sourceHoldCeilingUs = sourceReserveMaximumUs + sourceMaximumMappingErrorUs;
inline bool sourceHoldExceedsCeiling(std::int64_t targetUs, std::int64_t decodedNs) {
    return targetUs > decodedNs / 1000 && targetUs - decodedNs / 1000 > sourceHoldCeilingUs;
}
inline std::string_view sourceDeliveryFallback(std::string_view reason) {
    for (const auto known : {"renderer_not_participating", "pacing_mode_incompatible", "source_timestamp_missing",
            "source_discontinuity", "timing_metadata_missing", "clock_mapping_unstable", "buffer_limit",
            "decoder_pressure", "hold_ceiling_exceeded", "stale_epoch", "stale_frame"})
        if (reason == known) return known;
    return "clock_mapping_unstable";
}
}
