#pragma once
#include <QString>
#include <QJsonObject>
#include <QVariantMap>
#include <optional>
#include <string_view>
#include "polaris/deck_doctor_action.h"

namespace nova::deck::polaris {
// Backend-only status identity. Never register this object or its tokens with QML.
struct DeckLiveTuningTelemetry {
    bool enabled = false, supported = false;
    QString state, revision, instance, appSession;
    qint64 sequence = 0, generation = 0;
    int qualityLimit = 0, requested = 0, applied = 0;
};
struct DeckHostTelemetry {
    bool active = false, owned = false, ending = false, authorityValid = false, hostTuningAllowed = false;
    QString role, gameUuid, sessionToken, appSession;
    int gameId = 0;
    int eventsHttpsPort = 0;
    std::optional<qint64> generation;
    bool livePresent = false;
    std::optional<DeckLiveTuningTelemetry> live;
    std::optional<bool> legacyTuning;
    bool doctorAuthoritative = false, doctorHealthy = false, doctorVerdictPresent = false;
    bool hostWarning = false, networkWarning = false, clientWarning = false, evidenceWarning = false, displayOverride = false;
    QString primaryIssue, grade;
    bool hostLimited = false, hdrDowngraded = false;
    QVariantMap doctor;
    std::optional<DeckDoctorOffer> doctorOffer;
};
std::optional<DeckHostTelemetry> parseHostTelemetry(std::string_view json);
std::optional<DeckLiveTuningTelemetry> parseLiveTuningTelemetry(const QJsonObject& object);
}
