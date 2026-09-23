#include "runtime/deck_host_discovery.h"
#include <QCoreApplication>
#include <QDBusMetaType>
#include <QDBusObjectPath>
#include <QDBusVirtualObject>
#include <QElapsedTimer>
#include <QThread>
#include <cstdlib>
#include <iostream>
#include <vector>
using namespace nova::deck::runtime;
namespace {
void require(bool ok, const char* why) { if (!ok) { std::cerr << why << '\n'; std::exit(1); } }
template<class F> void until(F ready, int ms = 4000) {
    QElapsedTimer timer; timer.start();
    while (!ready() && timer.elapsed() < ms) { QCoreApplication::processEvents(); QThread::msleep(2); }
    require(ready(), "discovery test timed out");
}
void settle() { QElapsedTimer timer; timer.start(); while(timer.elapsed()<80) { QCoreApplication::processEvents(); QThread::msleep(2); } }
const QString service = "org.freedesktop.Avahi", browserInterface = service + ".ServiceBrowser";
class AvahiFixture : public QDBusVirtualObject {
public:
    QDBusConnection bus = QDBusConnection::sessionBus();
    QString path;
    int prepares = 0, starts = 0, frees = 0;
    bool hold = false, cached = false;
    QDBusMessage prepare;
    std::vector<QDBusMessage> resolves;
    AvahiFixture() {
        require(bus.registerVirtualObject("/", this, QDBusConnection::SubPath) && bus.registerService(service), "private Avahi fixture unavailable");
    }
    ~AvahiFixture() { bus.unregisterService(service); bus.unregisterObject("/", QDBusConnection::UnregisterTree); }
    QString introspect(const QString&) const override { return {}; }
    bool handleMessage(const QDBusMessage& message, const QDBusConnection&) override {
        if (message.member() == "ServiceBrowserPrepare") {
            require(message.interface() == service + ".Server2" && message.arguments() == QVariantList{-1,-1,QString("_nvstream._tcp"),QString("local"),uint{0}}, "discovery browsed outside local streaming services");
            path = "/Browser" + QString::number(++prepares);
            if (hold) prepare = message;
            else bus.send(message.createReply({QVariant::fromValue(QDBusObjectPath(path))}));
        } else if (message.member() == "Start") {
            ++starts; bus.send(message.createReply());
            if (cached) item("ItemNew", "Cached PC");
        } else if (message.member() == "Free") {
            ++frees; bus.send(message.createReply());
        } else if (message.member() == "ResolveService") {
            require(message.interface() == service + ".Server2" && message.arguments().size() == 7, "unexpected resolve call");
            resolves.push_back(message);
        } else { require(false, "discovery attempted an unexpected operation"); }
        return true;
    }
    void item(const QString& event, QString name, int interface = 1, int protocol = 0, QString type = "_nvstream._tcp", QString target = {}) {
        auto message = QDBusMessage::createSignal(target.isEmpty() ? path : target, browserInterface, event);
        message.setArguments({interface, protocol, name, type, QString("local"), uint{0}}); bus.send(message);
    }
    void resolve(int index, const QString& address, quint16 port = 47989) {
        require(index >= 0 && index < int(resolves.size()), "missing resolver request");
        const auto& request = resolves[index]; const auto in = request.arguments();
        bus.send(request.createReply({in[0],in[1],in[2],in[3],in[4],QString("fixture.local"),in[5],address,
            QVariant::fromValue(port),QVariant::fromValue(QList<QByteArray>{}),uint{0}}));
    }
    void releasePrepare() { bus.send(prepare.createReply({QVariant::fromValue(QDBusObjectPath(path))})); }
};
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv); qDBusRegisterMetaType<QList<QByteArray>>();
    if (app.arguments().contains("--system-probe")) {
        DeckHostDiscovery discovery; discovery.start(); until([&] { return !discovery.state().value("busy").toBool(); },10000);
        std::cout << "System discovery finished; candidates=" << discovery.hosts().size() << "; " << discovery.state().value("copy").toString().toStdString() << '\n';
        return discovery.state().value("copy").toString().contains("unavailable") ? 1 : 0;
    }
    require(qEnvironmentVariableIsSet("NOVA_DECK_PRIVATE_DISCOVERY_BUS"), "requires disposable D-Bus session");
    const auto address = qEnvironmentVariable("DBUS_SESSION_BUS_ADDRESS");
    AvahiFixture host;
    DeckHostDiscovery discovery(nullptr, address);
    require(discovery.hosts().isEmpty() && host.prepares == 0, "opening pairing started discovery automatically");
    host.cached = true; discovery.start(); until([&] { return host.resolves.size()==1; }); host.cached = false;
    host.resolve(0,"192.0.2.1"); until([&] { return discovery.hosts().size()==1; });
    const auto first = discovery.hosts()[0].toMap();
    require(first.value("name")=="Cached PC" && first.value("port")==47989 && !first.contains("trusted"), "advertisement became a trust decision");
    host.item("ItemNew","Cached PC",2); until([&] { return host.resolves.size()==2; }); host.resolve(1,"192.0.2.1"); settle();
    require(discovery.hosts().size()==1 && discovery.endpoint(first.value("id").toString())==first,"duplicate endpoint duplicated or changed selected result");
    host.item("ItemNew","Cached PC",3); until([&] { return host.resolves.size()==3; }); host.resolve(2,"192.0.2.2",48000); until([&] { return discovery.hosts().size()==2; });
    host.item("ItemRemove","Cached PC",1); host.item("ItemRemove","Cached PC",2); until([&] { return discovery.hosts().size()==1; });
    require(discovery.endpoint(first.value("id").toString()).isEmpty(),"removed endpoint still selectable");
    host.item("ItemNew","IPv6 PC",4,1); until([&] { return host.resolves.size()==4; }); host.resolve(3,"fe80::1234"); until([&] { return discovery.hosts().size()==2; });
    bool scoped = false; for(const auto& item:discovery.hosts()) scoped |= item.toMap().value("address")=="fe80::1234%4";
    require(scoped,"link-local IPv6 lost its interface scope");
    host.item("ItemNew","Late PC"); until([&] { return host.resolves.size()==5; }); host.item("ItemRemove","Late PC"); settle(); host.resolve(4,"192.0.2.3"); settle();
    require(discovery.hosts().size()==2,"late resolver resurrected removed host");
    host.item("ItemNew","Bad PC"); until([&] { return host.resolves.size()==6; }); host.resolve(5,"224.0.0.1");
    host.item("ItemNew",QString("Spoof")+QChar(0x202e)); host.item("ItemNew","Wrong service",1,0,"_ssh._tcp"); settle();
    require(host.resolves.size()==6 && discovery.hosts().size()==2,"malformed advertisement was accepted");
    discovery.pause(); require(!discovery.state().value("busy").toBool() && discovery.hosts().size()==2,"selection discarded fresh endpoints");
    until([&] { return host.frees==1; });
    const auto oldPath = host.path;
    discovery.start(); until([&] { return host.starts==2; });
    host.item("ItemNew","Old browser",1,0,"_nvstream._tcp",oldPath); settle(); require(host.resolves.size()==6,"old browser leaked into a new search");
    host.item("ItemNew","Cancelled PC"); until([&] { return host.resolves.size()==7; }); discovery.stop(); host.resolve(6,"192.0.2.4"); settle();
    require(discovery.hosts().isEmpty() && discovery.endpoint(first.value("id").toString()).isEmpty(),"cancelled result was selectable");
    const int beforeFree=host.frees;
    host.hold=true; discovery.start(); until([&] { return host.prepares==3; }); discovery.stop(); host.releasePrepare(); until([&] { return host.frees>beforeFree; });
    host.hold=false;
    discovery.start(); until([&] { return host.starts==3; });
    for(int i=0;i<80;++i) host.item("ItemNew","PC "+QString::number(i));
    until([&] { return host.resolves.size()==71; }); settle(); require(host.resolves.size()==71,"discovery request budget was unbounded");
    for(int i=7;i<71;++i) host.resolve(i,"192.0.2."+QString::number(i));
    until([&] { return discovery.hosts().size()==32; }); settle(); require(discovery.hosts().size()==32,"result budget was unbounded");
    host.bus.unregisterService(service); until([&] { return !discovery.state().value("busy").toBool() && discovery.hosts().isEmpty(); });
    discovery.start(); until([&] { return !discovery.state().value("busy").toBool(); });
    require(discovery.state().value("copy").toString().contains("unavailable"),"missing discovery service was not actionable");
    std::cout << "Local discovery lifecycle, duplicates, scope, malformed and stale records, cancellation and service loss passed\n";
}
