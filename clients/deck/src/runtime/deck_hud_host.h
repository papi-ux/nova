#pragma once
#include "polaris/deck_polaris_client.h"
#include <QVariantMap>
#include <QThread>
#include <QSet>
#include <memory>

namespace nova::deck::runtime {
class DeckDoctorReceiptStore;
struct DeckHudHostContext { int gameId = 0; QString gameUuid, sessionToken; };
struct DeckHudHostTarget {
    std::function<polaris::DeckPolarisResult<polaris::DeckHostTelemetry>(const std::function<bool()>&)> fetch;
    std::function<bool()> identityValid;
    std::function<polaris::DeckPolarisResult<bool>(bool, const polaris::DeckLiveTuningTelemetry&, const std::function<bool()>&)> setEnabled;
    std::function<polaris::DeckPolarisResult<bool>(int, const polaris::DeckLiveTuningTelemetry&, const std::function<bool()>&)> setBitrate;
    // Called on a second thread. The callback must construct/own its network
    // client there, and return promptly when cancelled.
    std::function<polaris::DeckPolarisResult<bool>(int, const std::function<void()>&, const std::function<bool()>&)> events;
    std::function<polaris::DeckPolarisResult<polaris::DeckDoctorReceipt>(const polaris::DeckDoctorRequest&, const std::function<bool()>&)> doctorAction;
    std::shared_ptr<DeckDoctorReceiptStore> doctorReceipts;
    std::function<polaris::DeckPolarisResult<polaris::DeckHostSettings>(const std::function<bool()>&)> readProfile;
    std::function<polaris::DeckPolarisResult<polaris::DeckHostSettings>(const QString&, int, bool,
        const polaris::DeckLiveTuningTelemetry&, const std::function<bool()>&)> writeProfile;
};
// Called on a dedicated observer thread, so network objects never cross threads.
using DeckHudHostFactory = std::function<std::optional<DeckHudHostTarget>()>;
class DeckHudHostReducer {
public:
    explicit DeckHudHostReducer(DeckHudHostContext context) : context_(std::move(context)) {}
    static QVariantMap unavailable();
    QVariantMap accept(const polaris::DeckHostTelemetry& sample);
private:
    DeckHudHostContext context_;
    std::optional<polaris::DeckLiveTuningTelemetry> last_;
    QSet<QString> retired_;
};
struct DeckHudHostTiming { int intervalMs = 1000, staleMs = 3500, maxBackoffMs = 8000, confirmationMs = 5000, eventRetryMs = 1000; };
class DeckHudHostObserver {
public:
    DeckHudHostObserver(DeckHudHostFactory factory, DeckHudHostContext context, DeckHudHostTiming timing = {}, std::function<bool()> sessionEnded = {});
    ~DeckHudHostObserver();
    DeckHudHostObserver(const DeckHudHostObserver&) = delete;
    DeckHudHostObserver& operator=(const DeckHudHostObserver&) = delete;
    QVariantMap snapshot() const;
    bool setLiveTuningEnabled(bool enabled);
    bool setFixedBitrate(int bitrateKbps);
    bool setSyncProfile(const QString& display, int bitrateKbps, bool clear, const QVariantMap& reviewed);
    bool refreshDiagnostics();
    bool applyDoctorFix();
    bool undoDoctorFix();
    bool checkDoctorResult();
private:
    struct Shared;
    std::shared_ptr<Shared> shared_;
    std::unique_ptr<QThread> worker_;
};
}
