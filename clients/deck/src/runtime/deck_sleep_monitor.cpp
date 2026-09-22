#include "runtime/deck_sleep_monitor.h"

#include <QDBusMessage>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#include <QDBusServiceWatcher>
#include <QDBusVariant>

namespace nova::deck::runtime {
namespace {
const QString service = QStringLiteral("org.freedesktop.login1");
const QString path = QStringLiteral("/org/freedesktop/login1");
const QString manager = QStringLiteral("org.freedesktop.login1.Manager");
}

DeckSleepMonitor::DeckSleepMonitor(bool enabled, QDBusConnection bus, QObject* parent)
    : QObject(parent), bus_(std::move(bus)) {
    deadline_.setSingleShot(true);
    deadline_.setTimerType(Qt::PreciseTimer);
    // Host policy may allow less. This is only our upper bound, never a promise
    // that network/driver cleanup must finish before the machine can sleep.
    deadline_.setInterval(3000);
    connect(&deadline_, &QTimer::timeout, this, &DeckSleepMonitor::releaseDelay);
    if (!enabled || !bus_.isConnected()) return;
    bus_.connect(service, path, manager, "PrepareForSleep", this, SLOT(prepareForSleep(bool)));
    auto* watcher = new QDBusServiceWatcher(service, bus_, QDBusServiceWatcher::WatchForOwnerChange, this);
    connect(watcher, &QDBusServiceWatcher::serviceOwnerChanged, this, [this](const QString&, const QString&, const QString&) {
        refresh();
    });
    QTimer::singleShot(0, this, &DeckSleepMonitor::refresh);
}

void DeckSleepMonitor::releaseDelay() {
    deadline_.stop();
    inhibitor_ = {};
}

void DeckSleepMonitor::finishPreparation() {
    if (sleeping_) releaseDelay();
}

void DeckSleepMonitor::refresh() {
    const auto generation = ++generation_;
    const auto revision = revision_;
    acquiring_ = false;
    releaseDelay();
    auto message = QDBusMessage::createMethodCall(service, path, "org.freedesktop.DBus.Properties", "Get");
    message.setArguments({manager, QStringLiteral("PreparingForSleep")});
    message.setAutoStartService(false);
    auto* call = new QDBusPendingCallWatcher(bus_.asyncCall(message, 1500), this);
    connect(call, &QDBusPendingCallWatcher::finished, this, [this, call, generation, revision] {
        const QDBusPendingReply<QDBusVariant> reply = *call;
        call->deleteLater();
        if (generation != generation_ || revision != revision_) return;
        if (reply.isError() || reply.value().variant().metaType().id() != QMetaType::Bool) {
            // Service loss cannot strand the app in an artificial asleep state.
            // Recovery remains manual and still rechecks the pinned host.
            known_ = false;
            if (sleeping_) { sleeping_ = false; emit sleepingChanged(false); }
            return;
        }
        prepareForSleep(reply.value().variant().toBool());
    });
}

void DeckSleepMonitor::prepareForSleep(bool sleeping) {
    ++revision_; // a pending property snapshot must not overwrite a newer signal
    if (known_ && sleeping_ == sleeping) {
        if (!sleeping) acquireDelay();
        return;
    }
    ++generation_; // discard late inhibitor replies across sleep/wake or service replacement
    acquiring_ = false;
    known_ = true;
    sleeping_ = sleeping;
    if (sleeping) deadline_.start();
    else { releaseDelay(); acquireDelay(); }
    emit sleepingChanged(sleeping);
}

void DeckSleepMonitor::acquireDelay() {
    if (sleeping_ || acquiring_ || inhibitor_.isValid()) return;
    acquiring_ = true;
    const auto generation = generation_;
    auto message = QDBusMessage::createMethodCall(service, path, manager, "Inhibit");
    message.setArguments({QStringLiteral("sleep"), QStringLiteral("Nova"),
        QStringLiteral("Release game controls and close the stream"), QStringLiteral("delay")});
    message.setAutoStartService(false);
    auto* call = new QDBusPendingCallWatcher(bus_.asyncCall(message, 1500), this);
    connect(call, &QDBusPendingCallWatcher::finished, this, [this, call, generation] {
        const QDBusPendingReply<QDBusUnixFileDescriptor> reply = *call;
        call->deleteLater();
        if (generation != generation_) return;
        acquiring_ = false;
        // Denied/unavailable delay support must never prevent normal streaming.
        if (!reply.isError() && !sleeping_) inhibitor_ = reply.value();
    });
}

} // namespace nova::deck::runtime
