#include "deck_desktop_input.h"
#include <Limelight.h>
#include <algorithm>
#include <array>
#include <cmath>

namespace nova::deck::stream {
bool DeckDesktopPacket::valid() const {
    switch (kind) {
    case Key: return code > 0 && code <= 255;
    case Button: return code >= BUTTON_LEFT && code <= BUTTON_X2;
    case Position: return x >= 0 && x <= 32766 && y >= 0 && y <= 32766;
    case Scroll: return x >= -32768 && x <= 32767 && y >= -32768 && y <= 32767;
    case ReleaseAll: return true;
    }
    return false;
}
int sendDeckDesktopPacket(const DeckDesktopPacket& packet) {
    if (!packet.valid()) return -1;
    switch (packet.kind) {
    case DeckDesktopPacket::Key:
        return LiSendKeyboardEvent(static_cast<short>(0x8000 | packet.code),
            packet.down ? KEY_ACTION_DOWN : KEY_ACTION_UP, 0);
    case DeckDesktopPacket::Button:
        return LiSendMouseButtonEvent(packet.down ? BUTTON_ACTION_PRESS : BUTTON_ACTION_RELEASE, packet.code);
    case DeckDesktopPacket::Position: return LiSendMousePositionEvent(packet.x, packet.y, 32767, 32767);
    case DeckDesktopPacket::Scroll:
        if (packet.y && LiSendHighResScrollEvent(packet.y) != 0) return -1;
        return packet.x ? LiSendHighResHScrollEvent(packet.x) : 0;
    case DeckDesktopPacket::ReleaseAll: return -1; // Resolved by the worker ledger.
    }
    return -1;
}

namespace {
int linuxKey(unsigned scan) {
    // Linux input-event-codes, translated to GameStream's US Win32 VKs.
    static constexpr std::array<int, 128> keys = [] {
        std::array<int, 128> a{};
        a[1]=0x1b;
        for (int i=0;i<9;++i) a[2+i]='1'+i;
        a[11]='0'; a[12]=0xbd; a[13]=0xbb; a[14]=8; a[15]=9;
        const char* q="QWERTYUIOP"; for (int i=0;i<10;++i) a[16+i]=q[i];
        a[26]=0xdb; a[27]=0xdd; a[28]=0x0d; a[29]=0xa2;
        const char* h="ASDFGHJKL"; for (int i=0;i<9;++i) a[30+i]=h[i];
        a[39]=0xba; a[40]=0xde; a[41]=0xc0; a[42]=0xa0; a[43]=0xdc;
        const char* z="ZXCVBNM"; for (int i=0;i<7;++i) a[44+i]=z[i];
        a[51]=0xbc; a[52]=0xbe; a[53]=0xbf; a[54]=0xa1; a[55]=0x6a;
        a[56]=0xa4; a[57]=0x20; a[58]=0x14;
        for (int i=0;i<10;++i) a[59+i]=0x70+i;
        a[69]=0x90; a[70]=0x91;
        a[71]=0x67; a[72]=0x68; a[73]=0x69; a[74]=0x6d;
        a[75]=0x64; a[76]=0x65; a[77]=0x66; a[78]=0x6b;
        a[79]=0x61; a[80]=0x62; a[81]=0x63; a[82]=0x60; a[83]=0x6e;
        a[86]=0xe2; a[87]=0x7a; a[88]=0x7b; a[96]=0x0d; a[97]=0xa3;
        a[98]=0x6f; a[99]=0x2c; a[100]=0xa5;
        a[102]=0x24; a[103]=0x26; a[104]=0x21; a[105]=0x25; a[106]=0x27;
        a[107]=0x23; a[108]=0x28; a[109]=0x22; a[110]=0x2d; a[111]=0x2e;
        a[119]=0x13; a[125]=0x5b; a[126]=0x5c; a[127]=0x5d;
        return a;
    }();
    if (scan >= 183 && scan <= 194) return 0x7c + scan - 183; // F13-F24
    return scan < keys.size() ? keys[scan] : 0;
}
}
int deckDesktopKey(const QKeyEvent& event, const QString& platform) {
    // Qt reports XKB keycodes (evdev + 8) on both Linux display backends.
    const auto scan = event.nativeScanCode();
    if ((platform == "xcb" || platform.startsWith("wayland")) && scan >= 8)
        return linuxKey(scan - 8);
    const int key = event.key();
    if (event.modifiers().testFlag(Qt::KeypadModifier)) {
        if (key >= Qt::Key_0 && key <= Qt::Key_9) return 0x60 + key - Qt::Key_0;
        switch (key) {
        case Qt::Key_Asterisk: return 0x6a;
        case Qt::Key_Plus: return 0x6b;
        case Qt::Key_Minus: return 0x6d;
        case Qt::Key_Period: case Qt::Key_Comma: return 0x6e;
        case Qt::Key_Slash: return 0x6f;
        default: break;
        }
    }
    if ((key >= Qt::Key_A && key <= Qt::Key_Z) || (key >= Qt::Key_0 && key <= Qt::Key_9)) return key;
    if (key >= Qt::Key_F1 && key <= Qt::Key_F24) return 0x70 + key - Qt::Key_F1;
    switch (key) {
    case Qt::Key_Escape: return 0x1b;
    case Qt::Key_Tab: case Qt::Key_Backtab: return 9;
    case Qt::Key_Backspace: return 8;
    case Qt::Key_Return: case Qt::Key_Enter: return 0x0d;
    case Qt::Key_Space: return 0x20;
    case Qt::Key_Shift: return 0xa0;
    case Qt::Key_Control: return 0xa2;
    case Qt::Key_Alt: return 0xa4;
    case Qt::Key_AltGr: return 0xa5;
    case Qt::Key_Meta: return 0x5b;
    case Qt::Key_Menu: return 0x5d;
    case Qt::Key_CapsLock: return 0x14;
    case Qt::Key_NumLock: return 0x90;
    case Qt::Key_ScrollLock: return 0x91;
    case Qt::Key_Print: return 0x2c;
    case Qt::Key_Pause: return 0x13;
    case Qt::Key_Home: return 0x24;
    case Qt::Key_End: return 0x23;
    case Qt::Key_PageUp: return 0x21;
    case Qt::Key_PageDown: return 0x22;
    case Qt::Key_Left: return 0x25;
    case Qt::Key_Up: return 0x26;
    case Qt::Key_Right: return 0x27;
    case Qt::Key_Down: return 0x28;
    case Qt::Key_Insert: return 0x2d;
    case Qt::Key_Delete: return 0x2e;
    case Qt::Key_Semicolon: case Qt::Key_Colon: return 0xba;
    case Qt::Key_Equal: case Qt::Key_Plus: return 0xbb;
    case Qt::Key_Comma: case Qt::Key_Less: return 0xbc;
    case Qt::Key_Minus: case Qt::Key_Underscore: return 0xbd;
    case Qt::Key_Period: case Qt::Key_Greater: return 0xbe;
    case Qt::Key_Slash: case Qt::Key_Question: return 0xbf;
    case Qt::Key_QuoteLeft: case Qt::Key_AsciiTilde: return 0xc0;
    case Qt::Key_BracketLeft: case Qt::Key_BraceLeft: return 0xdb;
    case Qt::Key_Backslash: case Qt::Key_Bar: return 0xdc;
    case Qt::Key_BracketRight: case Qt::Key_BraceRight: return 0xdd;
    case Qt::Key_Apostrophe: case Qt::Key_QuoteDbl: return 0xde;
    default: return 0;
    }
}
int deckDesktopButton(Qt::MouseButton button) {
    switch (button) {
    case Qt::LeftButton: return BUTTON_LEFT;
    case Qt::MiddleButton: return BUTTON_MIDDLE;
    case Qt::RightButton: return BUTTON_RIGHT;
    case Qt::BackButton: return BUTTON_X1;
    case Qt::ForwardButton: return BUTTON_X2;
    default: return 0;
    }
}
std::optional<DeckDesktopPacket> deckDesktopPosition(QPointF point, QSizeF viewport,
        QSizeF video, DeckVideoScaleMode scale, bool clamp, double pixelAspect) {
    if (viewport.isEmpty() || video.isEmpty() || !std::isfinite(point.x()) || !std::isfinite(point.y())) return {};
    const auto layout = deckVideoScaleLayout(video, viewport, scale, pixelAspect);
    const QPointF p(point.x()/viewport.width(), point.y()/viewport.height());
    if (!clamp && !layout.destination.contains(p)) return {};
    const double x = layout.source.x() + std::clamp((p.x()-layout.destination.x())/layout.destination.width(), 0., 1.) * layout.source.width();
    const double y = layout.source.y() + std::clamp((p.y()-layout.destination.y())/layout.destination.height(), 0., 1.) * layout.source.height();
    return DeckDesktopPacket{DeckDesktopPacket::Position, 0, int(std::lround(x*32766)), int(std::lround(y*32766))};
}
void DeckDesktopRouter::capture(bool enabled) {
    capture_ = enabled;
    if (!enabled) { sentKeys_.clear(); sentButtons_.clear(); }
}
void DeckDesktopRouter::focusLost() { capture(false); keys_.clear(); buttons_.clear(); }
std::optional<DeckDesktopPacket> DeckDesktopRouter::key(int code, bool down, bool repeat) {
    if (code <= 0 || code > 255 || repeat) return {};
    if (down) {
        if (!keys_.insert(code).second || !capture_) return {};
        sentKeys_.insert(code);
    } else {
        keys_.erase(code);
        if (!sentKeys_.erase(code) || !capture_) return {};
    }
    return DeckDesktopPacket{DeckDesktopPacket::Key, code, 0, 0, down};
}
std::optional<DeckDesktopPacket> DeckDesktopRouter::button(int code, bool down, bool forward) {
    if (code < 1 || code > 5) return {};
    if (down) {
        if (!buttons_.insert(code).second || !capture_ || !forward) return {};
        sentButtons_.insert(code);
    } else {
        buttons_.erase(code);
        if (!sentButtons_.erase(code) || !capture_) return {};
    }
    return DeckDesktopPacket{DeckDesktopPacket::Button, code, 0, 0, down};
}
int DeckDesktopLedger::deliver(const DeckDesktopPacket& packet, const DeckDesktopSend& send) {
    if (!packet.valid()) return -1;
    if (packet.kind == DeckDesktopPacket::ReleaseAll) return release(send);
    if (packet.down) {
        if (packet.kind == DeckDesktopPacket::Key) keys_.insert(packet.code);
        if (packet.kind == DeckDesktopPacket::Button) buttons_.insert(packet.code);
    }
    const auto result = send(packet);
    if (!result && !packet.down) {
        if (packet.kind == DeckDesktopPacket::Key) keys_.erase(packet.code);
        if (packet.kind == DeckDesktopPacket::Button) buttons_.erase(packet.code);
    }
    return result;
}
int DeckDesktopLedger::release(const DeckDesktopSend& send) {
    int result = 0;
    for (const auto code : keys_) if (send({DeckDesktopPacket::Key, code}) != 0) result = -1;
    for (const auto code : buttons_) if (send({DeckDesktopPacket::Button, code}) != 0) result = -1;
    keys_.clear(); buttons_.clear();
    return result;
}
}
