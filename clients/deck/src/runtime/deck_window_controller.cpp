#include "runtime/deck_window_controller.h"
#include <QGuiApplication>
#include <QKeyEvent>
#include <QScreen>
#include <algorithm>

namespace nova::deck::runtime {
QRect deckWindowGeometry(QRect requested, QRect available) {
    if (available.isEmpty()) return requested;
    if (!requested.isValid()) requested = QRect(available.center() - QPoint(640, 400), QSize(1280, 800));
    requested.setSize({std::clamp(requested.width(), std::min(640, available.width()), available.width()),
                       std::clamp(requested.height(), std::min(400, available.height()), available.height())});
    requested.moveLeft(std::clamp(requested.left(), available.left(), available.right() - requested.width() + 1));
    requested.moveTop(std::clamp(requested.top(), available.top(), available.bottom() - requested.height() + 1));
    return requested;
}

DeckWindowController::DeckWindowController(QObject* parent) : QObject(parent) {
    // Gamescope owns the display in Game Mode. A desktop launch starts as an
    // ordinary window unless the player has already chosen a mode there. Keep
    // Game Mode preferences separate so it cannot overwrite that desktop choice.
    const bool gameMode = qEnvironmentVariable("XDG_CURRENT_DESKTOP").contains("gamescope", Qt::CaseInsensitive)
        || qEnvironmentVariable("XDG_SESSION_DESKTOP").contains("gamescope", Qt::CaseInsensitive);
    settings_.beginGroup(gameMode ? QStringLiteral("GameModeWindow") : QStringLiteral("Window"));
    fullscreen_ = settings_.value("fullscreen", gameMode).toBool();
    maximized_ = settings_.value("maximized", false).toBool();
    normalGeometry_ = settings_.value("geometry").toRect();
    screenName_ = settings_.value("screen").toString();
    geometryTimer_.setSingleShot(true);
    geometryTimer_.setInterval(200);
    connect(&geometryTimer_, &QTimer::timeout, this, &DeckWindowController::rememberGeometry);
    qApp->installEventFilter(this);
    connect(qApp, &QGuiApplication::screenRemoved, this, [this] {
        // Qt first moves windows off the disappearing screen.
        QTimer::singleShot(0, this, [this] {
            if (!window_) return;
            emit modeAboutToChange();
            if (window_->visibility() == QWindow::Windowed) fitToScreen();
        });
    });
}

DeckWindowController::~DeckWindowController() { rememberGeometry(); }

void DeckWindowController::save() {
    settings_.setValue("fullscreen", fullscreen_);
    settings_.setValue("maximized", maximized_);
    settings_.setValue("geometry", normalGeometry_);
    settings_.setValue("screen", screenName_);
}

void DeckWindowController::rememberGeometry() {
    if (!window_ || applying_) return;
    if (window_->visibility() == QWindow::Windowed) normalGeometry_ = window_->geometry();
    geometries_[window_] = {normalGeometry_, maximized_};
    if (window_->screen()) screenName_ = window_->screen()->name();
    save();
}

void DeckWindowController::fitToScreen() {
    if (!window_ || !window_->screen()) return;
    const auto margins = window_->frameMargins();
    const auto available = window_->screen()->availableGeometry().marginsRemoved(margins);
    normalGeometry_ = deckWindowGeometry(normalGeometry_, available);
    // Wayland intentionally lets the compositor place top-level windows.
    if (QGuiApplication::platformName().startsWith("wayland")) window_->resize(normalGeometry_.size());
    else window_->setGeometry(normalGeometry_);
}

void DeckWindowController::applyMode() {
    if (!window_) return;
    applying_ = true;
    if (fullscreen_) window_->showFullScreen();
    else if (maximized_) window_->showMaximized();
    else { window_->showNormal(); fitToScreen(); }
    applying_ = false;
}

void DeckWindowController::watchWindow(QWindow* window) {
    if (window_ == window) return;
    rememberGeometry(); geometryTimer_.stop();
    for (const auto& connection : connections_) disconnect(connection);
    connections_.clear();
    window_ = window;
    if (!window_) return;
    if (!normalGeometry_.isValid()) normalGeometry_ = window_->geometry();
    if (const auto found = geometries_.constFind(window); found != geometries_.cend()) {
        normalGeometry_ = found->normal;
        maximized_ = found->maximized;
    } else {
        connect(window, &QObject::destroyed, this, [this, window] { geometries_.remove(window); });
    }
    for (auto* screen : QGuiApplication::screens()) {
        if (screen->name() == screenName_) { window_->setScreen(screen); break; }
    }
    connections_ << connect(window_, &QWindow::visibilityChanged, this, [this](QWindow::Visibility visibility) {
        if (applying_ || visibility == QWindow::Hidden || visibility == QWindow::Minimized) return;
        const bool next = visibility == QWindow::FullScreen;
        if (fullscreen_ != next) { emit modeAboutToChange(); fullscreen_ = next; emit changed(); }
        if (!next) maximized_ = visibility == QWindow::Maximized;
        save(); geometryTimer_.start();
    });
    const auto geometryChanged = [this] { if (!applying_) geometryTimer_.start(); };
    connections_ << connect(window_, &QWindow::xChanged, this, geometryChanged)
                 << connect(window_, &QWindow::yChanged, this, geometryChanged)
                 << connect(window_, &QWindow::widthChanged, this, geometryChanged)
                 << connect(window_, &QWindow::heightChanged, this, geometryChanged)
                 << connect(window_, &QWindow::screenChanged, this, [this] {
                     if (applying_) return;
                     emit modeAboutToChange();
                     geometryTimer_.start();
                 });
    applyMode();
}

void DeckWindowController::setFullscreen(bool value) {
    if (fullscreen_ == value) return;
    rememberGeometry(); geometryTimer_.stop();
    emit modeAboutToChange();
    fullscreen_ = value;
    applyMode(); save(); emit changed();
}

bool DeckWindowController::eventFilter(QObject* watched, QEvent* event) {
    if (!window_ || watched != window_) return false;
    if (event->type() == QEvent::ShortcutOverride || event->type() == QEvent::KeyPress || event->type() == QEvent::KeyRelease) {
        auto* key = static_cast<QKeyEvent*>(event);
        const auto modifiers = Qt::ControlModifier | Qt::AltModifier | Qt::ShiftModifier;
        const bool chord = key->key() == Qt::Key_F && (key->modifiers() & modifiers) == modifiers;
        if (key->key() == Qt::Key_F && (chord || shortcutHeld_)) {
            if (event->type() == QEvent::KeyPress && !key->isAutoRepeat() && !shortcutHeld_) {
                shortcutHeld_ = true; toggleFullscreen();
            } else if (event->type() == QEvent::KeyRelease && !key->isAutoRepeat()) shortcutHeld_ = false;
            event->accept(); return true;
        }
    }
    if (event->type() == QEvent::Close) rememberGeometry();
    if (event->type() == QEvent::FocusOut || event->type() == QEvent::Hide) shortcutHeld_ = false;
    return false;
}
}
