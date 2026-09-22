#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace nova::deck {
struct DeckLaunchModePolicy {
    bool known = false;
    std::string hostDefault;
    std::vector<std::string> allowed;
};

namespace polaris {
struct DeckLaunchModeOption {
    std::string value;
    bool available = false;
    bool sessionOverridable = false;
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
