#pragma once
#include "polaris/deck_host_telemetry.h"
#include "polaris/deck_doctor_action.h"

namespace nova::deck::runtime {
// Used under the host observer's mutex. Network work remains on its worker.
class DeckDoctorActions {
public:
    enum class Recovery { Restored, Obsolete, Invalid };
    QJsonObject checkpoint() const;
    Recovery restore(const QJsonObject& checkpoint, const polaris::DeckLiveTuningTelemetry& current, qint64 epochMs);
    void storageFailure();
    void observe(const polaris::DeckHostTelemetry* sample, bool authorized, qint64 now);
    void invalidate();
    bool apply(qint64 now);
    bool undo(qint64 now);
    bool check(qint64 now);
    std::optional<polaris::DeckDoctorRequest> next(qint64 now);
    void complete(const std::optional<polaris::DeckDoctorReceipt>& receipt, qint64 now);
    QVariantMap view(qint64 now) const;
    bool busy() const { return !pending_.isEmpty() || inFlight_; }
private:
    bool fresh(qint64 now) const;
    bool sameScope(const polaris::DeckLiveTuningTelemetry& live) const;
    void retire(const QString& message);
    std::optional<polaris::DeckDoctorOffer> offer_, reviewed_;
    std::optional<polaris::DeckLiveTuningTelemetry> live_, origin_;
    std::optional<polaris::DeckDoctorRequest> initial_;
    std::optional<polaris::DeckDoctorReceipt> receipt_;
    QString pending_, state_, message_, dispatched_;
    qint64 observed_ = -10000, due_ = 0, deadline_ = 0, createdEpochMs_ = 0;
    int checks_ = 0;
    bool inFlight_ = false, uncertain_ = false, recovered_ = false;
};
}
