#include "runtime/deck_host_wake.h"
#include <QCryptographicHash>
#include <QRegularExpression>
#include <QSettings>
#include <QUdpSocket>
#include <memory>

namespace nova::deck::runtime {
namespace {
QString macKey(const QString& id) {
    return "Wake/v1/" + QString::fromLatin1(QCryptographicHash::hash(id.toUtf8(), QCryptographicHash::Sha256).toHex()) + "/mac";
}
std::unique_ptr<QSettings> settings(const QString& file) {
    return file.isEmpty() ? std::make_unique<QSettings>() : std::make_unique<QSettings>(file, QSettings::IniFormat);
}
}
std::optional<QString> normalizedWakeMac(const QString& input) {
    const auto text = input.trimmed();
    static const QRegularExpression format("^(?:[0-9A-Fa-f]{12}|(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}|(?:[0-9A-Fa-f]{2}-){5}[0-9A-Fa-f]{2})$");
    if (!format.match(text).hasMatch()) return {};
    auto hex = text; hex.remove(':'); hex.remove('-');
    const auto bytes = QByteArray::fromHex(hex.toLatin1());
    if (bytes.size() != 6 || (quint8(bytes[0]) & 1) || bytes == QByteArray(6, '\0')) return {};
    QStringList parts;
    for (int i = 0; i < 12; i += 2) parts.append(hex.mid(i, 2).toUpper());
    return parts.join(':');
}
QByteArray deckWakeMagicPacket(const QString& mac) {
    const auto normalized = normalizedWakeMac(mac);
    if (!normalized) return {};
    auto hex = *normalized; hex.remove(':');
    const auto address = QByteArray::fromHex(hex.toLatin1());
    QByteArray packet(6, char(0xff));
    for (int i = 0; i < 16; ++i) packet.append(address);
    return packet;
}
QString sendDeckWakePacket(const QByteArray& packet, const QHostAddress& destination, quint16 port) {
    if (packet.size() != 102 || destination.isNull() || port == 0) return "The Wake packet or network destination is invalid.";
    QUdpSocket socket;
    return socket.writeDatagram(packet, destination, port) == packet.size() ? QString{} : socket.errorString();
}
DeckHostWakeController::DeckHostWakeController(QString fileName, DeckWakeSender sender, QObject* parent)
    : QObject(parent), fileName_(std::move(fileName)), sender_(sender ? std::move(sender) : DeckWakeSender([](const QByteArray& packet) { return sendDeckWakePacket(packet); })) { publish(); }
void DeckHostWakeController::setTarget(QString id, QString name) {
    if (id == hostId_ && name == hostName_) return;
    hostId_ = id.size() <= 128 ? std::move(id) : QString{};
    hostName_ = std::move(name); publish();
}
void DeckHostWakeController::setBlocked(bool blocked) { if (blocked_ != blocked) { blocked_ = blocked; publish(); } }
QString DeckHostWakeController::savedMac() const {
    if (hostId_.isEmpty()) return {};
    const auto store = settings(fileName_);
    const auto value = store->value(macKey(hostId_));
    if (store->status() != QSettings::NoError || value.metaType().id() != QMetaType::QString) return {};
    return normalizedWakeMac(value.toString()).value_or(QString{});
}
void DeckHostWakeController::publish(QString copy) {
    const auto mac = savedMac();
    const bool canSave = !hostId_.isEmpty() && !blocked_;
    if (copy.isEmpty()) copy = blocked_ ? "Finish the current action before changing Wake PC."
        : hostId_.isEmpty() ? "Select a saved PC before setting up Wake PC."
        : mac.isEmpty() ? "Enter and save this PC's wired network adapter MAC address."
        : "Ready to send a Wake packet to this PC on your local network.";
    state_ = {{"hostId", hostId_}, {"hostName", hostName_}, {"mac", mac}, {"copy", copy},
        {"canSave", canSave}, {"canWake", canSave && !mac.isEmpty()}};
    emit stateChanged();
}
bool DeckHostWakeController::saveMac(const QString& input) {
    if (hostId_.isEmpty() || blocked_) { publish(); return false; }
    const bool clear = input.trimmed().isEmpty();
    const auto mac = normalizedWakeMac(input);
    if (!clear && !mac) { publish("Enter a valid unicast MAC address, such as 02:11:22:33:44:55. Zero and multicast addresses cannot wake a PC."); return false; }
    auto store = settings(fileName_);
    const auto key = macKey(hostId_);
    const auto previous = store->value(key);
    if (clear) store->remove(key); else store->setValue(key, *mac);
    store->sync();
    if (store->status() != QSettings::NoError) {
        if (previous.isValid()) store->setValue(key, previous); else store->remove(key);
        publish("Couldn't save this PC's Wake MAC address. Check local storage access, then try again."); return false;
    }
    publish(clear ? "Wake MAC address cleared for this PC." : "Wake MAC address saved for this PC. Choose Wake PC when you want to send a packet."); return true;
}
bool DeckHostWakeController::wake() {
    if (hostId_.isEmpty() || blocked_) { publish(); return false; }
    const auto packet = deckWakeMagicPacket(savedMac());
    if (packet.isEmpty()) { publish("Save this PC's valid wired MAC address before choosing Wake PC."); return false; }
    const auto error = sender_(packet);
    if (!error.isEmpty()) { publish("Couldn't send the Wake packet: " + error.left(240) + ". Check this device's local network connection, then try again."); return false; }
    publish("Wake packet sent. This does not confirm the PC is awake. Wait a moment, then refresh the PC. If it stays offline, check Wake-on-LAN in its firmware and wired network adapter settings."); return true;
}
} // namespace nova::deck::runtime
