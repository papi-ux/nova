#include "runtime/deck_artwork_tint.h"

#include <QColor>
#include <algorithm>
#include <array>
#include <cmath>

namespace nova::deck::runtime {
namespace {
constexpr int kHues = 12;
// Pixels nearer grey than this carry no hue worth tinting with.
constexpr int kMinChroma = 24;
// Under one opaque pixel in twenty with colour, a cover counts as grey.
constexpr double kMinColourful = 0.05;
// Every tint has the same brightest channel. No channel above it means a wash
// in this colour can only darken a light pixel under text.
constexpr double kPeak = 150.0;
}

std::optional<QRgb> deckArtworkTintOfSample(const QImage& sample) {
    if (sample.isNull()) return std::nullopt;
    const auto pixels = sample.convertToFormat(QImage::Format_ARGB32);
    std::array<double, kHues> weight{}, red{}, green{}, blue{};
    int opaque = 0, colourful = 0;
    for (int y = 0; y < pixels.height(); ++y) {
        for (int x = 0; x < pixels.width(); ++x) {
            const QRgb pixel = pixels.pixel(x, y);
            if (qAlpha(pixel) < 128) continue;
            ++opaque;
            const int r = qRed(pixel), g = qGreen(pixel), b = qBlue(pixel);
            const int max = std::max({r, g, b});
            const int chroma = max - std::min({r, g, b});
            if (chroma < kMinChroma) continue;
            ++colourful;
            const double hue = max == r ? double(g - b) / chroma
                : max == g ? 2.0 + double(b - r) / chroma : 4.0 + double(r - g) / chroma;
            // Vivid pixels count for more than dull ones.
            const int group = int(std::floor(std::fmod(hue + 6.0, 6.0) * kHues / 6.0)) % kHues;
            weight[group] += chroma;
            red[group] += double(r) * chroma;
            green[group] += double(g) * chroma;
            blue[group] += double(b) * chroma;
        }
    }
    if (opaque == 0 || colourful < opaque * kMinColourful) return std::nullopt;
    int best = 0;
    double bestWeight = -1.0;
    for (int at = 0; at < kHues; ++at) {
        const double run = weight[(at + kHues - 1) % kHues] + weight[at] + weight[(at + 1) % kHues];
        if (run > bestWeight) { best = at; bestWeight = run; }
    }
    const auto mean = [&](const std::array<double, kHues>& channel) {
        return (channel[(best + kHues - 1) % kHues] + channel[best] + channel[(best + 1) % kHues]) / bestWeight;
    };
    const double means[] = {mean(red), mean(green), mean(blue)};
    const double peak = std::max({means[0], means[1], means[2]});
    const auto scaled = [&](double value) { return std::clamp(int(std::lround(value * kPeak / peak)), 0, 255); };
    return qRgb(scaled(means[0]), scaled(means[1]), scaled(means[2]));
}

std::optional<QRgb> deckArtworkTint(const QImage& image) {
    if (image.isNull()) return std::nullopt;
    return deckArtworkTintOfSample(image.scaled(kDeckArtworkTintSize, kDeckArtworkTintSize,
        Qt::IgnoreAspectRatio, Qt::SmoothTransformation));
}

QString deckArtworkTintName(std::optional<QRgb> tint) {
    return tint ? QColor(*tint).name(QColor::HexRgb) : QString();
}

} // namespace nova::deck::runtime
