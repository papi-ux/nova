#pragma once
#include "polaris/deck_polaris_client.h"
#include <QObject>
#include <QElapsedTimer>
#include <QThread>
#include <QTimer>
#include <QVariantMap>

namespace nova::deck::runtime {
struct DeckHostPowerTarget {
    std::function<polaris::DeckPolarisResult<polaris::DeckPolarisCapabilities>()> capabilities;
    std::function<polaris::DeckPolarisResult<polaris::DeckHostPower>()> power;
    std::function<polaris::DeckPolarisResult<polaris::DeckHostSleepReceipt>(const std::function<bool()>&)> sleep;
    std::function<bool()> reachable;
    std::function<bool()> identityValid;
};
// Resolve on the worker so pinned transport objects stay on their owner thread.
using DeckHostPowerResolver = std::function<std::optional<DeckHostPowerTarget>()>;
struct DeckHostPowerTiming {
    int holdMs = 1000, graceMs = 5000, confirmAttempts = 10, confirmIntervalMs = 1000;
};
class DeckHostPowerController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
public:
    explicit DeckHostPowerController(QObject* parent = nullptr, DeckHostPowerTiming timing = {});
    ~DeckHostPowerController() override;
    QVariantMap state() const { return state_; }
    bool busy() const;
    void setTarget(QString hostId, QString hostName, DeckHostPowerResolver resolver);
    void setSessionActive(bool active);
    Q_INVOKABLE void setWindowActive(bool active);
    Q_INVOKABLE bool refresh();
    Q_INVOKABLE bool beginHold();
    Q_INVOKABLE void releaseHold();
    Q_INVOKABLE void cancel();
signals:
    void stateChanged();
private:
    struct Job;
    void tick();
    void startJob(bool sendSleep);
    void publish(QString phase, QString copy, bool eligible = false, double progress = 0, int remaining = 0);
    DeckHostPowerTiming timing_;
    DeckHostPowerResolver resolver_;
    QString hostId_, hostName_, phase_ = "idle";
    QVariantMap state_;
    QElapsedTimer phaseClock_, verifiedClock_;
    bool eligible_ = false, windowActive_ = false, sessionActive_ = false;
    QTimer timer_;
    QThread* worker_ = nullptr;
    std::shared_ptr<Job> job_;
    unsigned generation_ = 0;
};
} // namespace nova::deck::runtime
