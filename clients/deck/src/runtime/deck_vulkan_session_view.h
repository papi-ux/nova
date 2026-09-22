#pragma once
#include "runtime/deck_native_session.h"
#include "runtime/deck_display_capabilities.h"
#include "runtime/deck_desktop_input_bridge.h"
#include "stream/deck_vulkan_video_window.h"
#include <QQuickItem>

namespace nova::deck::runtime {

// Rehosts the existing native-stream popup; it does not duplicate controls or
// session ownership. Opt-in only, with the ordinary library window retained.
class DeckVulkanSessionView final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool ready READ ready NOTIFY changed)
    Q_PROPERTY(QString error READ error NOTIFY changed)
public:
    DeckVulkanSessionView(DeckNativeSessionController& session, DeckDisplayCapabilities& display, DeckPlaySettings& settings, DeckDesktopInputBridge& desktopInput,
        bool allowSoftware = false, QObject* parent = nullptr);
    ~DeckVulkanSessionView() override;
    bool ready() const { return ready_; }
    QString error() const { return error_; }
    Q_INVOKABLE void attach(QObject* popup);
    Q_INVOKABLE void detach();
    stream::DeckVulkanVideoWindow* window() { return &window_; }
signals:
    void changed();
private:
    void restore(bool keepPopup);
    DeckNativeSessionController& session_;
    DeckDisplayCapabilities& display_;
    DeckDesktopInputBridge& desktopInput_;
    stream::DeckVulkanVideoWindow window_;
    QPointer<QObject> popup_;
    QPointer<QQuickItem> originalParent_;
    QPointer<QWindow> library_;
    bool ready_ = false, restoring_ = false, closing_ = false;
    QString error_;
    QMetaObject::Connection popupDestroyed_;
};
}
