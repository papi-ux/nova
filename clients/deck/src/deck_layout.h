#pragma once

#include <string_view>

namespace nova::deck {

struct DeckWindowProfile {
    int width;
    int height;
    bool fullscreenPreferred;
    std::string_view shellName;
};

DeckWindowProfile defaultWindowProfile();

bool isDeckNativeAspect(int width, int height);

} // namespace nova::deck
