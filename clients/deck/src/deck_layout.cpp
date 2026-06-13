#include "deck_layout.h"

namespace nova::deck {

DeckWindowProfile defaultWindowProfile() {
    return DeckWindowProfile{
        .width = 1280,
        .height = 800,
        .fullscreenPreferred = true,
        .shellName = "Nova Deck",
    };
}

bool isDeckNativeAspect(const int width, const int height) {
    if (width <= 0 || height <= 0) {
        return false;
    }

    return width * 10 == height * 16;
}

} // namespace nova::deck
