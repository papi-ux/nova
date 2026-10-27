#pragma once

#include <QImage>
#include <QObject>
#include <QString>
#include <functional>
#include <optional>

namespace nova::deck::runtime {

// The colour a game's artwork lends its detail page, read from pixels the
// artwork provider has already decoded, so nothing is fetched again. It is the
// rule Polaris's dashboard and Nova on Android use: colourful pixels grouped by
// hue, the strongest run of neighbouring hues, its brightest channel set to 150.
// A cover with too little colour has none.
inline constexpr int kDeckArtworkTintSize = 24;

// The tint of a sample of any size, read pixel by pixel.
std::optional<QRgb> deckArtworkTintOfSample(const QImage& sample);
// The tint of decoded artwork, sampled down to 24 by 24 first.
std::optional<QRgb> deckArtworkTint(const QImage& image);
// "#rrggbb" for QML, or empty for none.
QString deckArtworkTintName(std::optional<QRgb> tint);

// QML's read-only view of the tints the artwork provider recorded. It holds no
// image and starts no request: it only looks up an issued image URL.
class DeckArtworkTints final : public QObject {
    Q_OBJECT
public:
    using Reader = std::function<QString(const QString&)>;
    explicit DeckArtworkTints(QObject* parent = nullptr) : QObject(parent) {}
    void setReader(Reader reader) { reader_ = std::move(reader); }
    Q_INVOKABLE QString tint(const QString& source) const { return reader_ ? reader_(source) : QString(); }
private:
    Reader reader_;
};

} // namespace nova::deck::runtime
