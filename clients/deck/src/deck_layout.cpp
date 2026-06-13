#include "deck_layout.h"
#include "polaris_game_fixture.h"

#include <algorithm>
#include <cctype>
#include <string>

namespace nova::deck {
namespace {

constexpr std::string_view kPreviewStateLabel = "Preview only — not executable";

std::string encodePreviewComponent(const std::string& value) {
    std::string encoded;
    for (const unsigned char ch : value) {
        if (std::isalnum(ch) || ch == char(45) || ch == char(95)) {
            encoded.push_back(static_cast<char>(ch));
        } else if (ch == char(32)) {
            encoded += "%20";
        }
    }
    return encoded;
}

} // namespace


DeckWindowProfile defaultWindowProfile() {
    return DeckWindowProfile{
        .width = 1280,
        .height = 800,
        .fullscreenPreferred = true,
        .shellName = "Nova Deck",
    };
}

std::vector<DeckFocusTarget> defaultLibraryFocusTargets() {
    return {
        DeckFocusTarget{
            .id = "sample-game-card",
            .label = "Shared Polaris DTO fixture",
            .row = 0,
            .column = 0,
            .initialFocus = true,
        },
        DeckFocusTarget{
            .id = "details-placeholder",
            .label = "Controller placeholder scope",
            .row = 0,
            .column = 1,
            .initialFocus = false,
        },
    };
}

std::string_view initialLibraryFocusTarget(const std::vector<DeckFocusTarget>& targets) {
    const auto initial = std::ranges::find_if(targets, [](const DeckFocusTarget& target) {
        return target.initialFocus;
    });

    if (initial != targets.end()) {
        return initial->id;
    }

    if (!targets.empty()) {
        return targets.front().id;
    }

    return {};
}

std::string_view nextLibraryFocusTarget(
    const std::vector<DeckFocusTarget>& targets,
    const std::string_view currentId,
    const DeckFocusDirection direction) {
    const auto current = std::ranges::find_if(targets, [currentId](const DeckFocusTarget& target) {
        return target.id == currentId;
    });

    if (current == targets.end()) {
        return initialLibraryFocusTarget(targets);
    }

    const int horizontalDelta = direction == DeckFocusDirection::Left ? -1 : direction == DeckFocusDirection::Right ? 1 : 0;
    const int verticalDelta = direction == DeckFocusDirection::Up ? -1 : direction == DeckFocusDirection::Down ? 1 : 0;

    const auto next = std::ranges::find_if(targets, [&](const DeckFocusTarget& target) {
        return target.row == current->row + verticalDelta && target.column == current->column + horizontalDelta;
    });

    if (next != targets.end()) {
        return next->id;
    }

    return current->id;
}

std::vector<DeckHostListItem> emptyHostListState() {
    return {};
}

std::vector<DeckHostListItem> demoHostListState() {
    return {
        DeckHostListItem{
            .id = "host-gaming-pc",
            .displayName = "Gaming PC",
            .statusLabel = "Ready for local demo",
            .row = 0,
            .initialFocus = true,
        },
        DeckHostListItem{
            .id = "host-living-room-pc",
            .displayName = "Living Room PC",
            .statusLabel = "Ready for local demo",
            .row = 1,
            .initialFocus = false,
        },
    };
}

std::string_view initialHostFocusTarget(const std::vector<DeckHostListItem>& hosts) {
    const auto initial = std::ranges::find_if(hosts, [](const DeckHostListItem& host) {
        return host.initialFocus;
    });

    if (initial != hosts.end()) {
        return initial->id;
    }

    if (!hosts.empty()) {
        return hosts.front().id;
    }

    return "host-empty-state";
}

std::string_view nextHostFocusTarget(
    const std::vector<DeckHostListItem>& hosts,
    const std::string_view currentId,
    const DeckFocusDirection direction) {
    if (hosts.empty()) {
        return "host-empty-state";
    }

    const auto current = std::ranges::find_if(hosts, [currentId](const DeckHostListItem& host) {
        return host.id == currentId;
    });

    if (current == hosts.end()) {
        return initialHostFocusTarget(hosts);
    }

    const int verticalDelta = direction == DeckFocusDirection::Up ? -1 : direction == DeckFocusDirection::Down ? 1 : 0;
    if (verticalDelta == 0) {
        return current->id;
    }

    const auto next = std::ranges::find_if(hosts, [&](const DeckHostListItem& host) {
        return host.row == current->row + verticalDelta;
    });

    if (next != hosts.end()) {
        return next->id;
    }

    return current->id;
}

DeckHostDetail resolveHostDetail(const std::vector<DeckHostListItem>& hosts, const std::string_view hostId) {
    const auto host = std::ranges::find_if(hosts, [hostId](const DeckHostListItem& item) {
        return item.id == hostId;
    });

    if (host == hosts.end()) {
        return DeckHostDetail{
            .id = "host-detail-empty",
            .displayName = "No demo host selected",
            .statusLabel = "Select a demo host",
            .subtitle = "Demo host detail only — not discovered from the network.",
        };
    }

    return DeckHostDetail{
        .id = host->id,
        .displayName = host->displayName,
        .statusLabel = host->statusLabel,
        .subtitle = "Demo host detail only — not discovered from the network.",
    };
}

DeckLaunchIntent resolveLaunchIntent(const DeckHostDetail& detail, const PolarisGameFixture& game) {
    return DeckLaunchIntent{
        .targetHostId = std::string(detail.id),
        .targetHostName = std::string(detail.displayName),
        .sampleGameId = game.id,
        .gameTitle = game.name,
        .executable = false,
        .safetyLabel = std::string(kPreviewStateLabel),
    };
}

DeckLaunchPreview fakeLaunchCommandPreviewFor(const DeckLaunchIntent& intent) {
    return DeckLaunchPreview{
        .text = "preview://nova-deck/launch?host=" + encodePreviewComponent(intent.targetHostId)
            + "&game=" + encodePreviewComponent(intent.gameTitle)
            + "&state=copy-preview-only",
        .stateLabel = std::string(kPreviewStateLabel),
        .copyOnly = true,
        .executable = false,
    };
}

DeckLaunchPreviewCopyAction copyLaunchPreviewActionFor(const DeckLaunchPreview& preview) {
    const bool hasPreviewText = !preview.text.empty();
    return DeckLaunchPreviewCopyAction{
        .id = "host-detail-copy-preview",
        .label = "Copy preview text",
        .previewText = preview.text,
        .statusLabel = hasPreviewText
            ? "Preview copied for inspection only — copy-only, not executable."
            : "No preview text to copy — preview-only action stayed inert.",
        .enabled = hasPreviewText,
        .copyOnly = true,
        .executable = false,
    };
}

DeckLaunchCta inertLaunchCtaFor(const DeckHostDetail& detail) {
    const auto launchIntent = resolveLaunchIntent(detail, loadSamplePolarisGameFixture());
    const auto preview = fakeLaunchCommandPreviewFor(launchIntent);
    (void)preview;
    return DeckLaunchCta{
        .id = "host-detail-launch-cta",
        .label = "Launch preview only",
        .helpText = "Display-only preview — not wired to launch, Moonlight, or a network backend.",
        .previewStateLabel = preview.stateLabel,
        .previewText = preview.text,
        .enabled = false,
    };
}

std::vector<DeckFocusTarget> hostDetailFocusTargets(const DeckHostDetail& detail, const DeckLaunchCta& launchCta) {
    return {
        DeckFocusTarget{
            .id = "host-detail-panel",
            .label = detail.displayName,
            .row = 0,
            .column = 0,
            .initialFocus = true,
        },
        DeckFocusTarget{
            .id = launchCta.id,
            .label = launchCta.label,
            .row = 1,
            .column = 0,
            .initialFocus = false,
        },
    };
}

std::string_view nextHostDetailFocusTarget(
    const std::vector<DeckFocusTarget>& targets,
    const std::string_view currentId,
    const DeckFocusDirection direction) {
    const auto current = std::ranges::find_if(targets, [currentId](const DeckFocusTarget& target) {
        return target.id == currentId;
    });

    if (current == targets.end()) {
        return initialLibraryFocusTarget(targets);
    }

    const int verticalDelta = direction == DeckFocusDirection::Up ? -1 : direction == DeckFocusDirection::Down ? 1 : 0;
    if (verticalDelta == 0) {
        return current->id;
    }

    const auto next = std::ranges::find_if(targets, [&](const DeckFocusTarget& target) {
        return target.row == current->row + verticalDelta && target.column == current->column;
    });

    if (next != targets.end()) {
        return next->id;
    }

    return current->id;
}

bool isDeckNativeAspect(const int width, const int height) {
    if (width <= 0 || height <= 0) {
        return false;
    }

    return width * 10 == height * 16;
}

} // namespace nova::deck
