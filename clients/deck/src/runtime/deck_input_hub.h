#pragma once

#include "stream/deck_players.h"
#include "stream/deck_controller_input.h"
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QVariantList>
#include <memory>
#include <vector>

namespace nova::deck::runtime {
class DeckNativeSessionController;

class DeckInputHub final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool available READ available NOTIFY availabilityChanged)
    Q_PROPERTY(QVariantList players READ players NOTIFY playersChanged)
    Q_PROPERTY(bool primaryHeld READ primaryHeld NOTIFY primaryHeldChanged)
public:
    explicit DeckInputHub(QObject* parent = nullptr);
    ~DeckInputHub() override;
    bool available() const { return !devices_.empty(); }
    QVariantList players() const;
    bool primaryHeld() const { return !primaryOwner_.empty(); }
    void setNativeSession(DeckNativeSessionController* session);
    Q_INVOKABLE void activateFocusedItem();
    Q_INVOKABLE bool reassignPlayers();
signals:
    void availabilityChanged();
    void playersChanged();
    void primaryHeldChanged();
    void primaryActionPressed(int count);
    void secondaryActionPressed(int count);
private:
    struct Device;
    void scan();
    void read(Device& device);
    void remove(Device& device);
    void syncFocus();
    bool neutral(stream::DeckControllerState state) const;
    void navigationKey(int key);
    QTimer scanTimer_;
    stream::DeckPlayers assignments_;
    std::vector<std::unique_ptr<Device>> devices_;
    QPointer<DeckNativeSessionController> session_;
    unsigned serial_ = 0;
    int primaryCount_ = 0, secondaryCount_ = 0;
    std::string primaryOwner_;
};
} // namespace nova::deck::runtime
