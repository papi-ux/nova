#include "deck_layout.h"
#include "polaris_game_fixture.h"

#include <algorithm>
#include <cctype>
#include <string>

namespace nova::deck {
namespace {

constexpr std::string_view kPreviewStateLabel = "Preview only — not executable";
constexpr std::string_view kPreviewBoundaryId = "deck-launch-preview-only";
constexpr std::string_view kPreviewBoundaryLabel = "Preview-only typed intent boundary";
constexpr std::string_view kPreviewBoundaryReason = "Deck shell may build copyable preview text, but launch execution is blocked.";
constexpr std::string_view kCopyIdleStatusLabel = "Copy action is preview-only and not executable.";
constexpr std::string_view kCopySuccessToast = "Preview text copied for inspection only — still not executable.";
constexpr std::string_view kCopyInertToast = "No preview text to copy — preview-only action stayed inert.";

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

std::string sourceLabelFor(const std::string& source) {
    if (source == "steam") {
        return "Steam";
    }
    if (source == "lutris") {
        return "Lutris";
    }
    if (source == "heroic") {
        return "Heroic";
    }
    if (source == "manual") {
        return "Manual";
    }
    return source.empty() ? std::string{"Other"} : source;
}

std::string joinedSourceRuntimeLabelFor(const PolarisGameFixture& game) {
    std::vector<std::string> parts;
    parts.push_back(sourceLabelFor(game.launcherSource.empty() ? game.source : game.launcherSource));
    if (!game.platformLabel.empty()) {
        parts.push_back(game.platformLabel);
    }
    if (!game.runtimeLabel.empty() && game.runtimeLabel != game.platformLabel) {
        parts.push_back(game.runtimeLabel);
    }

    std::string label;
    for (const auto& part : parts) {
        if (part.empty()) {
            continue;
        }
        if (!label.empty()) {
            label += " · ";
        }
        label += part;
    }
    return label;
}

std::string launchModeLabelFor(const PolarisGameFixture& game) {
    const std::string streamMode = game.launchMode.recommendedMode.empty() ? "preview" : game.launchMode.recommendedMode;
    const std::string steamMode = game.steamLaunch.recommendedMode.empty() ? "direct" : game.steamLaunch.recommendedMode;
    return "Stream: " + streamMode + " · Steam: " + steamMode;
}

DeckLibraryGameCard libraryGameCardFor(const PolarisGameFixture& game, const int row) {
    return DeckLibraryGameCard{
        .id = game.id.empty() ? "library-game-" + std::to_string(row) : game.id,
        .title = game.name.empty() ? "Untitled game" : game.name,
        .sourceRuntimeLabel = joinedSourceRuntimeLabelFor(game),
        .launchModeLabel = launchModeLabelFor(game),
        .installedLabel = game.installed ? "Installed" : "Not installed",
        .row = row,
        .initialFocus = row == 0,
    };
}

std::size_t selectedGameIndexFor(const PolarisGameLibraryFixture& library, const std::string_view selectedGameId) {
    if (library.games.empty()) {
        return 0;
    }

    const auto selected = std::ranges::find_if(library.games, [selectedGameId](const PolarisGameFixture& game) {
        return game.id == selectedGameId;
    });

    if (selected == library.games.end()) {
        return 0;
    }

    return static_cast<std::size_t>(std::distance(library.games.begin(), selected));
}

PolarisGameFixture emptySelectedGameFixture() {
    return PolarisGameFixture{
        .id = "game-empty-state",
        .name = "No game selected",
        .source = "manual",
        .launcherSource = "manual",
        .platformLabel = "Preview",
        .runtimeLabel = "No runtime",
        .installed = false,
    };
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

std::vector<DeckLibraryGameCard> libraryGameCardsFor(const PolarisGameLibraryFixture& library) {
    std::vector<DeckLibraryGameCard> cards;
    cards.reserve(library.games.size());
    int row = 0;
    for (const auto& game : library.games) {
        cards.push_back(libraryGameCardFor(game, row));
        ++row;
    }
    return cards;
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

DeckLaunchIntentBoundary previewOnlyLaunchIntentBoundary() {
    return DeckLaunchIntentBoundary{
        .kind = DeckLaunchIntentBoundaryKind::PreviewOnly,
        .id = std::string(kPreviewBoundaryId),
        .label = std::string(kPreviewBoundaryLabel),
        .reason = std::string(kPreviewBoundaryReason),
        .previewOnly = true,
        .allowsNetwork = false,
        .allowsProcessExecution = false,
        .allowsMoonlight = false,
        .allowsHostMutation = false,
    };
}

DeckLaunchIntent resolveLaunchIntent(const DeckHostDetail& detail, const PolarisGameFixture& game) {
    return DeckLaunchIntent{
        .targetHostId = std::string(detail.id),
        .targetHostName = std::string(detail.displayName),
        .sampleGameId = game.id,
        .gameTitle = game.name,
        .streamLaunchMode = game.launchMode.recommendedMode.empty() ? std::string{"preview"} : game.launchMode.recommendedMode,
        .steamLaunchMode = game.steamLaunch.recommendedMode.empty() ? std::string{"direct"} : game.steamLaunch.recommendedMode,
        .boundary = previewOnlyLaunchIntentBoundary(),
        .executable = false,
        .safetyLabel = std::string(kPreviewStateLabel),
    };
}

DeckLaunchPreviewBinding resolveLaunchPreviewBinding(
    const std::vector<DeckHostListItem>& hosts,
    const PolarisGameLibraryFixture& library,
    const std::string_view selectedHostId,
    const std::string_view selectedGameId) {
    const std::string_view resolvedHostId = resolveHostDetail(hosts, selectedHostId).id == std::string_view("host-detail-empty")
        ? initialHostFocusTarget(hosts)
        : selectedHostId;
    const auto hostDetail = resolveHostDetail(hosts, resolvedHostId);

    const auto gameIndex = selectedGameIndexFor(library, selectedGameId);
    const auto selectedGame = library.games.empty() ? emptySelectedGameFixture() : library.games.at(gameIndex);
    const auto gameCard = libraryGameCardFor(selectedGame, static_cast<int>(gameIndex));
    const auto intent = resolveLaunchIntent(hostDetail, selectedGame);
    const auto preview = fakeLaunchCommandPreviewFor(intent);
    const auto launchCta = inertLaunchCtaFor(hostDetail, selectedGame);
    const auto copyAction = copyLaunchPreviewActionFor(preview);

    return DeckLaunchPreviewBinding{
        .selectedHostId = std::string(hostDetail.id),
        .selectedHostName = std::string(hostDetail.displayName),
        .selectedGameId = selectedGame.id,
        .selectedGameTitle = selectedGame.name,
        .hostDetail = hostDetail,
        .gameCard = gameCard,
        .intent = intent,
        .preview = preview,
        .launchCta = launchCta,
        .copyAction = copyAction,
    };
}

bool canExecuteLaunchIntent(const DeckLaunchIntent& intent) {
    return intent.executable
        && !intent.boundary.previewOnly
        && intent.boundary.allowsNetwork
        && intent.boundary.allowsProcessExecution
        && intent.boundary.allowsMoonlight
        && intent.boundary.allowsHostMutation;
}

DeckLaunchPreview fakeLaunchCommandPreviewFor(const DeckLaunchIntent& intent) {
    return DeckLaunchPreview{
        .text = "preview://nova-deck/launch?host=" + encodePreviewComponent(intent.targetHostId)
            + "&game=" + encodePreviewComponent(intent.gameTitle)
            + "&state=copy-preview-only",
        .stateLabel = std::string(kPreviewStateLabel),
        .boundaryId = intent.boundary.id,
        .boundaryLabel = intent.boundary.label,
        .copyOnly = true,
        .executable = canExecuteLaunchIntent(intent),
        .networkAllowed = intent.boundary.allowsNetwork,
        .processExecutionAllowed = intent.boundary.allowsProcessExecution,
        .moonlightAllowed = intent.boundary.allowsMoonlight,
        .hostMutationAllowed = intent.boundary.allowsHostMutation,
    };
}

DeckLaunchPreviewCopyAction copyLaunchPreviewActionFor(const DeckLaunchPreview& preview) {
    const bool hasPreviewText = !preview.text.empty();
    return DeckLaunchPreviewCopyAction{
        .id = "host-detail-copy-preview",
        .label = "Copy preview text",
        .previewText = preview.text,
        .idleStatusLabel = hasPreviewText ? std::string(kCopyIdleStatusLabel) : std::string(kCopyInertToast),
        .successToast = std::string(kCopySuccessToast),
        .inertToast = std::string(kCopyInertToast),
        .enabled = hasPreviewText,
        .copyOnly = true,
        .uiLocalClipboardOnly = true,
        .executable = false,
    };
}

DeckLaunchPreviewCopyResult activateLaunchPreviewCopy(const DeckLaunchPreviewCopyAction& action) {
    const bool canCopyPreview = action.enabled && !action.previewText.empty() && action.copyOnly && !action.executable;
    const std::string status = canCopyPreview ? action.successToast : action.inertToast;
    return DeckLaunchPreviewCopyResult{
        .previewText = canCopyPreview ? action.previewText : std::string{},
        .statusLabel = status,
        .toastLabel = status,
        .copied = canCopyPreview,
    };
}

DeckLaunchPreviewCopyResult copyLaunchPreviewToLocalClipboard(
    const DeckLaunchPreviewCopyAction& action,
    DeckLocalClipboard& clipboard) {
    const bool canCopyPreview = action.enabled && !action.previewText.empty() && action.copyOnly
        && action.uiLocalClipboardOnly && !action.executable;
    if (!canCopyPreview) {
        return DeckLaunchPreviewCopyResult{
            .previewText = {},
            .statusLabel = action.inertToast,
            .toastLabel = action.inertToast,
            .copied = false,
        };
    }

    const bool published = clipboard.publishPreviewText(action.previewText);
    const std::string status = published ? action.successToast : action.inertToast;
    return DeckLaunchPreviewCopyResult{
        .previewText = published ? action.previewText : std::string{},
        .statusLabel = status,
        .toastLabel = status,
        .copied = published,
    };
}

DeckLaunchCta inertLaunchCtaFor(const DeckHostDetail& detail, const PolarisGameFixture& game) {
    const auto launchIntent = resolveLaunchIntent(detail, game);
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

DeckLaunchCta inertLaunchCtaFor(const DeckHostDetail& detail) {
    return inertLaunchCtaFor(detail, loadSamplePolarisGameFixture());
}

std::vector<DeckFocusTarget> hostDetailFocusTargets(
    const DeckHostDetail& detail,
    const DeckLaunchCta& launchCta,
    const DeckLaunchPreviewCopyAction& copyAction) {
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
        DeckFocusTarget{
            .id = copyAction.id,
            .label = copyAction.label,
            .row = 2,
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
