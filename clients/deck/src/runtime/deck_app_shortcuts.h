#pragma once
#include "runtime/deck_steam_shortcuts.h"
#include <QObject>
#include <QThread>
#include <QTimer>
#include <QVariantMap>
#include <functional>
#include <memory>

namespace nova::deck::runtime {
DeckSteamShortcut novaSteamShortcut(bool insideFlatpak, const std::string& executable);
DeckShortcutWriteResult registerNovaSteamShortcut(const DeckSteamShortcut& shortcut, bool insideFlatpak);

class DeckAppShortcuts final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
public:
    using Operation = std::function<DeckShortcutWriteResult()>;
    explicit DeckAppShortcuts(Operation operation = {}, QObject* parent = nullptr);
    ~DeckAppShortcuts() override;
    QVariantMap state() const;
    void setBlocked(bool blocked);
    void shutdown();
    Q_INVOKABLE bool add();
signals:
    void stateChanged();
private:
    void poll();
    Operation operation_;
    QThread* worker_ = nullptr;
    QTimer timer_;
    std::shared_ptr<DeckShortcutWriteResult> result_;
    QString copy_;
    bool blocked_ = false, ok_ = false, attempted_ = false;
};
}
