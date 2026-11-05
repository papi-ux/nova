#pragma once
#include <QJsonObject>
#include <QJsonValue>
#include <QString>
#include <optional>

namespace nova::deck::polaris {
struct DeckSessionVrrHostPacing { bool fresh = false; QString state; };
struct DeckSessionVrr {
    qint64 sessionGeneration = 0;
    QString requested, requestedFrom, pacingMode, capturePacing;
    bool hostPermission = false, effective = false;
    std::optional<QString> fallbackReason;
    std::optional<DeckSessionVrrHostPacing> hostPacing;
    bool hostSourcePaced() const {
        return hostPermission && effective && !fallbackReason && pacingMode == "source_time" &&
            hostPacing && hostPacing->fresh && hostPacing->state == "source_time";
    }
};
struct DeckSessionVrrReading {
    enum class Kind { Absent, Null, Malformed, Present };
    Kind kind = Kind::Absent;
    std::optional<DeckSessionVrr> record;
};
std::optional<DeckSessionVrrHostPacing> parseSessionVrrHostPacing(const QJsonObject& o);
std::optional<DeckSessionVrr> parseSessionVrr(const QJsonObject& o);
DeckSessionVrrReading readSessionVrr(const QJsonValue& value);
}
