#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace nova::deck {

struct PolarisGameFixture;

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

struct DeckHostDetail {
    std::string_view id;
    std::string_view displayName;
    std::string_view statusLabel;
    std::string_view subtitle;
};

struct DeckLaunchCta {
    std::string_view id;
    std::string_view label;
    std::string_view helpText;
    std::string previewStateLabel;
    std::string previewText;
    bool enabled;
};

struct DeckLaunchIntent {
    std::string targetHostId;
    std::string targetHostName;
    std::string sampleGameId;
    std::string gameTitle;
    bool executable = false;
    std::string safetyLabel;
};

struct DeckLaunchPreview {
    std::string text;
    std::string stateLabel;
    bool copyOnly = true;
    bool executable = false;
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

DeckHostDetail resolveHostDetail(const std::vector<DeckHostListItem>& hosts, std::string_view hostId);

DeckLaunchIntent resolveLaunchIntent(const DeckHostDetail& detail, const PolarisGameFixture& game);

DeckLaunchPreview fakeLaunchCommandPreviewFor(const DeckLaunchIntent& intent);

DeckLaunchCta inertLaunchCtaFor(const DeckHostDetail& detail);

std::vector<DeckFocusTarget> hostDetailFocusTargets(const DeckHostDetail& detail, const DeckLaunchCta& launchCta);

std::string_view nextHostDetailFocusTarget(
    const std::vector<DeckFocusTarget>& targets,
    std::string_view currentId,
    DeckFocusDirection direction);

bool isDeckNativeAspect(int width, int height);

} // namespace nova::deck
