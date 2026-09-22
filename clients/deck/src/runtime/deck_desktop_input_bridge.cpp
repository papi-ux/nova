#include "deck_desktop_input_bridge.h"
#include <QGuiApplication>
#include <QMouseEvent>
#include <QQuickItem>
#include <QWheelEvent>
#include <algorithm>

namespace nova::deck::runtime {
using namespace stream;
DeckDesktopInputBridge::DeckDesktopInputBridge(DeckNativeSessionController& session, DeckPlaySettings& settings, QObject* parent)
    : QObject(parent), session_(session), settings_(settings) {
    qApp->installEventFilter(this);
    const auto sync = [this] { router_.capture(capturing()); };
    connect(&session_, &DeckNativeSessionController::controlsChanged, this, sync);
    connect(&session_, &DeckNativeSessionController::stateChanged, this, sync);
    connect(qApp, &QGuiApplication::applicationStateChanged, this, [this](Qt::ApplicationState state) {
        if (state != Qt::ApplicationActive) { session_.setInputFocus(false); router_.focusLost(); localGesture_ = false; }
    });
}
DeckDesktopInputBridge::~DeckDesktopInputBridge() { watchWindow(nullptr, nullptr); }
void DeckDesktopInputBridge::watchWindow(QWindow* window, QObject* content) {
    if (window == window_ && content == content_) return;
    if (window_) session_.showControls();
    disconnect(destroyed_);
    router_.focusLost(); localGesture_ = false;
    window_ = window; content_ = content;
    if (window_) destroyed_ = connect(window_, &QObject::destroyed, this, [this] {
        session_.setInputFocus(false); router_.focusLost(); content_ = nullptr;
    });
}
bool DeckDesktopInputBridge::capturing() const {
    return window_ && window_->isActive() && session_.capturesDesktopInput();
}
bool DeckDesktopInputBridge::localPointer(QPointF point) const {
    if (!content_) return false;
    for (const auto* name : {"native-show-controls", "nova-stream-hud"}) {
        auto* item = content_->findChild<QQuickItem*>(QString::fromLatin1(name));
        if (item && item->isVisible() && item->isEnabled() && item->contains(item->mapFromScene(point))) return true;
    }
    return false;
}
void DeckDesktopInputBridge::submit(const std::optional<DeckDesktopPacket>& packet) {
    if (packet) session_.sendDesktopInput(*packet);
}
bool DeckDesktopInputBridge::eventFilter(QObject* watched, QEvent* event) {
    if (watched != window_ || !window_) return false;
    if (event->type() == QEvent::FocusOut || event->type() == QEvent::Hide || event->type() == QEvent::Close) {
        session_.setInputFocus(false); router_.focusLost(); localGesture_ = false;
        return false;
    }
    if (event->type() == QEvent::FocusIn) { session_.setInputFocus(true); return false; }
    const bool active = capturing();
    router_.capture(active);
    if (event->type() == QEvent::ShortcutOverride && active) { event->accept(); return true; }
    if (event->type() == QEvent::KeyPress || event->type() == QEvent::KeyRelease) {
        auto& key = *static_cast<QKeyEvent*>(event);
        const bool down = event->type() == QEvent::KeyPress;
        const auto menuModifiers = Qt::ControlModifier | Qt::AltModifier | Qt::ShiftModifier;
        if (active && down && !key.isAutoRepeat() && key.key() == Qt::Key_M &&
            (key.modifiers() & menuModifiers) == menuModifiers) {
            session_.showControls();
            // Observe M while paused, so Resume cannot replay the shortcut.
            router_.key(deckDesktopKey(key, QGuiApplication::platformName()), true);
            return true;
        }
        submit(router_.key(deckDesktopKey(key, QGuiApplication::platformName()), down, key.isAutoRepeat()));
        return active; // Unknown keys stay out of local shortcuts during play.
    }
    const auto position = [this](QPointF point, bool clamp) {
        return session_.desktopPosition(point, window_->size(),
            deckVideoScaleMode(settings_.videoScaleMode()).value_or(DeckVideoScaleMode::Fit), clamp);
    };
    if (event->type() == QEvent::MouseMove || event->type() == QEvent::MouseButtonPress ||
        event->type() == QEvent::MouseButtonRelease || event->type() == QEvent::MouseButtonDblClick) {
        auto& mouse = *static_cast<QMouseEvent*>(event);
        // Touch stays available for Nova's controls. Native touch forwarding
        // and touch-as-trackpad require their own gesture ownership policy.
        if (mouse.source() != Qt::MouseEventNotSynthesized) return false;
        const bool press = event->type() == QEvent::MouseButtonPress || event->type() == QEvent::MouseButtonDblClick;
        const bool release = event->type() == QEvent::MouseButtonRelease;
        if (!active || localGesture_ || (!router_.dragging() && localPointer(mouse.position()))) {
            if (press) { localGesture_ = true; router_.button(deckDesktopButton(mouse.button()), true, false); }
            if (release) { router_.button(deckDesktopButton(mouse.button()), false); if (!mouse.buttons()) localGesture_ = false; }
            return false;
        }
        const auto mapped = position(mouse.position(), router_.dragging());
        if (mapped) submit(mapped);
        if (press && mapped) submit(router_.button(deckDesktopButton(mouse.button()), true));
        if (release) submit(router_.button(deckDesktopButton(mouse.button()), false));
        return true;
    }
    if (event->type() == QEvent::Wheel && active) {
        const auto& wheel = *static_cast<QWheelEvent*>(event);
        if (localGesture_ || localPointer(wheel.position())) return false;
        const auto mapped = position(wheel.position(), false);
        if (!mapped) return true;
        submit(mapped);
        // Both Qt and GameStream use 120 units per wheel notch. Pixel-only
        // touchpad gestures need a separate, explicit sensitivity policy.
        const auto delta = wheel.angleDelta();
        if (!delta.isNull()) session_.sendDesktopInput({DeckDesktopPacket::Scroll, 0,
            std::clamp(delta.x(), -32768, 32767), std::clamp(delta.y(), -32768, 32767)});
        return true;
    }
    if (event->type() == QEvent::Leave && router_.dragging()) session_.showControls();
    return false;
}
}
