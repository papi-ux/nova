#include "runtime/deck_updates.h"
#include <QCoreApplication>
#include <QDBusMessage>
#include <QDBusArgument>
#include <QDBusObjectPath>
#include <QDBusVirtualObject>
#include <QElapsedTimer>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSettings>
#include <QTemporaryDir>
#include <QThread>
#include <cstdlib>
#include <iostream>

using namespace nova::deck::runtime;
namespace {
void require(bool ok, const char* text) { if (!ok) { std::cerr << text << '\n'; std::exit(1); } }
void pump(int ms) { QElapsedTimer timer; timer.start(); while (timer.elapsed() < ms) { QCoreApplication::processEvents(); QThread::msleep(1); } }
template<class F> void until(F condition, int timeout = 3000) {
    QElapsedTimer timer; timer.start();
    while (!condition() && timer.elapsed() < timeout) pump(1);
    require(condition(), "update fixture timed out");
}
const QString service = "org.freedesktop.portal.Flatpak";
const QString path = "/org/freedesktop/portal/Flatpak";
const QString iface = service + ".UpdateMonitor";
const QString a(64, 'a'), b(64, 'b'), c(64, 'c');
class Portal : public QDBusVirtualObject {
public:
    explicit Portal(QDBusConnection bus) : bus(std::move(bus)) {
        require(this->bus.registerVirtualObject(path, this, QDBusConnection::SubPath), "register fake portal");
        require(this->bus.registerService(service), "claim private portal");
    }
    ~Portal() override { bus.unregisterService(service); bus.unregisterObject(path, QDBusConnection::UnregisterTree); }
    QString introspect(const QString&) const override { return {}; }
    bool handleMessage(const QDBusMessage& message, const QDBusConnection&) override {
        if (message.member() == "CreateUpdateMonitor") {
            ++creates;
            auto sender = message.service().mid(1); sender.replace('.', '_');
            const auto options = qdbus_cast<QVariantMap>(message.arguments().at(0));
            monitor = path + "/update_monitor/" + sender + "/" + options.value("handle_token").toString();
            require(message.interface() == service, "wrong create interface");
            bus.send(message.createReply({QVariant::fromValue(QDBusObjectPath(monitor))}));
        } else if (message.member() == "Update") {
            ++installs;
            require(message.path() == monitor && message.interface() == iface, "wrong install scope");
            require(message.arguments().size() == 2 && message.arguments()[0].toString().isEmpty()
                && qdbus_cast<QVariantMap>(message.arguments()[1]).isEmpty(), "update selected a different app, remote or branch");
            if (holdUpdate) return true;
            if (deny) bus.send(message.createErrorReply("org.freedesktop.DBus.Error.NotSupported", "test permission increase"));
            else bus.send(message.createReply());
        } else if (message.member() == "Close") { ++closes; bus.send(message.createReply()); }
        else require(false, "unexpected portal mutation");
        return true;
    }
    void send(const QString& name, QVariantMap data) {
        auto message = QDBusMessage::createSignal(monitor, iface, name);
        message.setArguments({data}); require(bus.send(message), "send private portal signal");
    }
    void offer(QString remote = b, QString local = a, QString running = a) {
        send("UpdateAvailable", {{"running-commit", running}, {"local-commit", local}, {"remote-commit", remote}});
    }
    void finish(uint status) { send("Progress", {{"status", status}, {"progress", 100u}}); }
    QDBusConnection bus;
    QString monitor;
    int creates = 0, installs = 0, closes = 0;
    bool deny = false, holdUpdate = false;
};
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    const QJsonObject valid{{"appId", "com.papi_ux.Nova"}, {"channel", "beta"}, {"arch", "x86_64"}, {"commit", b}, {"version", "v1.4.13-beta.1"}};
    require(parseDeckUpdateCatalog(QJsonDocument(valid).toJson(), "beta").has_value(), "valid catalog refused");
    for (const auto& key : {"appId", "channel", "arch", "commit", "version"}) {
        auto invalid = valid; invalid[key] = "wrong";
        require(!parseDeckUpdateCatalog(QJsonDocument(invalid).toJson(), "beta"), "foreign or malformed catalog accepted");
        invalid = valid; invalid.remove(key);
        require(!parseDeckUpdateCatalog(QJsonDocument(invalid).toJson(), "beta"), "incomplete catalog accepted");
    }
    require(!parseDeckUpdateCatalog(QByteArray(17000, ' '), "beta"), "unbounded catalog accepted");
    for (const auto& key : {"commit", "version"}) {
        auto invalid = valid; invalid[key] = invalid[key].toString() + "\n";
        require(!parseDeckUpdateCatalog(QJsonDocument(invalid).toJson(), "beta"), "trailing data in catalog identity accepted");
    }
    require(qgetenv("NOVA_DECK_PRIVATE_UPDATE_BUS") == "1" && qEnvironmentVariableIsSet("DBUS_SESSION_BUS_ADDRESS"),
        "use CTest's private update bus");
    const auto address = QString::fromUtf8(qgetenv("DBUS_SESSION_BUS_ADDRESS"));
    auto server = QDBusConnection::connectToBus(address, "update-server");
    auto client = QDBusConnection::connectToBus(address, "update-client");
    QTemporaryDir dir; require(dir.isValid(), "temp settings");
    QFile file(dir.filePath("flatpak-info")); require(file.open(QIODevice::WriteOnly), "instance fixture");
    file.write(("[Application]\nname=com.papi_ux.Nova\n[Instance]\narch=x86_64\nbranch=beta\napp-commit=" + a + "\n").toUtf8()); file.close();
    DeckUpdateOptions options;
    options.instanceFile = file.fileName(); options.settingsFile = dir.filePath("settings.ini");
    options.channel = "beta"; options.feedUrl = "https://127.0.0.1:1"; options.idleDelayMs = 40;
    Portal portal(server);
    {
        auto nativeOptions = options; nativeOptions.instanceFile = dir.filePath("missing");
        DeckUpdates native(nativeOptions, client);
        native.setBlocked(false); native.check(); native.install();
        require(!native.state()["supported"].toBool(), "native install advertised flatpak updates");
        auto wrongOptions = options; wrongOptions.channel = "pyrowave";
        DeckUpdates wrong(wrongOptions, client); wrong.setBlocked(false); wrong.check();
        require(!wrong.state()["supported"].toBool() && portal.creates == 0, "cross-channel update admitted");
    }
    {
        DeckUpdates updates(options, client);
        int quits = 0; QObject::connect(&updates, &DeckUpdates::quitRequested, [&] { ++quits; });
        require(!updates.state()["automatic"].toBool(), "automatic updates weren't opt-in");
        updates.check(); require(portal.creates == 0, "busy session checked updates");
        updates.setBlocked(false); updates.check();
        until([&] { return portal.creates == 1 && !updates.state()["checking"].toBool(); });
        require(updates.state()["message"].toString().contains("Couldn't check"), "offline check claimed current");
        portal.offer(b, a, c); pump(20);
        require(!updates.state()["available"].toBool(), "foreign running commit admitted");
        portal.offer(); until([&] { return updates.state()["canInstall"].toBool(); });
        pump(80); require(portal.installs == 0, "manual mode installed automatically");
        updates.setBlocked(true); updates.install(); updates.finishUpdate(); pump(50);
        require(portal.installs == 0 && quits == 0, "stream exclusion failed");
        require(updates.setAutomatic(true), "save automatic setting");
        pump(80); require(portal.installs == 0, "automatic install during session");
        updates.setBlocked(false);
        until([&] { return portal.installs == 1; });
        require(updates.busy(), "install did not reserve idle state");
        updates.install(); pump(20); require(portal.installs == 1, "duplicate install admitted");
        portal.finish(3); until([&] { return !updates.busy(); });
        updates.setBlocked(true); updates.setBlocked(false); pump(100);
        require(portal.installs == 1, "failed automatic update retried without a new revision");
        portal.deny = true; updates.install(); until([&] { return portal.installs == 2 && !updates.busy(); });
        require(updates.state()["message"].toString().contains("new permissions"), "permission escalation was hidden");
        portal.deny = false; updates.install(); until([&] { return portal.installs == 3; });
        portal.finish(2); until([&] { return updates.state()["restartRequired"].toBool(); });
        require(!updates.busy() && !updates.state()["canInstall"].toBool() && quits == 0, "completion auto-quit or repeated install");
        updates.setBlocked(true); updates.finishUpdate(); require(quits == 0, "quit during stream");
        updates.setBlocked(false); updates.finishUpdate(); require(quits == 1, "explicit finish did not close");
        portal.finish(3); pump(20); require(updates.state()["restartRequired"].toBool(), "late progress overwrote completion");
    }
    until([&] { return portal.closes >= 1; });
    {
        DeckUpdates restored(options, client);
        require(restored.state()["automatic"].toBool(), "automatic preference lost on restart");
        restored.setBlocked(false); restored.check();
        until([&] { return portal.creates == 2 && !restored.state()["checking"].toBool(); });
        portal.offer(b, b); until([&] { return restored.state()["restartRequired"].toBool(); });
        pump(80); require(portal.installs == 3, "already-installed update installed again");
        server.unregisterService(service);
        until([&] { return restored.state()["message"].toString().contains("disconnected"); });
        require(!restored.busy(), "portal disappearance stranded updater");
    }
    require(server.registerService(service), "reclaim private portal");
    {
        DeckUpdates waiting(options, client);
        waiting.setAutomatic(false); waiting.setBlocked(false); waiting.check();
        until([&] { return portal.creates == 3 && !waiting.state()["checking"].toBool(); });
        portal.offer(); until([&] { return waiting.state()["canInstall"].toBool(); });
        portal.holdUpdate = true; waiting.install();
        until([&] { return waiting.state()["message"].toString().startsWith("Waiting for the system updater"); }, 13000);
        require(waiting.busy() && !waiting.state()["canInstall"].toBool(), "method timeout released an unconfirmed transaction");
        portal.finish(3); until([&] { return !waiting.busy(); });
    }
    std::cout << "Private Flatpak portal: channel guards, offline checks, idle gating, opt-in persistence, install errors, completion and service loss passed.\n";
}
