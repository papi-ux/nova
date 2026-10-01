#include "runtime/deck_host_wake.h"
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QFile>
#include <QSettings>
#include <QTemporaryDir>
#include <QUdpSocket>
#include <cstdlib>
#include <iostream>
using namespace nova::deck::runtime;
void require(bool value, const char* message) { if (!value) { std::cerr << message << '\n'; std::exit(1); } }
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    const auto packet = deckWakeMagicPacket("02:11:22:33:44:55");
    require(packet.size() == 102 && packet.left(6) == QByteArray(6, char(0xff)), "Wake packet lacks its exact 6-byte sync and 16 MAC repeats");
    for (int i = 0; i < 16; ++i) require(packet.mid(6 + 6 * i, 6) == QByteArray::fromHex("021122334455"), "Wake MAC repeat differs");
    for (const QString& input : {QString(" 02-11-22-aa-BB-55 "), QString("021122AABB55"), QString("02:11:22:aa:bb:55")})
        require(normalizedWakeMac(input) == std::optional<QString>{"02:11:22:AA:BB:55"}, "MAC normalization differs by input format");
    for (const char* bad : {"", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "01:11:22:33:44:55", "02:11:22:33:44", "02:11:22:33:44:555", "02:11-22:33:44:55", "02:11:22:33:44:GG", "02 11 22 33 44 55"})
        require(!normalizedWakeMac(bad) && deckWakeMagicPacket(bad).isEmpty(), "invalid/multicast MAC accepted");
    QUdpSocket receiver;
    require(receiver.bind(QHostAddress(QHostAddress::LocalHost), 0), "loopback wake fixture did not bind");
    require(sendDeckWakePacket(packet, QHostAddress::LocalHost, receiver.localPort()).isEmpty() && receiver.waitForReadyRead(1000), "real UDP sender did not deliver loopback magic packet");
    QByteArray received(102, 0); require(receiver.readDatagram(received.data(), received.size()) == 102 && received == packet, "UDP packet changed on the wire");
    require(!sendDeckWakePacket({}).isEmpty(), "empty wake datagram accepted");
    QTemporaryDir directory; const auto file = directory.filePath("wake.ini");
    int sends = 0; QByteArray sent; bool fail = false;
    DeckHostWakeController wake(file, [&](const QByteArray& bytes) { ++sends; sent = bytes; return fail ? QString("Network unavailable") : QString{}; });
    require(!wake.saveMac("02:11:22:33:44:55") && !wake.wake() && sends == 0, "unselected host saved or sent Wake");
    wake.setTarget("host/one", "Living Room PC");
    require(!wake.state().value("canWake").toBool(), "unset host MAC enabled Wake");
    require(!wake.saveMac("FF:FF:FF:FF:FF:FF") && sends == 0 && wake.state().value("copy").toString().contains("MAC"), "bad MAC saved or sent");
    require(wake.saveMac("021122334455") && sends == 0 && wake.state().value("mac") == "02:11:22:33:44:55", "Save sent Wake or lost normalized MAC");
    DeckHostWakeController restarted(file, [](const QByteArray&) { return QString("Unexpected send"); }); restarted.setTarget("host/one", "Living Room PC");
    require(restarted.state().value("mac") == "02:11:22:33:44:55", "host MAC did not persist across restart");
    wake.setTarget("other", "Bedroom PC"); require(!wake.wake() && sends == 0 && !wake.state().value("canWake").toBool(), "another host borrowed saved MAC");
    wake.setTarget("host/one", "Living Room PC"); wake.setBlocked(true);
    require(!wake.wake() && !wake.saveMac("") && sends == 0, "busy session allowed Wake/save");
    wake.setBlocked(false); require(wake.wake() && sends == 1 && sent == packet, "Wake did not send exactly one saved-host packet");
    require(wake.state().value("copy").toString().contains("packet sent") && !wake.state().contains("awake"), "sent packet claimed confirmed PC wake");
    fail = true; require(!wake.wake() && sends == 2 && wake.state().value("copy").toString().contains("Network unavailable"), "send failure appeared successful");
    require(wake.saveMac("") && !wake.wake() && sends == 2, "clear MAC sent packet or retained Wake");
    QFile blocker(directory.filePath("blocked")); require(blocker.open(QIODevice::WriteOnly), "blocked fixture failed"); blocker.close();
    DeckHostWakeController blocked(blocker.fileName() + "/wake.ini"); blocked.setTarget("host", "PC");
    require(!blocked.saveMac("02:11:22:33:44:55") && !blocked.state().value("canWake").toBool(), "failed save appeared persisted");
    QSettings corrupt(file, QSettings::IniFormat);
    const auto key = "Wake/v1/" + QString::fromLatin1(QCryptographicHash::hash(QByteArray("host/one"), QCryptographicHash::Sha256).toHex()) + "/mac";
    corrupt.setValue(key, "FF:FF:FF:FF:FF:FF"); corrupt.sync();
    require(!restarted.wake() && !restarted.state().value("canWake").toBool(), "corrupt saved MAC became a broadcast wake target");
    std::cout << "Wake packet/UDP, MAC validation, host-scoped persistence, explicit action, busy and failure gates passed\n";
}
