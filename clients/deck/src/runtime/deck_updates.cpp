#include "runtime/deck_updates.h"

#include <QCoreApplication>
#include <QDBusMessage>
#include <QDBusObjectPath>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#include <QDBusServiceWatcher>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QRegularExpression>
#include <QSettings>
#include <QUuid>
#include <algorithm>
#include <memory>

namespace nova::deck::runtime {
namespace {
const QString service = QStringLiteral("org.freedesktop.portal.Flatpak");
const QString portalPath = QStringLiteral("/org/freedesktop/portal/Flatpak");
const QString monitorInterface = service + QStringLiteral(".UpdateMonitor");
bool commit(const QString& value) {
    static const QRegularExpression pattern(QStringLiteral("\\A[0-9a-f]{64}\\z"));
    return pattern.match(value).hasMatch();
}
std::unique_ptr<QSettings> preferences(const QString& path) {
    return path.isEmpty() ? std::make_unique<QSettings>() : std::make_unique<QSettings>(path, QSettings::IniFormat);
}
}

std::optional<DeckUpdateCatalog> parseDeckUpdateCatalog(const QByteArray& bytes, const QString& channel) {
    if (bytes.size() > 16384) return {};
    const auto object = QJsonDocument::fromJson(bytes).object();
    const QString revision = object.value("commit").toString();
    const QString version = object.value("version").toString();
    static const QRegularExpression versionPattern(QStringLiteral("\\Av[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?\\z"));
    if (object.value("appId") != "com.papi_ux.Nova" || object.value("channel").toString() != channel
        || object.value("arch") != "x86_64" || !commit(revision) || version.size() > 80
        || !versionPattern.match(version).hasMatch()) return {};
    return DeckUpdateCatalog{revision, version};
}

DeckUpdateOptions DeckUpdates::installedOptions(bool enabled) {
    DeckUpdateOptions options;
    options.enabled = enabled;
    options.channel = QStringLiteral(NOVA_DECK_UPDATE_CHANNEL);
    options.feedUrl = QStringLiteral(NOVA_DECK_UPDATE_URL);
    return options;
}

DeckUpdates::DeckUpdates(DeckUpdateOptions options, QDBusConnection bus, QObject* parent)
    : QObject(parent), options_(std::move(options)), bus_(std::move(bus)) {
    automatic_ = preferences(options_.settingsFile)->value("Updates/automatic", false).toBool();
    if (!options_.enabled || !QFileInfo::exists(options_.instanceFile)) {
        message_ = "Update this installation using your package manager or a new Nova download.";
        return;
    }
    QSettings instance(options_.instanceFile, QSettings::IniFormat);
    const QString branch = instance.value("Instance/branch").toString();
    running_ = instance.value("Instance/app-commit").toString();
    local_ = running_;
    supported_ = instance.value("Application/name") == "com.papi_ux.Nova"
        && instance.value("Instance/arch") == "x86_64"
        && commit(running_) && QStringList{"stable", "beta", "pyrowave"}.contains(options_.channel)
        && branch == options_.channel && QUrl(options_.feedUrl).scheme() == "https";
    if (!supported_) {
        message_ = "This copy has no Nova update channel. Install a channel from Nova's Downloads page once to enable in-app updates. Your pairing and settings stay on this device.";
        return;
    }
    message_ = "Updates will be checked when Nova is idle.";
    idle_.setSingleShot(true);
    idle_.setInterval(options_.idleDelayMs);
    connect(&idle_, &QTimer::timeout, this, [this] {
        if (automatic_ && canInstall() && attempted_ != remote_) {
            attempted_ = remote_;
            install();
        }
    });
    periodic_.setInterval(6 * 60 * 60 * 1000);
    connect(&periodic_, &QTimer::timeout, this, [this] { if (!blocked_) check(); });
    periodic_.start();
    auto* watcher = new QDBusServiceWatcher(service, bus_, QDBusServiceWatcher::WatchForOwnerChange, this);
    connect(watcher, &QDBusServiceWatcher::serviceOwnerChanged, this,
        [this](const QString&, const QString& oldOwner, const QString&) {
            if (oldOwner.isEmpty()) return;
            closeMonitor();
            failInstall("The system updater disconnected. Check for updates to try again.");
        });
    QTimer::singleShot(10000, this, [this] { if (!blocked_) check(); });
}

DeckUpdates::~DeckUpdates() { closeMonitor(); }

QVariantMap DeckUpdates::state() const {
    const bool restart = restartRequired_ || (!local_.isEmpty() && local_ != running_);
    return {{"supported", supported_}, {"version", QCoreApplication::applicationVersion()},
        {"channel", options_.channel}, {"automatic", automatic_}, {"checking", checking_},
        {"installing", installing_}, {"progress", progress_}, {"message", message_},
        {"available", !remote_.isEmpty() && remote_ != local_}, {"latestVersion", latestVersion_},
        {"restartRequired", restart}, {"canFinish", restart && !blocked_ && !installing_},
        {"canCheck", supported_ && !checking_ && !installing_ && !blocked_},
        {"canInstall", canInstall()}, {"blocked", blocked_}};
}

bool DeckUpdates::canInstall() const {
    return supported_ && !blocked_ && !installing_ && !checking_ && !restartRequired_ && local_ == running_
        && commit(remote_) && remote_ != local_;
}

void DeckUpdates::setBlocked(bool blocked) {
    if (blocked_ == blocked) return;
    blocked_ = blocked;
    if (blocked_) idle_.stop();
    else {
        scheduleAutomatic();
        if (supported_ && !checked_) QTimer::singleShot(10000, this, [this] { if (!blocked_ && !checked_) check(); });
    }
    emit stateChanged();
}

void DeckUpdates::scheduleAutomatic() {
    if (automatic_ && canInstall() && attempted_ != remote_ && !idle_.isActive()) idle_.start();
}

bool DeckUpdates::setAutomatic(bool enabled) {
    if (!supported_) return false;
    auto settings = preferences(options_.settingsFile);
    const auto previous = settings->value("Updates/automatic", false);
    settings->setValue("Updates/automatic", enabled);
    settings->sync();
    if (settings->status() != QSettings::NoError) {
        settings->setValue("Updates/automatic", previous);
        message_ = "Couldn't save the automatic update setting.";
        emit stateChanged();
        return false;
    }
    automatic_ = enabled;
    if (!enabled) idle_.stop(); else scheduleAutomatic();
    emit stateChanged();
    return true;
}

void DeckUpdates::check() {
    if (!supported_ || blocked_ || checking_ || installing_) return;
    checking_ = true;
    checked_ = true;
    message_ = "Checking for updates…";
    emit stateChanged();
    ensureMonitor();
    QUrl url(options_.feedUrl + "/" + options_.channel + ".json");
    QNetworkRequest request(url);
    request.setTransferTimeout(15000);
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::ManualRedirectPolicy);
    auto* reply = network_.get(request);
    reply->setReadBufferSize(16385);
    connect(reply, &QNetworkReply::readyRead, this, [reply] { if (reply->bytesAvailable() > 16384) reply->abort(); });
    connect(reply, &QNetworkReply::finished, this, [this, reply] {
        checking_ = false;
        const auto bytes = reply->readAll();
        const auto catalog = parseDeckUpdateCatalog(bytes, options_.channel);
        if (reply->error() != QNetworkReply::NoError || reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt() != 200
            || !catalog) {
            message_ = "Couldn't check the update feed. Check your connection and try again.";
        } else {
            remote_ = catalog->commit;
            latestVersion_ = catalog->version;
            message_ = restartRequired_ || local_ != running_ ? "Update installed. Close and reopen Nova to use it."
                : remote_ == local_ ? "You're up to date on this channel." : "A Nova update is available.";
            scheduleAutomatic();
        }
        reply->deleteLater();
        emit stateChanged();
    });
}

void DeckUpdates::ensureMonitor() {
    if (!monitor_.isEmpty() || creating_) return;
    const auto generation = ++generation_;
    creating_ = true;
    const QString token = "nova_" + QUuid::createUuid().toString(QUuid::Id128);
    QString sender = bus_.baseService().mid(1); sender.replace('.', '_');
    monitor_ = portalPath + "/update_monitor/" + sender + "/" + token;
    bus_.connect(service, monitor_, monitorInterface, "UpdateAvailable", this, SLOT(available(QVariantMap)));
    bus_.connect(service, monitor_, monitorInterface, "Progress", this, SLOT(progress(QVariantMap)));
    auto message = QDBusMessage::createMethodCall(service, portalPath, service, "CreateUpdateMonitor");
    message.setArguments({QVariantMap{{"handle_token", token}}});
    auto* call = new QDBusPendingCallWatcher(bus_.asyncCall(message, 10000), this);
    connect(call, &QDBusPendingCallWatcher::finished, this, [this, call, generation] {
        const QDBusPendingReply<QDBusObjectPath> reply = *call;
        call->deleteLater();
        if (generation != generation_) return;
        creating_ = false;
        if (reply.isError() || reply.value().path() != monitor_) {
            closeMonitor();
            failInstall("The system updater isn't available. Use your system's software manager or try again.");
            return;
        }
        if (pendingInstall_) {
            pendingInstall_ = false;
            if (blocked_) failInstall("Update postponed until the current session or operation finishes.");
            else startInstall();
        }
    });
}

void DeckUpdates::closeMonitor() {
    ++generation_;
    creating_ = false;
    if (monitor_.isEmpty()) return;
    bus_.disconnect(service, monitor_, monitorInterface, "UpdateAvailable", this, SLOT(available(QVariantMap)));
    bus_.disconnect(service, monitor_, monitorInterface, "Progress", this, SLOT(progress(QVariantMap)));
    auto message = QDBusMessage::createMethodCall(service, monitor_, monitorInterface, "Close");
    message.setAutoStartService(false);
    bus_.asyncCall(message, 3000);
    monitor_.clear();
}

void DeckUpdates::setInstalling(bool value) {
    if (installing_ == value) return;
    installing_ = value;
    emit busyChanged();
    emit stateChanged();
}

void DeckUpdates::install() {
    if (!canInstall()) return;
    attempted_ = remote_;
    ensureMonitor();
    if (creating_) { pendingInstall_ = true; setInstalling(true); return; }
    if (monitor_.isEmpty()) return;
    startInstall();
}

void DeckUpdates::startInstall() {
    progress_ = 0;
    message_ = "Updating Nova… Keep Nova open until this finishes.";
    setInstalling(true); // synchronously blocks new sessions before the async call
    const auto generation = generation_;
    auto message = QDBusMessage::createMethodCall(service, monitor_, monitorInterface, "Update");
    message.setArguments({QString{}, QVariantMap{}});
    auto* call = new QDBusPendingCallWatcher(bus_.asyncCall(message, 10000), this);
    connect(call, &QDBusPendingCallWatcher::finished, this, [this, call, generation] {
        const QDBusPendingReply<> reply = *call;
        call->deleteLater();
        if (generation != generation_ || !installing_) return;
        if (reply.isError() && reply.error().type() == QDBusError::NoReply) {
            // A method timeout does not prove that Flatpak's transaction or
            // permission prompt ended. Retain the session exclusion until a
            // terminal Progress signal or service-owner loss establishes that.
            message_ = "Waiting for the system updater. Complete any permission prompt in your desktop's software manager.";
            emit stateChanged();
            return;
        }
        if (reply.isError()) failInstall(reply.error().name() == "org.freedesktop.DBus.Error.NotSupported"
            ? "This update needs new permissions. Install it through your system's software manager."
            : "The update couldn't start. Check your connection and system update permissions, then try again.");
    });
}

void DeckUpdates::failInstall(const QString& error) {
    pendingInstall_ = false;
    message_ = error;
    setInstalling(false);
    emit stateChanged();
}

void DeckUpdates::available(const QVariantMap& info) {
    const auto running = info.value("running-commit").toString();
    const auto local = info.value("local-commit").toString();
    const auto remote = info.value("remote-commit").toString();
    if (running != running_ || !commit(local) || !commit(remote)) return;
    local_ = local;
    remote_ = remote;
    if (!installing_ && !checking_) message_ = restartRequired_ || local_ != running_
        ? "Update installed. Close and reopen Nova to use it."
        : remote_ != local_ ? "A Nova update is available." : "You're up to date on this channel.";
    scheduleAutomatic();
    emit stateChanged();
}

void DeckUpdates::progress(const QVariantMap& info) {
    if (!installing_ || pendingInstall_) return;
    bool valid = false;
    const auto status = info.value("status").toUInt(&valid);
    if (!valid || status > 3) return;
    progress_ = std::clamp(info.value("progress").toInt(), 0, 100);
    if (status == 2) {
        // Progress confirms completion, not the exact installed commit. Only
        // UpdateAvailable may provide that identity; never invent it from the catalog.
        restartRequired_ = true;
        message_ = "Update installed. Close and reopen Nova to use it.";
        setInstalling(false);
    } else if (status == 1) {
        remote_ = local_;
        message_ = restartRequired_ || local_ != running_ ? "Update installed. Close and reopen Nova to use it." : "No update was available from your installed channel.";
        setInstalling(false);
    } else if (status == 3) {
        failInstall(info.value("error").toString() == "org.freedesktop.DBus.Error.NotSupported"
            ? "This update needs new permissions. Install it through your system's software manager."
            : "The update didn't finish. Check your connection and system update permissions, then try again.");
    }
    emit stateChanged();
}

void DeckUpdates::finishUpdate() {
    if (!blocked_ && !installing_ && (restartRequired_ || (!local_.isEmpty() && local_ != running_))) emit quitRequested();
}

} // namespace nova::deck::runtime
