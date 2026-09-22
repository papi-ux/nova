#pragma once
#include "runtime/deck_native_session.h"
#include <QPointer>
#include <QWindow>

namespace nova::deck::runtime {
// Only the real presentation window is watched. Vulkan's redirected Quick
// window receives the same event later and must never forward it a second time.
class DeckDesktopInputBridge final : public QObject {
public:
    DeckDesktopInputBridge(DeckNativeSessionController& session, DeckPlaySettings& settings, QObject* parent = nullptr);
    ~DeckDesktopInputBridge() override;
    void watchWindow(QWindow* window, QObject* content);
protected:
    bool eventFilter(QObject* watched, QEvent* event) override;
private:
    bool capturing() const;
    bool localPointer(QPointF point) const;
    void submit(const std::optional<stream::DeckDesktopPacket>& packet);
    DeckNativeSessionController& session_;
    DeckPlaySettings& settings_;
    QPointer<QWindow> window_;
    QPointer<QObject> content_;
    stream::DeckDesktopRouter router_;
    bool localGesture_ = false;
    QMetaObject::Connection destroyed_;
};
}
