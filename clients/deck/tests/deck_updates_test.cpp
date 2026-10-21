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
#include <QSysInfo>
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
    const auto builtArchitecture = deckUpdateArchitecture();
    const QJsonObject valid{{"appId", "com.papi_ux.Nova"}, {"channel", "beta"}, {"arch", builtArchitecture}, {"commit", b}, {"version", "v1.4.13-beta.1"}};
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
    auto x86Catalog = valid; x86Catalog["arch"] = "x86_64";
    auto armCatalog = valid; armCatalog["arch"] = "aarch64";
    require(parseDeckUpdateCatalog(QJsonDocument(armCatalog).toJson(), "beta", "aarch64").has_value(),
        "ARM catalog refused for an ARM built identity");
    require(!parseDeckUpdateCatalog(QJsonDocument(armCatalog).toJson(), "beta", "x86_64"),
        "ARM catalog admitted to x86 built identity");
    require(!parseDeckUpdateCatalog(QJsonDocument(x86Catalog).toJson(), "beta", "aarch64"),
        "x86 catalog admitted to ARM built identity despite identical commit");
    for (const auto& unsupported : {QString{}, QStringLiteral("arm"), QStringLiteral("arm64"), QStringLiteral("riscv64")}) {
        require(!parseDeckUpdateCatalog(QJsonDocument(valid).toJson(), "beta", unsupported),
            "unsupported catalog identity admitted");
    }
    require(deckUpdateArchitecture("arm64") == "aarch64" && deckUpdateArchitecture("x86_64") == "x86_64",
        "built Qt ABI was not mapped to its Flatpak architecture");
    for (const auto& unsupported : {QString{}, QStringLiteral("arm"), QStringLiteral("aarch64"), QStringLiteral("riscv64")})
        require(deckUpdateArchitecture(unsupported).isEmpty(), "unsupported built Qt ABI fell back to x86");
    require(deckUpdateArchitecture() == deckUpdateArchitecture(QSysInfo::buildCpuArchitecture()),
        "production identity did not use Qt's built ABI");
    require(qgetenv("NOVA_DECK_PRIVATE_UPDATE_BUS") == "1" && qEnvironmentVariableIsSet("DBUS_SESSION_BUS_ADDRESS"),
        "use CTest's private update bus");
    const auto address = QString::fromUtf8(qgetenv("DBUS_SESSION_BUS_ADDRESS"));
    auto server = QDBusConnection::connectToBus(address, "update-server");
    auto client = QDBusConnection::connectToBus(address, "update-client");
    QTemporaryDir dir; require(dir.isValid(), "temp settings");
    QFile file(dir.filePath("flatpak-info")); require(file.open(QIODevice::WriteOnly), "instance fixture");
    file.write(("[Application]\nname=com.papi_ux.Nova\n[Instance]\narch=" + builtArchitecture + "\nbranch=beta\napp-commit=" + a + "\n").toUtf8()); file.close();
    const auto installed=DeckUpdates::installedOptions(true);
    require(installed.feedUrl == QStringLiteral(NOVA_DECK_EXPECTED_UPDATE_URL), "installed update feed differs from configured production base");
    DeckUpdateOptions options;
    options.instanceFile = file.fileName(); options.settingsFile = dir.filePath("settings.ini");
    options.channel = "beta"; options.feedUrl = "https://127.0.0.1:1"; options.idleDelayMs = 40;
    Portal portal(server);
    const auto regression = [&](const QString& name) {
        // Each case owns an opt-out store and measures portal counters relative
        // to its start, so the existing full lifecycle fixture stays unchanged.
        auto isolated = options;
        isolated.settingsFile = dir.filePath(name + ".ini");
        if (name == "manual") {
            for (const auto& arch : {QStringLiteral("x86_64"), QStringLiteral("aarch64")}) {
                auto manual = isolated; manual.architecture = arch; manual.channel = "unpublished";
                DeckUpdates updates(manual, client);
                const auto message = updates.state()["message"].toString();
                require(!updates.state()["supported"].toBool() && message.contains("manually")
                    && !message.contains("Install a channel"), "manual bundle promised an unpublished update channel");
                if (arch == "aarch64") require(message.contains("ARM64") && message.contains("x86_64"),
                    "Frame manual update guidance lost the architecture boundary");
            }
            return;
        }
        DeckUpdates updates(isolated, client); updates.setBlocked(false);
        if (name == "equal") {
            // Defensive event-handler input, without a monitor or a catalog
            // request: this is not a claim that the real portal emits it early.
            require(QMetaObject::invokeMethod(&updates, "available", Qt::DirectConnection,
                Q_ARG(QVariantMap, (QVariantMap{{"running-commit", a}, {"local-commit", a}, {"remote-commit", a}}))),
                "cannot deliver defensive current-commit event");
            require(!updates.state()["message"].toString().contains("You're up to date"),
                "uncompleted check claimed up to date");
            return;
        }
        if (name == "outcome") {
            require(updates.state()["lastCheck"] == "none", "fresh update state claimed a completed check");
            updates.check();
            require(updates.state()["checking"].toBool() && updates.state()["lastCheck"] == "none",
                "in-flight first check claimed success");
            until([&] { return !updates.state()["checking"].toBool(); });
            require(updates.state()["lastCheck"] == "failed", "offline result lost its typed failure");
            updates.check();
            require(updates.state()["checking"].toBool() && updates.state()["lastCheck"] == "failed",
                "recheck discarded the last completed result");
            until([&] { return !updates.state()["checking"].toBool(); });
            return;
        }
        const auto creates = portal.creates, installs = portal.installs;
        updates.check();
        until([&] { return portal.creates == creates + 1 && !updates.state()["checking"].toBool(); });
        portal.offer(); until([&] { return updates.state()["canInstall"].toBool(); });
        updates.install(); until([&] { return portal.installs == installs + 1; });
        portal.send("Progress", {{"status", 0u}, {"progress", 40u}});
        until([&] { return updates.state()["progress"].toInt() == 40; });
        require(server.unregisterService(service), "release private updater for recreation");
        until([&] { return !updates.busy() && updates.state()["message"].toString().contains("disconnected"); });
        require(server.registerService(service), "reclaim private updater for recreation");
        updates.install(); // Check synchronously, before CreateUpdateMonitor replies.
        require(updates.busy() && updates.state()["progress"].toInt() == 0,
            "new pending install exposed stale progress");
        until([&] { return portal.installs == installs + 2; });
        portal.send("Progress", {{"status", 3u}, {"error", "org.freedesktop.portal.Error.Cancelled"}});
        until([&] { return !updates.busy(); });
        require(updates.state()["canInstall"].toBool() && updates.state()["lastCheck"] == "failed",
            "cancelled installation lost retry or changed the completed check");
    };
    for (const auto& name : {QStringLiteral("outcome"), QStringLiteral("equal"), QStringLiteral("progress"), QStringLiteral("manual")}) {
        if (app.arguments().contains("--update-regression=" + name)) {
            regression(name); std::cout << name.toStdString() << " regression passed\n"; return 0;
        }
    }
    for (const auto& architecture : {QStringLiteral("x86_64"), QStringLiteral("aarch64")}) {
        const auto instanceFile = dir.filePath(architecture + ".flatpak-info");
        QFile instance(instanceFile); require(instance.open(QIODevice::WriteOnly), "architecture instance fixture");
        instance.write(("[Application]\nname=com.papi_ux.Nova\n[Instance]\narch=" + architecture
            + "\nbranch=beta\napp-commit=" + a + "\n").toUtf8()); instance.close();
        auto matching = options; matching.instanceFile = instanceFile; matching.architecture = architecture;
        DeckUpdates sameArch(matching, client); sameArch.setBlocked(false);
        require(sameArch.state()["supported"].toBool(), "matching built/installed architecture refused");
        auto crossed = matching; crossed.architecture = architecture == "x86_64" ? "aarch64" : "x86_64";
        DeckUpdates otherArch(crossed, client); otherArch.setBlocked(false); otherArch.check(); otherArch.install();
        require(!otherArch.state()["supported"].toBool() && portal.creates == 0 && portal.installs == 0,
            "cross-architecture instance reached the updater portal");
        auto unknown = matching; unknown.architecture = "riscv64";
        DeckUpdates unsupported(unknown, client); unsupported.setBlocked(false); unsupported.check(); unsupported.install();
        require(!unsupported.state()["supported"].toBool() && portal.creates == 0 && portal.installs == 0,
            "unsupported built identity reached the updater portal");
    }
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
        portal.offer(a, a); pump(20);
        require(updates.state()["message"].toString().contains("Couldn't check") && updates.state()["canCheck"].toBool(),
            "portal current signal hid unavailable feed or disabled retry");
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
    for (const auto& name : {QStringLiteral("outcome"), QStringLiteral("equal"), QStringLiteral("progress"), QStringLiteral("manual")}) regression(name);
    std::cout << "Update presentation regressions passed\n";

}
