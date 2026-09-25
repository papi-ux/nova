#pragma once

#include <QDBusConnection>
#include <QNetworkAccessManager>
#include <QObject>
#include <QTimer>
#include <QVariantMap>
#include <optional>

namespace nova::deck::runtime {

struct DeckUpdateCatalog { QString commit, version; };
std::optional<DeckUpdateCatalog> parseDeckUpdateCatalog(const QByteArray& bytes, const QString& channel);

struct DeckUpdateOptions {
    bool enabled = true;
    QString instanceFile = QStringLiteral("/.flatpak-info");
    QString settingsFile;
    QString channel;
    QString feedUrl;
    int idleDelayMs = 15000;
};

// The HTTPS catalog is advisory. Only Flatpak's portal installs updates, from
// the installed application's existing remote and branch, with its trust policy.
class DeckUpdates final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
public:
    explicit DeckUpdates(DeckUpdateOptions options, QDBusConnection bus = QDBusConnection::sessionBus(), QObject* parent = nullptr);
    ~DeckUpdates() override;
    static DeckUpdateOptions installedOptions(bool enabled);
    QVariantMap state() const;
    bool busy() const { return installing_; }
    void setBlocked(bool blocked);
    Q_INVOKABLE void check();
    Q_INVOKABLE void install();
    Q_INVOKABLE bool setAutomatic(bool enabled);
    Q_INVOKABLE void finishUpdate();
signals:
    void stateChanged();
    void busyChanged();
    void quitRequested();
private slots:
    void available(const QVariantMap& info);
    void progress(const QVariantMap& info);
private:
    bool canInstall() const;
    void ensureMonitor();
    void startInstall();
    void closeMonitor();
    void scheduleAutomatic();
    void failInstall(const QString& error);
    void setInstalling(bool value);
    DeckUpdateOptions options_;
    QDBusConnection bus_;
    QNetworkAccessManager network_;
    QTimer periodic_, idle_;
    QString running_, local_, remote_, monitor_, attempted_;
    QString message_, latestVersion_;
    bool supported_ = false, blocked_ = true, automatic_ = false;
    bool checking_ = false, creating_ = false, installing_ = false, pendingInstall_ = false;
    bool restartRequired_ = false, checked_ = false;
    int progress_ = 0;
    quint64 generation_ = 0;
};

} // namespace nova::deck::runtime
