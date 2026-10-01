#pragma once
#include <QObject>
#include <QByteArray>
#include <QHostAddress>
#include <QVariantMap>
#include <functional>
#include <optional>

namespace nova::deck::runtime {
std::optional<QString> normalizedWakeMac(const QString& input);
QByteArray deckWakeMagicPacket(const QString& mac);
// Returns an empty error only after the socket accepted the complete datagram.
QString sendDeckWakePacket(const QByteArray& packet, const QHostAddress& destination = QHostAddress::Broadcast, quint16 port = 9);
using DeckWakeSender = std::function<QString(const QByteArray&)>;
class DeckHostWakeController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
public:
    explicit DeckHostWakeController(QString fileName = {}, DeckWakeSender sender = {}, QObject* parent = nullptr);
    QVariantMap state() const { return state_; }
    void setTarget(QString hostId, QString hostName);
    void setBlocked(bool blocked);
    Q_INVOKABLE bool saveMac(const QString& input);
    Q_INVOKABLE bool wake();
signals:
    void stateChanged();
private:
    QString savedMac() const;
    void publish(QString copy = {});
    QString fileName_, hostId_, hostName_;
    DeckWakeSender sender_;
    QVariantMap state_;
    bool blocked_ = false;
};
} // namespace nova::deck::runtime
