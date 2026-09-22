#pragma once
#include "runtime/deck_steam_shortcuts.h"
#include <QObject>
#include <QImage>
#include <QThread>
#include <QTimer>
#include <QVariantMap>
#include <atomic>
#include <functional>

namespace nova::deck::runtime {
struct DeckGameLink { QString host, game, destination = "desktop"; };
QString encodeGameLink(const DeckGameLink& link);
std::optional<DeckGameLink> decodeGameLink(const QString& value);
DeckSteamShortcut steamGameShortcut(const DeckGameLink& link, const QString& title);
DeckShortcutWriteResult writeGameShortcut(const std::vector<std::filesystem::path>& files,
    const DeckSteamShortcut& shortcut, const QMap<QString, QImage>& artwork, const std::function<std::optional<bool>()>& steamRunning);

class DeckGameShortcuts final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
public:
    explicit DeckGameShortcuts(QObject* parent = nullptr);
    ~DeckGameShortcuts() override;
    QVariantMap state() const;
    void shutdown();
    void setCatalog(QString host, QString destination, QVariantList games);
    void setImageReader(std::function<QImage(const QString&)> reader) { reader_ = std::move(reader); }
    Q_INVOKABLE bool add(const QString& game);
signals:
    void stateChanged();
private:
    void poll();
    struct Job;
    QString host_, destination_, copy_;
    QVariantList games_;
    std::function<QImage(const QString&)> reader_;
    std::shared_ptr<std::atomic<quint64>> generation_ = std::make_shared<std::atomic<quint64>>(0);
    std::shared_ptr<Job> job_;
    QThread* worker_ = nullptr;
    QTimer timer_;
    bool ok_ = false, retry_ = false;
};
}
