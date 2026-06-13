#include "deck_layout.h"

#include <algorithm>

namespace nova::deck {

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

bool isDeckNativeAspect(const int width, const int height) {
    if (width <= 0 || height <= 0) {
        return false;
    }

    return width * 10 == height * 16;
}

} // namespace nova::deck
