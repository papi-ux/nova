#pragma once
#include "polaris/deck_polaris_client.h"
#include "runtime/deck_play_settings.h"
#include <QObject>
#include <QPointer>
#include <QThread>
#include <QTimer>
#include <QElapsedTimer>
#include <QHash>
#include <QSet>

namespace nova::deck::runtime {
struct DeckHostSettingsTarget {
    std::function<polaris::DeckPolarisResult<polaris::DeckPolarisCapabilities>()> capabilities;
    std::function<polaris::DeckPolarisResult<polaris::DeckHostSettings>(const std::function<bool()>&)> read;
    std::function<polaris::DeckPolarisResult<bool>(const std::function<bool()>&)> idle;
    std::function<polaris::DeckPolarisResult<polaris::DeckHostSettings>(const QString&, const std::function<bool()>&)> writeMode;
    std::function<polaris::DeckPolarisResult<polaris::DeckHostSettings>(const QString&, int, bool, const std::function<bool()>&)> writeProfile;
    std::function<bool()> identityValid;
    std::function<polaris::DeckPolarisResult<polaris::DeckHostSettings>(int, const std::function<bool()>&)> writeResumeTimeout;
};
using DeckHostSettingsResolver = std::function<std::optional<DeckHostSettingsTarget>()>;
class DeckHostSettingsController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
public:
    explicit DeckHostSettingsController(QObject* parent = nullptr);
    ~DeckHostSettingsController() override;
    QVariantMap state() const { return state_; }
    bool busy() const { return worker_ != nullptr; }
    void setTarget(QString hostId, QString name, DeckHostSettingsResolver resolver);
    void setSessionActive(bool active);
    void setPlaySettings(DeckPlaySettings* settings);
    Q_INVOKABLE void setWindowActive(bool active);
    Q_INVOKABLE void open();
    Q_INVOKABLE void openSync(bool readOnly);
    Q_INVOKABLE void close();
    Q_INVOKABLE bool refresh();
    Q_INVOKABLE bool selectMode(const QString& mode);
    Q_INVOKABLE bool profileAction(const QString& action);
    Q_INVOKABLE bool setKeepInStep(bool enabled);
    Q_INVOKABLE bool setResumeTimeout(int seconds);
    Q_INVOKABLE bool saveDefaults(const QVariantMap& values, const QVariantMap& expected);
signals:
    void stateChanged();
    void hostDefaultsMayHaveChanged();
    void novaDefaultsChanged();
private:
    struct Job;
    void start(const QString& mode = {}, const QString& profileAction = {}, bool automatic = false, int resumeTimeout = -1);
    QString keepInStep() const;
    void pauseKeepInStep();
    void maybeKeepInStep();
    void poll();
    void publish(QString phase, QString copy);
    void cancel();
    QString hostId_, name_, phase_ = "idle";
    DeckHostSettingsResolver resolver_;
    std::optional<polaris::DeckHostSettings> settings_;
    QPointer<DeckPlaySettings> playSettings_;
    bool readOnly_ = false, syncView_ = false;
    bool idle_ = false, windowActive_ = false, sessionActive_ = false, opened_ = false;
    unsigned generation_ = 0;
    QVariantMap state_;
    QTimer timer_;
    QTimer syncTimer_;
    QElapsedTimer clock_;
    QHash<QString, qint64> lastSync_;
    QSet<QString> pausedHosts_;
    QThread* worker_ = nullptr;
    std::shared_ptr<Job> job_;
};
}
