#pragma once
#include <QRectF>
#include <QSizeF>
#include <QString>
#include <cmath>
#include <optional>

namespace nova::deck::stream {
enum class DeckVideoScaleMode { Fit, Fill, Stretch };
inline std::optional<DeckVideoScaleMode> deckVideoScaleMode(const QString& value) {
    if (value == "fit") return DeckVideoScaleMode::Fit;
    if (value == "fill") return DeckVideoScaleMode::Fill;
    if (value == "stretch") return DeckVideoScaleMode::Stretch;
    return {};
}
// Normalized rectangles let both renderers share the geometry without changing
// the output surface, color metadata, or the screen-space overlay coordinates.
struct DeckVideoScaleLayout {
    QRectF source{0, 0, 1, 1};
    QRectF destination{0, 0, 1, 1};
};
inline DeckVideoScaleLayout deckVideoScaleLayout(QSizeF source, QSizeF target,
        DeckVideoScaleMode mode, double pixelAspect = 1) {
    DeckVideoScaleLayout out;
    if (source.isEmpty() || target.isEmpty()) return out;
    if (!std::isfinite(pixelAspect) || pixelAspect <= 0) pixelAspect = 1;
    const double ratio = (source.width() * pixelAspect / source.height()) / (target.width() / target.height());
    if (!std::isfinite(ratio) || ratio <= 0) return out;
    if (mode == DeckVideoScaleMode::Fit) {
        if (ratio > 1) out.destination = {0, (1 - 1/ratio)/2, 1, 1/ratio};
        else out.destination = {(1 - ratio)/2, 0, ratio, 1};
    } else if (mode == DeckVideoScaleMode::Fill) {
        if (ratio > 1) out.source = {(1 - 1/ratio)/2, 0, 1/ratio, 1};
        else out.source = {0, (1 - ratio)/2, 1, ratio};
    }
    return out;
}
}
