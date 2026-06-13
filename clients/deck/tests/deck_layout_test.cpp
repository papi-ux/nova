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
