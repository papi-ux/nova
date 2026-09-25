#include "runtime/deck_host_discovery.h"
#include "identity/deck_pairing.h"
#include <QCryptographicHash>
#include <QDBusMessage>
#include <QDBusObjectPath>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#include <QDBusServiceWatcher>
#include <QHostAddress>
#include <QFileInfo>
#include <QNetworkInterface>
#include <QRegularExpression>
#include <QUuid>
#include <algorithm>

namespace nova::deck::runtime {
namespace {
const QString service = QStringLiteral("org.freedesktop.Avahi");
const QString server = QStringLiteral("org.freedesktop.Avahi.Server2");
const QString browserInterface = QStringLiteral("org.freedesktop.Avahi.ServiceBrowser");
const QString streamType = QStringLiteral("_nvstream._tcp");
QString key(int interface, int protocol, const QString& name, const QString& type, const QString& domain) {
    return QString::number(interface) + QChar(0) + QString::number(protocol) + QChar(0) + name + QChar(0) + type + QChar(0) + domain;
}
bool validName(const QString& name) {
    if (name.trimmed().isEmpty() || name.toUtf8().size() > 63) return false;
    for (const auto c : name)
        if (c.category() == QChar::Other_Control || c.category() == QChar::Other_Format) return false;
    return true;
}
QString dnsTarget(QString target) {
    if (target.endsWith('.')) target.chop(1);
    static const QRegularExpression hostname(QStringLiteral(
        "\\A(?=.{1,253}\\z)[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?(?:\\.[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?)*\\z"));
    target = target.toLower();
    return hostname.match(target).hasMatch() ? target : QString{};
}
int endpointRank(const QHostAddress& address, const QNetworkInterface& network) {
    // Prefer the LAN over local container bridges and VPN adapters. Keep all
    // alternatives so removal of one advertisement can promote another route.
    int rank = 20;
    const QString sys = "/sys/class/net/" + network.name();
    if (network.isValid() && QFileInfo::exists(sys))
        rank = QFileInfo::exists(sys + "/device") ? 0 : 40;
    else if (network.type() == QNetworkInterface::Ethernet || network.type() == QNetworkInterface::Wifi)
        rank = 10;
    if (address.isLoopback() || network.flags().testFlag(QNetworkInterface::IsLoopBack)) rank = 100;
    if (address.isLinkLocal()) rank += 8;
    if (address.protocol() == QAbstractSocket::IPv6Protocol) ++rank;
    return rank;
}
QDBusMessage call(const QString& path, const QString& interface, const QString& method, const QVariantList& args = {}) {
    auto message = QDBusMessage::createMethodCall(service, path, interface, method);
    message.setArguments(args); return message;
}
}
DeckHostDiscovery::DeckHostDiscovery(QObject* parent, const QString& privateBusAddress)
    : QObject(parent), connectionName_("nova-discovery-" + QUuid::createUuid().toString(QUuid::Id128)),
      bus_(privateBusAddress.isEmpty() ? QDBusConnection::connectToBus(QDBusConnection::SystemBus, connectionName_)
                                     : QDBusConnection::connectToBus(privateBusAddress, connectionName_)) {
    deadline_.setSingleShot(true); deadline_.setInterval(8000);
    expiry_.setSingleShot(true); expiry_.setInterval(60000);
    connect(&deadline_, &QTimer::timeout, this, [this] {
        finish(hosts_.isEmpty() ? "No streaming PCs found. Check that the PC is awake and streaming is enabled, or enter its address."
                               : "Search finished. Select a PC, then choose Trusted Pair or Pair with PIN.", false);
    });
    connect(&expiry_, &QTimer::timeout, this, [this] { finish("Search results expired. Search again or enter the PC address.", true); });
    auto* watcher = new QDBusServiceWatcher(service, bus_, QDBusServiceWatcher::WatchForUnregistration, this);
    connect(watcher, &QDBusServiceWatcher::serviceUnregistered, this, [this] {
        if (busy_ || !hosts_.isEmpty()) failed({});
    });
}
DeckHostDiscovery::~DeckHostDiscovery() {
    freeBrowser(browser_);
    // A dedicated connection also releases Avahi objects whose creation reply
    // has not arrived yet; they cannot survive cancellation/destruction.
    QDBusConnection::disconnectFromBus(connectionName_);
}
QVariantMap DeckHostDiscovery::state() const { return {{"busy", busy_}, {"copy", copy_}}; }
void DeckHostDiscovery::freeBrowser(const QString& path) {
    if (!path.isEmpty()) bus_.asyncCall(call(path, browserInterface, "Free"), 2000);
}
void DeckHostDiscovery::finish(const QString& copy, bool clear) {
    ++generation_; deadline_.stop();
    if (!browser_.isEmpty()) {
        bus_.disconnect(service, browser_, browserInterface, "ItemNew", this, SLOT(itemAdded(QDBusMessage)));
        bus_.disconnect(service, browser_, browserInterface, "ItemRemove", this, SLOT(itemRemoved(QDBusMessage)));
        bus_.disconnect(service, browser_, browserInterface, "Failure", this, SLOT(failure(QDBusMessage)));
        freeBrowser(browser_); browser_.clear();
    }
    busy_ = false; copy_ = copy;
    if (clear) { expiry_.stop(); entries_.clear(); hosts_.clear(); emit hostsChanged(); }
    else if (!hosts_.isEmpty()) expiry_.start();
    emit stateChanged();
}
void DeckHostDiscovery::pause() { if (busy_) finish("Select a PC, then choose Trusted Pair or Pair with PIN.", false); }
void DeckHostDiscovery::stop() { finish("Search stopped. Search again or enter the PC address.", true); }
void DeckHostDiscovery::failed(const QString&) {
    finish("Local search is unavailable. Check that Avahi network discovery is running on this device, or enter the PC address.", true);
}
void DeckHostDiscovery::start() {
    stop(); attempts_ = 0; busy_ = true; copy_ = "Searching this network for streaming PCs…";
    const auto generation = generation_;
    emit stateChanged(); deadline_.start();
    // Prepare then subscribe then Start avoids losing cached advertisements
    // between creating a browser and connecting its D-Bus signals (Avahi 0.8+).
    auto* pending = new QDBusPendingCallWatcher(bus_.asyncCall(call("/", server, "ServiceBrowserPrepare",
        {-1, -1, streamType, QStringLiteral("local"), uint{0}}), 2500), this);
    connect(pending, &QDBusPendingCallWatcher::finished, this, [this, generation](QDBusPendingCallWatcher* watcher) {
        const QDBusPendingReply<QDBusObjectPath> reply = *watcher; watcher->deleteLater();
        if (generation != generation_ || !busy_) { if (!reply.isError()) freeBrowser(reply.value().path()); return; }
        if (reply.isError()) { failed({}); return; }
        browser_ = reply.value().path();
        if (browser_.isEmpty() || browser_ == "/") { failed({}); return; }
        const bool connected = bus_.connect(service, browser_, browserInterface, "ItemNew", this, SLOT(itemAdded(QDBusMessage)))
            && bus_.connect(service, browser_, browserInterface, "ItemRemove", this, SLOT(itemRemoved(QDBusMessage)))
            && bus_.connect(service, browser_, browserInterface, "Failure", this, SLOT(failure(QDBusMessage)));
        if (!connected) { failed({}); return; }
        auto* start = new QDBusPendingCallWatcher(bus_.asyncCall(call(browser_, browserInterface, "Start"), 2000), this);
        connect(start, &QDBusPendingCallWatcher::finished, this, [this, generation](QDBusPendingCallWatcher* watcher) {
            const QDBusPendingReply<> reply = *watcher; watcher->deleteLater();
            if (generation == generation_ && busy_ && reply.isError()) failed({});
        });
    });
}
void DeckHostDiscovery::itemAdded(const QDBusMessage& message) {
    const auto args = message.arguments();
    if (!busy_ || browser_.isEmpty() || message.path() != browser_ || args.size() != 6) return;
    added(args[0].toInt(), args[1].toInt(), args[2].toString(), args[3].toString(), args[4].toString(), args[5].toUInt());
}
void DeckHostDiscovery::itemRemoved(const QDBusMessage& message) {
    const auto args = message.arguments();
    if (!busy_ || browser_.isEmpty() || message.path() != browser_ || args.size() != 6) return;
    removed(args[0].toInt(), args[1].toInt(), args[2].toString(), args[3].toString(), args[4].toString(), args[5].toUInt());
}
void DeckHostDiscovery::failure(const QDBusMessage& message) {
    if (busy_ && !browser_.isEmpty() && message.path() == browser_) failed({});
}
void DeckHostDiscovery::added(int interface, int protocol, const QString& name, const QString& type, const QString& domain, uint) {
    if (!busy_ || interface < 1 || (protocol != 0 && protocol != 1) || !validName(name) || type != streamType || domain != "local") return;
    const auto id = key(interface, protocol, name, type, domain);
    if (entries_.contains(id) || attempts_ >= 64) return;
    ++attempts_;
    const auto request = ++request_, generation = generation_;
    entries_.insert(id, {name, interface, request, {}});
    auto* pending = new QDBusPendingCallWatcher(bus_.asyncCall(call("/", server, "ResolveService",
        {interface, protocol, name, type, domain, protocol, uint{0}}), 2500), this);
    connect(pending, &QDBusPendingCallWatcher::finished, this, [this, generation, request, id, interface, protocol, name](QDBusPendingCallWatcher* watcher) {
        const auto reply = watcher->reply(); watcher->deleteLater();
        auto entry = entries_.find(id);
        if (!busy_ || generation != generation_ || entry == entries_.end() || entry->request != request) return;
        const auto args = reply.arguments();
        if (reply.type() != QDBusMessage::ReplyMessage || args.size() != 11 || args[0].toInt() != interface ||
            args[1].toInt() != protocol || args[2].toString() != name || args[3].toString() != streamType || args[4].toString() != "local" || args[6].toInt() != protocol) return;
        QHostAddress address(args[7].toString());
        if (address.isNull() || address.isMulticast() || address == QHostAddress::AnyIPv4 || address == QHostAddress::AnyIPv6 ||
            (protocol == 0 && address.protocol() != QAbstractSocket::IPv4Protocol) ||
            (protocol == 1 && address.protocol() != QAbstractSocket::IPv6Protocol)) return;
        if (address.isLinkLocal() && address.protocol() == QAbstractSocket::IPv6Protocol) address.setScopeId(QString::number(interface));
        if (args[8].metaType().id() != QMetaType::UShort) return;
        const int port = args[8].toUInt();
        const auto endpoint = identity::pairingEndpoint(address.toString(), port);
        if (!endpoint) return;
        const auto target = dnsTarget(args[5].toString());
        if (target.isEmpty()) return;
        // A DNS-SD service can have many addresses. Its instance, SRV target
        // and port identify one suggestion, not one trusted pairing identity.
        const QString hostKey = name.toCaseFolded() + QChar(0) + target + QChar(0) + QString::number(port);
        const auto publicId = QString::fromLatin1(QCryptographicHash::hash(hostKey.toUtf8(), QCryptographicHash::Sha256).toHex());
        const auto network = QNetworkInterface::interfaceFromIndex(interface);
        auto networkName = network.humanReadableName();
        if (networkName.isEmpty()) networkName = "Network " + QString::number(interface);
        entry->rank = endpointRank(address, network);
        entry->endpoint = {{"id", publicId}, {"name", name.trimmed()}, {"address", endpoint->address}, {"port", port}, {"network", networkName}};
        publish();
    });
}
void DeckHostDiscovery::removed(int interface, int protocol, const QString& name, const QString& type, const QString& domain, uint) {
    if (busy_ && entries_.remove(key(interface, protocol, name, type, domain))) publish();
}
void DeckHostDiscovery::publish() {
    QHash<QString, const Entry*> unique;
    // Stable IDs preserve focus while a better address arrives. Different
    // targets or ports stay separate even when PCs have the same display name.
    auto keys = entries_.keys(); std::sort(keys.begin(), keys.end());
    for (const auto& key : keys) {
        const auto& entry = entries_[key];
        if (entry.endpoint.isEmpty()) continue;
        const auto id = entry.endpoint.value("id").toString();
        const auto* previous = unique.value(id, nullptr);
        if (!previous || entry.rank < previous->rank || (entry.rank == previous->rank &&
            entry.endpoint.value("address").toString() < previous->endpoint.value("address").toString())) unique.insert(id, &entry);
    }
    QList<QVariantMap> results;
    for (const auto* entry : unique) results.push_back(entry->endpoint);
    std::sort(results.begin(), results.end(), [](const auto& a, const auto& b) {
        const int byName = QString::compare(a.value("name").toString(), b.value("name").toString(), Qt::CaseInsensitive);
        return byName ? byName < 0 : a.value("id").toString() < b.value("id").toString();
    });
    hosts_.clear();
    for (const auto& result : results) { if (hosts_.size() == 32) break; hosts_.push_back(result); }
    emit hostsChanged();
}
QVariantMap DeckHostDiscovery::endpoint(const QString& id) const {
    for (const auto& host : hosts_) if (host.toMap().value("id") == id) return host.toMap();
    return {};
}
}
