#pragma once

#include "identity/deck_pairing.h"

#include <QObject>
#include <QThread>
#include <QTimer>
#include <QVariantMap>

namespace nova::deck::runtime {
using PairTransportFactory = std::function<identity::PairTransport(identity::crypto::Credentials, identity::PairCancelled)>;

class DeckPairingController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
    Q_PROPERTY(QVariantList savedHosts READ savedHosts NOTIFY savedHostsChanged)
public:
    explicit DeckPairingController(QString directory = identity::nativeIdentityDirectory(),
        PairTransportFactory factory = identity::pairingNetworkTransport,
        std::function<QString()> pinGenerator = identity::generatePairingPin, QObject* parent = nullptr);
    ~DeckPairingController() override;
    QVariantMap state() const { return state_; }
    QVariantList savedHosts() const { return hosts_; }
    bool busy() const { return worker_ != nullptr; }
    Q_INVOKABLE bool start(const QString& address, int httpPort);
    Q_INVOKABLE bool startTrusted(const QString& address, int httpPort);
    Q_INVOKABLE void cancel();
    Q_INVOKABLE bool removeHost(const QString& hostId, bool localOnly);
    Q_INVOKABLE void reset();
signals:
    void stateChanged();
    void savedHostsChanged();
private:
    struct Shared;
    bool startPairing(const QString& address, int httpPort, bool trusted);
    void poll();
    void refreshHosts();
    QString directory_;
    PairTransportFactory factory_;
    std::function<QString()> pinGenerator_;
    QVariantMap state_;
    QVariantList hosts_;
    std::optional<identity::DeckMoonlightIdentity> savedIdentity_;
    bool removing_ = false;
    QTimer timer_;
    QThread* worker_ = nullptr;
    std::shared_ptr<Shared> shared_;
};
} // namespace nova::deck::runtime
