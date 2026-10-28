#pragma once

#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace nova::deck {
struct DeckLaunchModePolicy {
    bool known = false;
    std::string hostDefault;
    std::vector<std::string> allowed;
    bool followsHostDefault = true;
    bool defaultAvailable = true;
    std::string unavailableReason;
    // The host's own name for each catalog mode it named. A mode it did not name keeps Nova's.
    std::vector<std::pair<std::string, std::string>> labels;
};

namespace polaris {
struct DeckLaunchModeOption {
    std::string value;
    bool available = false;
    bool sessionOverridable = false;
    // The host's name for this mode, or empty when it gave none Deck can show.
    std::string label;
};
struct DeckLaunchModeCatalog {
    std::string desired;
    std::string effective;
    std::vector<DeckLaunchModeOption> modes;
};
std::string normalizeLaunchMode(std::string_view value);
bool isSessionLaunchMode(std::string_view value);
} // namespace polaris
} // namespace nova::deck
