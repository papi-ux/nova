// The artwork tint rule, on 24 by 24 samples: the same table as Android's
// NovaArtworkTintTest, and both follow Polaris's artwork-tint.js, so one cover
// gives one tint on every surface.
//
// UNRUN: written for Nova #385 and not executed in the authoring session.
#include "runtime/deck_artwork_tint.h"

#include <QColor>
#include <QImage>
#include <cstdlib>
#include <initializer_list>
#include <iostream>
#include <utility>

using namespace nova::deck::runtime;

namespace {
void require(bool value, const char* message) {
    if (!value) { std::cerr << message << '\n'; std::exit(1); }
}

// Runs of (count, ARGB) laid out row by row over one 24 by 24 sample.
QImage sample(std::initializer_list<std::pair<int, QRgb>> runs) {
    QImage image(kDeckArtworkTintSize, kDeckArtworkTintSize, QImage::Format_ARGB32);
    int at = 0;
    for (const auto& [count, argb] : runs) {
        for (int i = 0; i < count; ++i, ++at)
            image.setPixel(at % kDeckArtworkTintSize, at / kDeckArtworkTintSize, argb);
    }
    require(at == kDeckArtworkTintSize * kDeckArtworkTintSize, "a fixture is one 24 by 24 sample");
    return image;
}

QString tintOf(const QImage& image) { return deckArtworkTintName(deckArtworkTintOfSample(image)); }
}

int main() {
    const QRgb blue = 0xFF0000C8, orange = 0xFFFF8000, grey = 0xFF808080;

    require(tintOf(sample({{520, blue}, {56, orange}})) == "#000096",
        "a blue cover with an orange title should tint blue, not a muddy mean");

    require(tintOf(sample({{576, grey}})).isEmpty(), "a grey cover has no tint");
    require(tintOf(sample({{576, 0x000000C8}})).isEmpty(), "a transparent cover has no tint");
    require(tintOf(sample({{553, grey}, {23, blue}})).isEmpty(), "under one pixel in twenty with colour counts as grey");
    require(tintOf(sample({{541, grey}, {35, blue}})) == "#000096", "only the colourful pixels make the tint");

    const auto pale = tintOf(sample({{576, 0xFF6464C8}}));
    require(pale == "#4b4b96" && tintOf(sample({{576, 0xFF323264}})) == pale,
        "a pale cover and a dark one of the same hue should tint alike");

    require(tintOf(sample({{288, 0xFFC8000A}, {288, 0xFFC80A00}})) == "#960404",
        "reds either side of zero hue are one group");

    // Decoded artwork of any size is sampled down first; one colour stays one colour.
    QImage hero(1280, 720, QImage::Format_ARGB32);
    hero.fill(QColor(0, 0, 200));
    require(deckArtworkTintName(deckArtworkTint(hero)) == "#000096", "large artwork should tint as its sample does");
    require(!deckArtworkTint(QImage()).has_value(), "no image, no tint");
    return 0;
}
