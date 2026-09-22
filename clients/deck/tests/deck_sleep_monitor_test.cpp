#include "runtime/deck_sleep_monitor.h"

#include <QCoreApplication>
#include <QDBusMessage>
#include <QDBusVariant>
#include <QDBusVirtualObject>
#include <QElapsedTimer>
#include <QThread>
#include <fcntl.h>
#include <unistd.h>
#include <cstdlib>
#include <iostream>
#include <optional>
#include <vector>

using nova::deck::runtime::DeckSleepMonitor;
namespace {
void require(bool ok, const char* message) {
    if (!ok) { std::cerr << message << '\n'; std::exit(1); }
}
template<class Predicate> void until(Predicate predicate, int timeout = 2500) {
    QElapsedTimer timer; timer.start();
    while (!predicate() && timer.elapsed() < timeout) {
        QCoreApplication::processEvents();
        QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
        QThread::msleep(1);
    }
    require(predicate(), "sleep monitor fixture timed out");
}
const QString service = "org.freedesktop.login1";
const QString path = "/org/freedesktop/login1";
const QString manager = "org.freedesktop.login1.Manager";

class LoginService : public QDBusVirtualObject {
public:
    explicit LoginService(QDBusConnection bus) : bus(std::move(bus)) {
        require(this->bus.registerVirtualObject(path, this), "cannot register fake manager");
        claim();
    }
    ~LoginService() override {
        bus.unregisterService(service);
        bus.unregisterObject(path);
        for (const int fd : readers) ::close(fd);
    }
    void claim() { require(bus.registerService(service), "cannot claim private login service"); }
    QString introspect(const QString&) const override { return {}; }
    bool handleMessage(const QDBusMessage& message, const QDBusConnection&) override {
        if (message.interface() == "org.freedesktop.DBus.Properties" && message.member() == "Get") {
            require(message.arguments() == QVariantList{manager, QStringLiteral("PreparingForSleep")}, "wrong sleep property");
            ++gets;
            if (holdProperty) property = message;
            else replyProperty(message, asleep);
            return true;
        }
        if (message.interface() == manager && message.member() == "Inhibit") {
            ++inhibits;
            const auto args = message.arguments();
            require(args.size() == 4 && args[0] == "sleep" && args[1] == "Nova" && args[3] == "delay",
                "requested an unbounded or unrelated power inhibitor");
            if (deny) bus.send(message.createErrorReply("org.freedesktop.DBus.Error.AccessDenied", "fixture denial"));
            else if (holdInhibit) pendingInhibit = message;
            else replyInhibit(message);
            return true;
        }
        require(false, "monitor requested an unexpected power operation");
        return false;
    }
    void replyProperty(const QDBusMessage& message, bool value) {
        bus.send(message.createReply({QVariant::fromValue(QDBusVariant(value))}));
    }
    void replyInhibit(const QDBusMessage& message) {
        int pipeFds[2];
        require(::pipe(pipeFds) == 0, "cannot create delay lifetime probe");
        ::fcntl(pipeFds[0], F_SETFL, O_NONBLOCK);
        readers.push_back(pipeFds[0]);
        QDBusUnixFileDescriptor fd(pipeFds[1]);
        ::close(pipeFds[1]);
        require(bus.send(message.createReply({QVariant::fromValue(fd)})), "cannot send inhibitor descriptor");
    }
    bool released(int index) { char byte; return ::read(readers.at(index), &byte, 1) == 0; }
    void signal(bool value) {
        asleep = value;
        auto message = QDBusMessage::createSignal(path, manager, "PrepareForSleep");
        message.setArguments({value});
        require(bus.send(message), "cannot emit private sleep event");
    }
    QDBusConnection bus;
    bool asleep = false, deny = false, holdProperty = false, holdInhibit = false;
    int gets = 0, inhibits = 0;
    std::optional<QDBusMessage> property, pendingInhibit;
    std::vector<int> readers;
};
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    // CTest runs this executable only inside dbus-run-session. Do not claim a
    // login name on a user's ordinary bus when invoked directly by mistake.
    require(qgetenv("NOVA_DECK_PRIVATE_SLEEP_BUS") == "1" && qEnvironmentVariableIsSet("DBUS_SESSION_BUS_ADDRESS"),
        "run this fixture through CTest on its private daemon");
    const auto address = QString::fromUtf8(qgetenv("DBUS_SESSION_BUS_ADDRESS"));
    auto server = QDBusConnection::connectToBus(address, "sleep-fixture-server");
    auto client = QDBusConnection::connectToBus(address, "sleep-fixture-client");
    require(server.isConnected() && client.isConnected(), "private daemon unavailable");
    {
        LoginService login(server);
        DeckSleepMonitor disabled(false, client);
        DeckSleepMonitor monitor(true, client);
        std::vector<bool> events;
        QObject::connect(&monitor, &DeckSleepMonitor::sleepingChanged, &app, [&](bool asleep) { events.push_back(asleep); });
        until([&] { return monitor.hasDelayInhibitor(); });
        require(login.gets == 1 && login.inhibits == 1 && events == std::vector<bool>{false}, "startup snapshot or disabled monitor mutated service");
        login.signal(true);
        until([&] { return monitor.sleeping(); });
        require(monitor.hasDelayInhibitor() && !login.released(0), "sleep notification released delay before stream cleanup");
        login.signal(true);
        monitor.finishPreparation();
        until([&] { return login.released(0); });
        require(events == std::vector<bool>({false, true}), "duplicate sleep notified twice");
        login.signal(false);
        until([&] { return !monitor.sleeping() && monitor.hasDelayInhibitor(); });
        require(login.inhibits == 2, "wake did not rearm delay");
        login.signal(true);
        until([&] { return monitor.sleeping(); });
        QElapsedTimer deadline; deadline.start();
        until([&] { return deadline.elapsed() >= 1900; });
        login.signal(true); // duplicate notification cannot extend the bound
        until([&] { return login.released(1); }, 1800);
        require(monitor.sleeping() && !monitor.hasDelayInhibitor() && deadline.elapsed() < 3600,
            "delay deadline blocked sleep or invented wake");
        login.signal(false); // also represents an aborted sleep
        until([&] { return monitor.hasDelayInhibitor(); });
        server.unregisterService(service);
        until([&] { return login.released(2); });
        login.deny = true;
        login.claim();
        until([&] { return login.inhibits == 4; });
        login.signal(true);
        until([&] { return monitor.sleeping(); });
        require(!monitor.hasDelayInhibitor(), "denied inhibitor retained a lock");
        server.unregisterService(service);
        until([&] { return !monitor.sleeping(); });
    }
    {
        LoginService login(server);
        login.holdProperty = true;
        DeckSleepMonitor monitor(true, client);
        until([&] { return login.property.has_value(); });
        login.signal(true);
        until([&] { return monitor.sleeping(); });
        login.replyProperty(*login.property, false);
        QElapsedTimer elapsed; elapsed.start();
        until([&] { return elapsed.elapsed() > 80; });
        require(monitor.sleeping() && login.inhibits == 0, "stale awake snapshot overrode newer sleep signal");
        login.holdInhibit = true;
        login.signal(false);
        until([&] { return login.pendingInhibit.has_value(); });
        login.signal(true);
        until([&] { return monitor.sleeping(); });
        login.replyInhibit(*login.pendingInhibit);
        until([&] { return login.released(0); });
        require(!monitor.hasDelayInhibitor(), "late inhibitor kept sleeping machine blocked");
    }
    {
        LoginService login(server);
        {
            DeckSleepMonitor monitor(true, client);
            until([&] { return monitor.hasDelayInhibitor(); });
        }
        until([&] { return login.released(0); });
    }
    std::cout << "Private-bus sleep notifications, bounded delay, stale replies, denial and service recovery passed\n";
}
