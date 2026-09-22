#include "runtime/deck_display_capabilities.h"
#include <cmath>
#include "polaris/deck_stream_capabilities.h"

namespace nova::deck::runtime {

DeckDisplayCapabilities::DeckDisplayCapabilities(RefreshReader reader, QObject* parent)
    : QObject(parent), reader_(std::move(reader)) {
    if (!reader_) reader_ = [](QScreen* screen) { return screen->refreshRate(); };
    applyRate(0);
}

DeckDisplayCapabilities::~DeckDisplayCapabilities() { rateLimit_->store(60); }

void DeckDisplayCapabilities::watchWindow(QWindow* window) {
    for (const auto& connection : windowConnections_) disconnect(connection);
    windowConnections_.clear();
    window_ = window;
    if (window) {
        windowConnections_.push_back(connect(window, &QWindow::screenChanged, this, [this] { watchScreen(); }));
        windowConnections_.push_back(connect(window, &QObject::destroyed, this, [this] { watchWindow(nullptr); }));
    }
    watchScreen();
}

void DeckDisplayCapabilities::watchScreen() {
    for (const auto& connection : screenConnections_) disconnect(connection);
    screenConnections_.clear();
    screen_ = window_ ? window_->screen() : nullptr;
    if (screen_) {
        screenConnections_.push_back(connect(screen_, &QScreen::refreshRateChanged, this, [this] { refresh(); }));
        screenConnections_.push_back(connect(screen_, &QObject::destroyed, this, [this] {
            screen_ = nullptr;
            applyRate(0);
        }));
    }
    refresh();
}

void DeckDisplayCapabilities::refresh() { applyRate(screen_ ? reader_(screen_) : 0); }

void DeckDisplayCapabilities::applyRate(double rate) {
    const bool known = std::isfinite(rate) && rate > 0 && rate <= 1000;
    // Qt reports the current mode, not permission to switch display modes.
    const int limit = deckDisplayRateLimit(rate);
    rateLimit_->store(limit);
    const QVariantMap next{{"known", known}, {"refreshHz", known ? rate : 0}, {"maxFps", limit}};
    if (state_ != next) { state_ = next; emit stateChanged(); }
}

std::function<int()> DeckDisplayCapabilities::rateLimitReader() const {
    return [limit = rateLimit_] { return limit->load(); };
}

} // namespace nova::deck::runtime
