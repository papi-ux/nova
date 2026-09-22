#pragma once
#include <QJsonObject>
#include <QString>
#include <optional>

namespace nova::deck::polaris {
// Backend-only authority and receipt identities. Never expose these to QML.
struct DeckDoctorOffer {
    QString action, result, appSession;
    qint64 generation = 0, controllerRevision = 0, evidenceRevision = 0;
    int targetKbps = 0, delaySeconds = 0;
};
struct DeckDoctorRequest {
    QString action, appSession, requestId, runId;
    qint64 generation = 0;
    std::optional<DeckDoctorOffer> offer;
};
struct DeckDoctorReceipt {
    QString state, runId;
    bool undo = false;
    int delaySeconds = 0;
};
std::optional<DeckDoctorOffer> parseDoctorOffer(const QJsonObject& doctor);
std::optional<QJsonObject> doctorRequestBody(const DeckDoctorRequest& request);
std::optional<DeckDoctorReceipt> parseDoctorReceipt(const QJsonObject& body, int httpStatus, const DeckDoctorRequest& request);
}
