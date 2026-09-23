#pragma once
#include <QDBusConnection>
#include <QDBusMessage>
#include <QHash>
#include <QObject>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>

namespace nova::deck::runtime {
// Advertisements are untrusted endpoint suggestions. Pairing remains explicit
// and uses the existing identity/certificate/trusted-network checks.
class DeckHostDiscovery final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
    Q_PROPERTY(QVariantList hosts READ hosts NOTIFY hostsChanged)
public:
    explicit DeckHostDiscovery(QObject* parent = nullptr, const QString& privateBusAddress = {});
    ~DeckHostDiscovery() override;
    QVariantMap state() const;
    QVariantList hosts() const { return hosts_; }
    Q_INVOKABLE void start();
    Q_INVOKABLE void stop();
    Q_INVOKABLE void pause();
    Q_INVOKABLE QVariantMap endpoint(const QString& id) const;
signals:
    void stateChanged();
    void hostsChanged();
private slots:
    void itemAdded(const QDBusMessage& message);
    void itemRemoved(const QDBusMessage& message);
    void failure(const QDBusMessage& message);
private:
    void added(int interface, int protocol, const QString& name, const QString& type, const QString& domain, uint flags);
    void removed(int interface, int protocol, const QString& name, const QString& type, const QString& domain, uint flags);
    void failed(const QString& error);
    struct Entry { QString name; int interface; quint64 request; QVariantMap endpoint; };
    void finish(const QString& copy, bool clear);
    void publish();
    void freeBrowser(const QString& path);
    QString connectionName_;
    QDBusConnection bus_;
    QTimer deadline_, expiry_;
    QString browser_, copy_ = QStringLiteral("Search this network for streaming PCs, or enter an address below.");
    bool busy_ = false;
    quint64 generation_ = 0, request_ = 0;
    int attempts_ = 0;
    QHash<QString, Entry> entries_;
    QVariantList hosts_;
};
}
