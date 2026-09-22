#pragma once

#include <cstdint>
#include <optional>
#include <string>

namespace nova::deck {
// Optional launcher/catalogue measurements, never inferred from session length.
// A known zero playtime is distinct from an absent launcher measurement.
struct DeckGameTime {
    std::optional<std::int64_t> playedSeconds;
    std::string playSource;
    std::optional<std::int64_t> mainSeconds;
    std::optional<std::int64_t> extrasSeconds;
    std::optional<std::int64_t> completionistSeconds;
    std::string matchedName;
};
}
