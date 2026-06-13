#include "deck_layout.h"
#include "polaris_game_fixture.h"

#include <cassert>
#include <string_view>

int main() {
    const auto profile = nova::deck::defaultWindowProfile();

    assert(profile.width == 1280);
    assert(profile.height == 800);
    assert(profile.fullscreenPreferred);
    assert(profile.shellName == std::string_view("Nova Deck"));

    const auto focusTargets = nova::deck::defaultLibraryFocusTargets();
    assert(focusTargets.size() >= 2);
    assert(focusTargets.front().id == std::string_view("sample-game-card"));
    assert(focusTargets.front().initialFocus);
    assert(nova::deck::initialLibraryFocusTarget(focusTargets) == std::string_view("sample-game-card"));
    assert(nova::deck::nextLibraryFocusTarget(focusTargets, "sample-game-card", nova::deck::DeckFocusDirection::Right)
        == std::string_view("details-placeholder"));
    assert(nova::deck::nextLibraryFocusTarget(focusTargets, "details-placeholder", nova::deck::DeckFocusDirection::Left)
        == std::string_view("sample-game-card"));

    const auto emptyHosts = nova::deck::emptyHostListState();
    assert(emptyHosts.empty());
    assert(nova::deck::initialHostFocusTarget(emptyHosts) == std::string_view("host-empty-state"));
    assert(nova::deck::nextHostFocusTarget(emptyHosts, "missing-host", nova::deck::DeckFocusDirection::Down)
        == std::string_view("host-empty-state"));

    const auto demoHosts = nova::deck::demoHostListState();
    assert(demoHosts.size() >= 2);
    assert(demoHosts[0].id == std::string_view("host-gaming-pc"));
    assert(demoHosts[0].displayName == std::string_view("Gaming PC"));
    assert(demoHosts[0].statusLabel == std::string_view("Ready for local demo"));
    assert(demoHosts[1].id == std::string_view("host-living-room-pc"));
    assert(demoHosts[1].displayName == std::string_view("Living Room PC"));
    assert(nova::deck::initialHostFocusTarget(demoHosts) == std::string_view("host-gaming-pc"));
    assert(nova::deck::nextHostFocusTarget(demoHosts, "host-gaming-pc", nova::deck::DeckFocusDirection::Down)
        == std::string_view("host-living-room-pc"));
    assert(nova::deck::nextHostFocusTarget(demoHosts, "host-living-room-pc", nova::deck::DeckFocusDirection::Up)
        == std::string_view("host-gaming-pc"));
    assert(nova::deck::nextHostFocusTarget(demoHosts, "host-living-room-pc", nova::deck::DeckFocusDirection::Down)
        == std::string_view("host-living-room-pc"));

    const auto detail = nova::deck::resolveHostDetail(demoHosts, "host-gaming-pc");
    assert(detail.id == std::string_view("host-gaming-pc"));
    assert(detail.displayName == std::string_view("Gaming PC"));
    assert(detail.statusLabel == std::string_view("Ready for local demo"));
    assert(detail.subtitle == std::string_view("Demo host detail only — not discovered from the network."));

    const auto launchCta = nova::deck::inertLaunchCtaFor(detail);
    assert(launchCta.id == std::string_view("host-detail-launch-cta"));
    assert(launchCta.label == std::string_view("Launch coming soon"));
    assert(launchCta.helpText == std::string_view("Placeholder only — not wired to launch, Moonlight, or a network backend yet."));
    assert(!launchCta.enabled);

    const auto detailFocus = nova::deck::hostDetailFocusTargets(detail, launchCta);
    assert(detailFocus.size() == 2);
    assert(detailFocus[0].id == std::string_view("host-detail-panel"));
    assert(detailFocus[1].id == std::string_view("host-detail-launch-cta"));
    assert(nova::deck::nextHostDetailFocusTarget(detailFocus, "host-detail-panel", nova::deck::DeckFocusDirection::Down)
        == std::string_view("host-detail-launch-cta"));
    assert(nova::deck::nextHostDetailFocusTarget(detailFocus, "host-detail-launch-cta", nova::deck::DeckFocusDirection::Up)
        == std::string_view("host-detail-panel"));

    assert(nova::deck::isDeckNativeAspect(1280, 800));
    assert(nova::deck::isDeckNativeAspect(2560, 1600));
    assert(!nova::deck::isDeckNativeAspect(1920, 1080));
    assert(!nova::deck::isDeckNativeAspect(0, 800));

    const auto game = nova::deck::loadSamplePolarisGameFixture();
    assert(game.id == "game-123");
    assert(game.appId == 456);
    assert(game.name == "Portal 2");
    assert(game.source == "steam");
    assert(game.platform == "linux");
    assert(game.runtime == "proton");
    assert(game.steamAppid == "620");
    assert(game.installed);
    assert(game.genres.size() == 2);
    assert(game.genres[0] == "Action");
    assert(game.genres[1] == "Puzzle");
    assert(game.launchMode.preferredMode == "virtual_display");
    assert(game.launchMode.recommendedMode == "headless");
    assert(game.launchMode.allowedModes.size() == 2);
    assert(game.steamLaunch.available);
    assert(game.steamLaunch.mode == "big-picture");
    assert(game.steamLaunch.recommendedMode == "direct");

    return 0;
}
