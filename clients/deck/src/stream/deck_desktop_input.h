#pragma once

#include "deck_video_scale.h"
#include <QKeyEvent>
#include <cstdint>
#include <functional>
#include <set>
#include <vector>

namespace nova::deck::stream {

struct DeckDesktopPacket {
    enum Kind { Key, Button, Position, Scroll, ReleaseAll } kind = ReleaseAll;
    int code = 0, x = 0, y = 0;
    bool down = false;
    bool operator==(const DeckDesktopPacket&) const = default;
    bool valid() const;
};
using DeckDesktopSend = std::function<int(const DeckDesktopPacket&)>;
int sendDeckDesktopPacket(const DeckDesktopPacket& packet);

// Physical US key positions on Linux/X11 and Wayland; logical Qt fallback for
// injected Steam Input events. No text/IME or clipboard forwarding here.
int deckDesktopKey(const QKeyEvent& event, const QString& platform);
int deckDesktopButton(Qt::MouseButton button);
std::optional<DeckDesktopPacket> deckDesktopPosition(QPointF point, QSizeF viewport,
    QSizeF video, DeckVideoScaleMode scale, bool clamp = false, double pixelAspect = 1);

// GUI-thread edge policy. Menu presses are observed but never replayed on
// Resume. Qt's repeat pairs do not release a held host key or duplicate downs.
class DeckDesktopRouter {
public:
    void capture(bool enabled);
    void focusLost();
    std::optional<DeckDesktopPacket> key(int code, bool down, bool repeat = false);
    std::optional<DeckDesktopPacket> button(int code, bool down, bool forward = true);
    bool dragging() const { return !sentButtons_.empty(); }
private:
    bool capture_ = false;
    std::set<int> keys_, buttons_, sentKeys_, sentButtons_;
};

// Worker-thread ledger records attempted downs as well as successful ones:
// failed delivery may be ambiguous. Releases precede connection teardown.
class DeckDesktopLedger {
public:
    int deliver(const DeckDesktopPacket& packet, const DeckDesktopSend& send);
    int release(const DeckDesktopSend& send);
private:
    std::set<int> keys_, buttons_;
};
}
