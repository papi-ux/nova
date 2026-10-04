#pragma once
#include "polaris/deck_polaris_client.h"
#include <QObject>
#include <QThread>
#include <QVariantMap>

namespace nova::deck::runtime {
struct DeckSteamTarget {
    std::function<polaris::DeckPolarisResult<polaris::DeckDesktopSteam>()> read;
    std::function<polaris::DeckPolarisResult<polaris::DeckDesktopSteam>(const std::string&, const std::string&, bool, const std::function<bool()>&)> act;
    std::function<bool()> identityValid;
};
using DeckSteamResolver = std::function<std::optional<DeckSteamTarget>()>;
class DeckSteamControl final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
public:
    explicit DeckSteamControl(QObject* parent = nullptr);
    ~DeckSteamControl() override;
    QVariantMap state() const { return state_; }
    bool busy() const { return worker_ != nullptr; }
    void setTarget(QString id, QString name, DeckSteamResolver resolver, QString identityKey = {});
    void setBlocked(bool blocked);
    Q_INVOKABLE void setWindowActive(bool active);
    Q_INVOKABLE bool refresh();
    Q_INVOKABLE bool request(QString action, bool confirm = false);
    Q_INVOKABLE void cancel();
signals:
    void stateChanged();
private:
    struct Job;
    bool start(QString action, bool confirmed);
    QString id_, name_, identityKey_;
    DeckSteamResolver resolver_;
    QVariantMap state_;
    std::shared_ptr<Job> job_;
    QThread* worker_ = nullptr;
    unsigned generation_ = 0;
    bool blocked_ = false, active_ = true;
};
}
