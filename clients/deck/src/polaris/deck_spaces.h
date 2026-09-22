#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace nova::deck::polaris {
inline constexpr int kSpaceAppId = 1347244801;
inline constexpr std::string_view kLegacySpaceAppUuid = "706f6c61-7269-4373-8000-6d756c746973";
struct DeckSpaceGameIdentity { std::string spaceId, target; };
struct DeckSpace {
    std::string id, name, state, blockedReason;
    bool selected = false, libraryEnabled = false, canOpen = false;
    bool openable() const { return canOpen || state == "running"; }
};
struct DeckSpaces {
    bool enabled = false, available = false, canSwitch = false, desktopAllowed = false;
    std::string selectedId, unavailableReason, switchBlockedReason;
    std::vector<DeckSpace> spaces;
    const DeckSpace* selected() const;
    bool permitsSelection(std::string_view id) const;
    bool permitsPlay(std::string_view id) const;
};
bool validSpaceId(std::string_view id);
std::optional<DeckSpaceGameIdentity> spaceGameIdentity(std::string_view id);
bool isSpaceGame(std::string_view id);
// Reject duplicate keys (including escaped equivalents) before Qt can collapse
// them. Bounded depth/UTF-8 checks apply to authority-bearing Space documents.
bool uniqueJsonFields(std::string_view json, int maximumDepth = 16);
std::optional<DeckSpaces> parseSpaces(std::string_view json);
}
