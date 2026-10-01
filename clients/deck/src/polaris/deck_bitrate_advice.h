#pragma once
#include <QJsonObject>
#include <QVariantMap>
#include <optional>
namespace nova::deck::polaris {
inline constexpr int automaticBitrateMaximum = 300000;
int manualBitrateMaximum(const QJsonValue& advertised);
int encoderForRequest(int request, int audio = 512, int fec = 10);
int requestForEncoder(int encoder, int audio = 512, int fec = 10);
struct DeckBitrateUnits {
    int requested = 0, encoder = 0, liveEncoder = 0, audio = 0, fec = 0, warp = 1;
    std::optional<int> split, cap;
    QString capSource;
    bool operator==(const DeckBitrateUnits&) const = default;
    int requestFor(int encoderKbps) const;
};
std::optional<DeckBitrateUnits> parseBitrateUnits(const QJsonObject&);
struct DeckPyrowaveAdvice {
    int width = 0, height = 0, fps = 0, goal = 0, cap = 0, audio = 512, fec = 10;
    QString limitedBy;
    bool assumptionsKnown = false;
    std::optional<int> manualMaximum;
    bool matches(int w, int h, int rate) const { return width == w && height == h && fps == rate; }
    QVariantMap toMap() const;
};
std::optional<DeckPyrowaveAdvice> parsePyrowaveAdvice(const QJsonObject&);
int pyrowaveEncoderAdvice(int width, int height, int fps, bool room = false, bool chroma444 = false);
QVariantMap bitrateAdvice(int width, int height, int fps, const QJsonObject& host = {}, bool room = false, bool chroma444 = false);
}
