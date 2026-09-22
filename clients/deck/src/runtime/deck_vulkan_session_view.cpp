#include "runtime/deck_vulkan_session_view.h"
#include <QQuickWindow>
#include <QMetaObject>

namespace nova::deck::runtime {
DeckVulkanSessionView::DeckVulkanSessionView(DeckNativeSessionController& session,
        DeckDisplayCapabilities& display, DeckPlaySettings& settings, DeckDesktopInputBridge& desktopInput, bool allowSoftware, QObject* parent)
    : QObject(parent), session_(session), display_(display), desktopInput_(desktopInput), window_(allowSoftware) {
    window_.setTitle(QStringLiteral("Nova"));
    window_.enableQuickOverlay();
    const auto updateScale = [this, &settings] {
        window_.setVideoScaleMode(stream::deckVideoScaleMode(settings.videoScaleMode()).value_or(stream::DeckVideoScaleMode::Fit));
    };
    updateScale();
    connect(&settings, &DeckPlaySettings::videoScaleModeChanged, this, updateScale);
    connect(&window_, &stream::DeckVulkanVideoWindow::presentationChanged, this, [this] {
        if (restoring_ || !popup_) return;
        const auto state = window_.presentationState();
        const bool next = state.ready && state.error.isEmpty();
        if (ready_ != next || error_ != state.error) {
            ready_ = next; error_ = state.error; emit changed();
        }
        if (!error_.isEmpty()) {
            // Keep the existing popup available on the library window so its
            // Back/Cancel controls and the presentation error remain visible.
            session_.closeSession();
            restore(true);
        }
    });
    connect(&window_, &stream::DeckVulkanVideoWindow::closeRequested, this, [this] {
        if (popup_) QMetaObject::invokeMethod(popup_, "close");
        detach();
    });
}

DeckVulkanSessionView::~DeckVulkanSessionView() {
    closing_ = true;
    if (session_.busy()) session_.closeSession();
    restore(false);
}

void DeckVulkanSessionView::attach(QObject* popup) {
    if (!popup || popup_ || session_.busy()) return;
    auto* parent = qvariant_cast<QQuickItem*>(popup->property("parent"));
    if (!parent || !parent->window()) return;
    popup_ = popup; originalParent_ = parent; library_ = parent->window();
    error_.clear(); ready_ = false;
    if (!session_.setPresentationSink(&window_, &window_, window_.composedFrames())) {
        error_ = QStringLiteral("The streaming display is busy. Return to the library and try again.");
        restore(true); emit changed(); return;
    }
    window_.setScreen(library_->screen());
    window_.setTransientParent(library_);
    window_.resize(library_->size());
    auto* overlay = window_.quickOverlayWindow();
    overlay->setGeometry(0, 0, library_->width(), library_->height());
    overlay->contentItem()->setSize(library_->size());
    popup->setProperty("externalVideo", true);
    popup->setProperty("parent", QVariant::fromValue(overlay->contentItem()));
    desktopInput_.watchWindow(&window_, popup);
    display_.watchWindow(&window_);
    if (library_->visibility() == QWindow::FullScreen) window_.showFullScreen();
    else window_.show();
    window_.requestActivate();
    popupDestroyed_ = connect(popup, &QObject::destroyed, this, [this] {
        if (session_.busy()) session_.closeSession();
        restore(false);
    });
    emit changed();
}

void DeckVulkanSessionView::detach() {
    if (restoring_) return;
    if (session_.busy()) session_.closeSession();
    restore(false);
}

void DeckVulkanSessionView::restore(bool keepPopup) {
    if (restoring_) return;
    restoring_ = true;
    disconnect(popupDestroyed_);
    session_.setPresentationSink(nullptr, nullptr);
    window_.clearFrame();
    if (popup_) {
        popup_->setProperty("externalVideo", false);
        if (originalParent_) popup_->setProperty("parent", QVariant::fromValue(originalParent_.data()));
    }
    display_.watchWindow(library_);
    desktopInput_.watchWindow(library_, library_);
    // Return input to the library immediately; retain the native surface until
    // the render worker has drained it. A reopen cancels native destruction.
    window_.retireSurface();
    if (library_ && !closing_) library_->requestActivate();
    popup_ = nullptr; originalParent_ = nullptr;
    if (!keepPopup) error_.clear();
    ready_ = false;
    restoring_ = false;
    if (!closing_) emit changed();
}
}
