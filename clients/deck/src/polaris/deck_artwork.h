#pragma once

#include <string>

namespace nova::deck {
// Backend-only, validated cached variants of the version-one artwork contract.
// Public models carry only the digest; QML image URLs are issued by the provider.
struct DeckArtworkManifest {
    std::string revision;
    std::string poster;
    std::string hero;
    std::string logo;
    std::string icon;
    std::string key;
    double logoScale = 1.0;
    double logoX = 0.5;
    double logoY = 0.5;
};
}
