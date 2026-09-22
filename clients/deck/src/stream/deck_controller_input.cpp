#include "stream/deck_controller_input.h"
#include <Limelight.h>
#include <algorithm>
#include <array>
#include <cctype>
#include <string>
#include <cmath>
#ifdef __linux__
#include <linux/joystick.h>
#include <sys/ioctl.h>
#endif

namespace nova::deck::stream {
namespace {
constexpr std::uint32_t menuMask = BACK_FLAG | PLAY_FLAG;
std::uint32_t buttonFlag(unsigned short code, bool xboxLabels = false) {
#ifdef __linux__
    switch (code) {
    case BTN_SOUTH: return A_FLAG;
    case BTN_EAST: return B_FLAG;
    // xpad retains the legacy BTN_X/BTN_Y aliases (NORTH/WEST) even though
    // their physical positions differ from the modern gamepad specification.
    case BTN_WEST: return xboxLabels ? Y_FLAG : X_FLAG;
    case BTN_NORTH: return xboxLabels ? X_FLAG : Y_FLAG;
    case BTN_TL: return LB_FLAG;
    case BTN_TR: return RB_FLAG;
    case BTN_SELECT: return BACK_FLAG;
    case BTN_START: return PLAY_FLAG;
    case BTN_THUMBL: return LS_CLK_FLAG;
    case BTN_THUMBR: return RS_CLK_FLAG;
    case BTN_MODE: return SPECIAL_FLAG;
    case BTN_DPAD_UP: return UP_FLAG;
    case BTN_DPAD_DOWN: return DOWN_FLAG;
    case BTN_DPAD_LEFT: return LEFT_FLAG;
    case BTN_DPAD_RIGHT: return RIGHT_FLAG;
    default: break;
    }
#else
    (void)code;
#endif
    return 0;
}
bool knownAxis(unsigned char code) {
#ifdef __linux__
    return code == ABS_X || code == ABS_Y || code == ABS_RX || code == ABS_RY ||
        code == ABS_Z || code == ABS_RZ || code == ABS_HAT0X || code == ABS_HAT0Y;
#else
    (void)code;
    return false;
#endif
}
short stick(short value, bool inverted = false) {
    return inverted ? static_cast<short>(-std::max<int>(-32767, value)) : value;
}
unsigned char trigger(short value) {
    const int scaled = (static_cast<int>(value) + 32768) * 255 / 65535;
    return scaled < 8 ? 0 : static_cast<unsigned char>(scaled);
}
}

DeckControllerState withStickDeadzone(DeckControllerState state, int percent) {
    if (percent < -20 || percent > 20) percent = kDeckDefaultStickDeadzone;
    const auto apply = [percent](std::int16_t& x, std::int16_t& y) {
        const double radius = percent / 100.0;
        const double magnitude = std::hypot(double(x), double(y)) / 32767.0;
        if (radius > 0) {
            if (magnitude <= radius) x = y = 0;
            return; // Android preserves values outside the positive cutoff.
        }
        // Android retains a tiny center floor even at zero/negative settings.
        if (magnitude < 0.01) { x = y = 0; return; }
        const double adjusted = -radius + magnitude * (1 + radius);
        if (adjusted >= 1) return; // Preserve axial extremes and diagonal range.
        const double scale = adjusted / magnitude;
        x = static_cast<std::int16_t>(std::clamp(int(x * scale), -32768, 32767));
        y = static_cast<std::int16_t>(std::clamp(int(y * scale), -32768, 32767));
    };
    apply(state.leftX, state.leftY);
    apply(state.rightX, state.rightY);
    return state;
}

DeckControllerPacket withFaceButtonLayout(DeckControllerPacket packet, bool matchPositions) {
    if (!matchPositions) return packet;
    const auto buttons = packet.state.buttons;
    packet.state.buttons = (buttons & ~(A_FLAG | B_FLAG | X_FLAG | Y_FLAG)) |
        (buttons & A_FLAG ? B_FLAG : 0) | (buttons & B_FLAG ? A_FLAG : 0) |
        (buttons & X_FLAG ? Y_FLAG : 0) | (buttons & Y_FLAG ? X_FLAG : 0);
    return packet;
}

DeckControllerMapping readDeckControllerMapping(int fd, DeckGamepadIoctl query) {
    DeckControllerMapping mapping;
#ifdef __linux__
    if (!query) query = [](int device, unsigned long request, void* data) { return ::ioctl(device, request, data); };
    unsigned char axesCount = 0, buttonsCount = 0;
    std::array<unsigned char, ABS_CNT> axes{};
    std::array<unsigned short, KEY_MAX - BTN_MISC + 1> buttons{};
    if (query(fd, JSIOCGAXES, &axesCount) < 0 || query(fd, JSIOCGBUTTONS, &buttonsCount) < 0 ||
        axesCount > axes.size() || query(fd, JSIOCGAXMAP, axes.data()) < 0 ||
        query(fd, JSIOCGBTNMAP, buttons.data()) < 0) return mapping;
    mapping.axes.assign(axes.begin(), axes.begin() + axesCount);
    mapping.buttons.assign(buttons.begin(), buttons.begin() + buttonsCount);
    std::array<char, 128> name{};
    if (query(fd, JSIOCGNAME(name.size()), name.data()) >= 0) {
        name.back() = '\0';
        std::string label(name.data());
        std::transform(label.begin(), label.end(), label.begin(), [](unsigned char c) { return std::tolower(c); });
        mapping.xboxButtonLabels = label.find("xbox") != std::string::npos ||
            label.find("x-box") != std::string::npos || label == "steam virtual gamepad";
    }
    // No guessed button ordering on failed/unknown maps. Library navigation can
    // still operate independently of this native-input readiness check.
    mapping.valid = std::find(mapping.buttons.begin(), mapping.buttons.end(), BTN_SOUTH) != mapping.buttons.end() &&
        std::find(mapping.buttons.begin(), mapping.buttons.end(), BTN_EAST) != mapping.buttons.end();
#else
    (void)fd;
    (void)query;
#endif
    return mapping;
}

DeckControllerDecoder::DeckControllerDecoder(DeckControllerMapping mapping)
    : mapping_(std::move(mapping)), axes_(mapping_.axes.size()), buttons_(mapping_.buttons.size()),
      seenAxes_(axes_.size()), seenButtons_(buttons_.size()) {}

void DeckControllerDecoder::consume(const DeckGamepadEvent& event) {
    const auto type = event.type & ~kDeckGamepadInitEvent;
    if (type == kDeckGamepadAxisEvent && event.number < axes_.size()) {
        axes_[event.number] = event.value;
        seenAxes_[event.number] = true;
    } else if (type == kDeckGamepadButtonEvent && event.number < buttons_.size()) {
        buttons_[event.number] = event.value;
        seenButtons_[event.number] = true;
    }
}

bool DeckControllerDecoder::ready() const {
    if (!mapping_.valid) return false;
    for (std::size_t i = 0; i < axes_.size(); ++i) if (knownAxis(mapping_.axes[i]) && !seenAxes_[i]) return false;
    for (std::size_t i = 0; i < buttons_.size(); ++i) if (buttonFlag(mapping_.buttons[i]) && !seenButtons_[i]) return false;
    return true;
}

DeckControllerState DeckControllerDecoder::state() const {
    DeckControllerState state;
    if (!mapping_.valid) return state;
    for (std::size_t i = 0; i < buttons_.size(); ++i) if (buttons_[i]) state.buttons |= buttonFlag(mapping_.buttons[i], mapping_.xboxButtonLabels);
#ifdef __linux__
    for (std::size_t i = 0; i < axes_.size(); ++i) {
        if (!seenAxes_[i]) continue;
        const auto value = axes_[i];
        switch (mapping_.axes[i]) {
        case ABS_X: state.leftX = stick(value); break;
        case ABS_Y: state.leftY = stick(value, true); break;
        case ABS_RX: state.rightX = stick(value); break;
        case ABS_RY: state.rightY = stick(value, true); break;
        case ABS_Z: state.leftTrigger = trigger(value); break;
        case ABS_RZ: state.rightTrigger = trigger(value); break;
        case ABS_HAT0X:
            state.buttons |= value < -16000 ? LEFT_FLAG : value > 16000 ? RIGHT_FLAG : 0;
            break;
        case ABS_HAT0Y:
            state.buttons |= value < -16000 ? UP_FLAG : value > 16000 ? DOWN_FLAG : 0;
            break;
        default: break;
        }
    }
#endif
    return state;
}

void DeckControllerRouter::append(DeckControllerRoute& result, DeckControllerPacket packet) {
    if (!last_ || packet != *last_) {
        result.packets.push_back(packet);
        last_ = packet;
    }
}

DeckControllerRoute DeckControllerRouter::capture(bool enabled, std::int64_t nowMs) {
    if (capture_ == enabled) return {};
    capture_ = enabled;
    armed_ = false;
    pendingMenu_ = 0;
    menuPassthrough_ = false;
    DeckControllerRoute result;
    result.discardPendingInput = true;
    // Always enqueue a fresh release after the queue barrier, even if the last
    // neutral packet was still queued. A zero mask would destroy the host pad
    // that an isolated game was given at launch.
    last_.reset();
    append(result, {{}, connected_});
    if (enabled && connected_ && physical_.neutral()) {
        armed_ = true;
    }
    (void)nowMs;
    return result;
}

DeckControllerRoute DeckControllerRouter::tick(std::int64_t nowMs) {
    DeckControllerRoute result;
    if (capture_ && armed_ && pendingMenu_ && nowMs >= menuDeadline_) {
        pendingMenu_ = 0;
        menuPassthrough_ = true;
        append(result, {physical_, true});
    }
    return result;
}

DeckControllerRoute DeckControllerRouter::update(DeckControllerState state, bool connected, std::int64_t nowMs) {
    const bool deviceChanged = connected_ != connected;
    auto result = connected && !deviceChanged ? tick(nowMs) : DeckControllerRoute{};
    physical_ = state;
    connected_ = connected;
    if (deviceChanged) {
        result.discardPendingInput = true;
        armed_ = false;
        pendingMenu_ = 0;
        menuPassthrough_ = false;
        last_.reset();
        append(result, {{}, connected});
    }
    if (!capture_ || !connected) return result;
    if (!armed_) {
        if (state.neutral()) { armed_ = true; append(result, {{}, true}); }
        return result;
    }
    const auto menu = state.buttons & menuMask;
    if (!menuPassthrough_) {
        if (menu == menuMask) {
            // Neither chord button was forwarded. Resume requires neutral, so
            // held buttons cannot trigger a UI action or leak back to the game.
            capture_ = armed_ = false;
            pendingMenu_ = 0;
            result.discardPendingInput = true;
            last_.reset();
            append(result, {{}, true});
            result.openControls = true;
            return result;
        }
        if (pendingMenu_ && menu != pendingMenu_) {
            // A quick single-button tap still needs both its down and up edges.
            auto down = state;
            down.buttons = (down.buttons & ~menuMask) | pendingMenu_;
            append(result, {down, true});
            pendingMenu_ = 0;
        }
        if (!pendingMenu_ && menu) {
            pendingMenu_ = menu;
            menuDeadline_ = nowMs + 150;
        }
        state.buttons &= ~menuMask;
    } else if (!menu) {
        menuPassthrough_ = false;
    }
    append(result, {state, true});
    return result;
}

int sendDeckControllerPacket(const DeckControllerPacket& packet) {
    const auto state = packet.connected ? packet.state : DeckControllerState{};
    if (packet.controllerNumber >= 16) return -1;
    const auto mask = packet.effectiveMask();
    if (bool(mask & (1u << packet.controllerNumber)) != packet.connected) return -1;
    return LiSendMultiControllerEvent(packet.controllerNumber, mask, static_cast<int>(state.buttons),
        state.leftTrigger, state.rightTrigger, state.leftX, state.leftY, state.rightX, state.rightY);
}

} // namespace nova::deck::stream
