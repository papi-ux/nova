#pragma once
#include <cstdint>

namespace nova::deck::runtime {
// Final decoded-frame admission bound; independent of future observations.
inline constexpr std::int64_t sourceReserveMaximumUs = 12'000;
inline constexpr std::int64_t sourceMaximumMappingErrorUs = 5'000;
inline constexpr std::int64_t sourceHoldCeilingUs = sourceReserveMaximumUs + sourceMaximumMappingErrorUs;
inline bool sourceHoldExceedsCeiling(std::int64_t targetUs, std::int64_t decodedNs) {
    return targetUs > decodedNs / 1000 && targetUs - decodedNs / 1000 > sourceHoldCeilingUs;
}
}
