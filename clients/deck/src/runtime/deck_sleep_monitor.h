#pragma once

#include <QDBusConnection>
#include <QDBusUnixFileDescriptor>
#include <QObject>
#include <QTimer>

namespace nova::deck::runtime {

// logind notifications plus a bounded delay inhibitor. Never requests sleep,
// changes system settings or blocks sleep indefinitely. The injectable bus is
// used by tests with a private daemon and a fake login service.
class DeckSleepMonitor final : public QObject {
    Q_OBJECT
public:
    explicit DeckSleepMonitor(bool enabled, QDBusConnection bus = QDBusConnection::systemBus(), QObject* parent = nullptr);
    bool sleeping() const { return sleeping_; }
    bool hasDelayInhibitor() const { return inhibitor_.isValid(); }
    void finishPreparation();
signals:
    void sleepingChanged(bool sleeping);
private slots:
    void prepareForSleep(bool sleeping);
private:
    void refresh();
    void acquireDelay();
    void releaseDelay();
    QDBusConnection bus_;
    QDBusUnixFileDescriptor inhibitor_;
    QTimer deadline_;
    bool known_ = false, sleeping_ = false, acquiring_ = false;
    quint64 generation_ = 0, revision_ = 0;
};

} // namespace nova::deck::runtime
