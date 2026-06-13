#pragma once

#include <string_view>
#include <vector>

namespace nova::deck {

struct DeckWindowProfile {
    int width;
    int height;
    bool fullscreenPreferred;
    std::string_view shellName;
};

struct DeckFocusTarget {
    std::string_view id;
    std::string_view label;
    int row;
    int column;
    bool initialFocus;
};

struct DeckHostListItem {
    std::string_view id;
    std::string_view displayName;
    std::string_view statusLabel;
    int row;
    bool initialFocus;
};

enum class DeckFocusDirection {
    Left,
    Right,
    Up,
    Down,
};

DeckWindowProfile defaultWindowProfile();

std::vector<DeckFocusTarget> defaultLibraryFocusTargets();

std::string_view initialLibraryFocusTarget(const std::vector<DeckFocusTarget>& targets);

std::string_view nextLibraryFocusTarget(
    const std::vector<DeckFocusTarget>& targets,
    std::string_view currentId,
    DeckFocusDirection direction);

std::vector<DeckHostListItem> emptyHostListState();

std::vector<DeckHostListItem> demoHostListState();

std::string_view initialHostFocusTarget(const std::vector<DeckHostListItem>& hosts);

std::string_view nextHostFocusTarget(
    const std::vector<DeckHostListItem>& hosts,
    std::string_view currentId,
    DeckFocusDirection direction);

bool isDeckNativeAspect(int width, int height);

} // namespace nova::deck
