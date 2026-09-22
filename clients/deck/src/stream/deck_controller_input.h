#pragma once

#include "deck_gamepad.h"
#include <cstdint>
#include <functional>
#include <optional>
#include <vector>

namespace nova::deck::stream {

struct DeckControllerState {
    std::uint32_t buttons = 0;
    std::uint8_t leftTrigger = 0, rightTrigger = 0;
    std::int16_t leftX = 0, leftY = 0, rightX = 0, rightY = 0;
    bool operator==(const DeckControllerState&) const = default;
    bool neutral() const { return *this == DeckControllerState{}; }
};

inline constexpr int kDeckDefaultStickDeadzone = 5;
/// Android radial cutoff/anti-deadzone, applied once before gameplay routing.
/// Invalid percentages use the default. Buttons, hats and triggers are unchanged.
DeckControllerState withStickDeadzone(DeckControllerState state, int percent);

struct DeckControllerMapping {
    std::vector<unsigned char> axes;
    std::vector<unsigned short> buttons;
    bool valid = false;
    bool xboxButtonLabels = false;
};
DeckControllerMapping readDeckControllerMapping(int fd, DeckGamepadIoctl query = nullptr);

/// Linux joystick indices are device-specific. Resolve their ABS/BTN codes
/// before translating to the GameStream layout. Init events seed held state;
/// readiness requires a complete snapshot of every mapped control.
class DeckControllerDecoder {
public:
    explicit DeckControllerDecoder(DeckControllerMapping mapping = {});
    void consume(const DeckGamepadEvent& event);
    DeckControllerState state() const; // Raw oriented sticks, before deadzone.
    bool ready() const;
private:
    DeckControllerMapping mapping_;
    std::vector<short> axes_, buttons_;
    std::vector<bool> seenAxes_, seenButtons_;
};

struct DeckControllerPacket {
    DeckControllerState state;
    bool connected = false;
    std::uint16_t controllerNumber = 0;
    std::optional<std::uint16_t> activeMask;
    std::uint16_t effectiveMask() const {
        return activeMask.value_or(connected && controllerNumber < 16 ? std::uint16_t(1u << controllerNumber) : 0);
    }
    bool operator==(const DeckControllerPacket& other) const {
        return state == other.state && connected == other.connected && controllerNumber == other.controllerNumber
            && effectiveMask() == other.effectiveMask();
    }
};
/// Android's Match positions swaps A/B and X/Y after device decoding. Apply
/// only to outgoing gameplay packets, leaving navigation and shortcut policy alone.
DeckControllerPacket withFaceButtonLayout(DeckControllerPacket packet, bool matchPositions);
struct DeckControllerRoute {
    std::vector<DeckControllerPacket> packets;
    bool openControls = false;
    // Focus/overlay/device transitions invalidate queued presses without
    // implying that a physically connected controller has been unplugged.
    bool discardPendingInput = false;
};

/// GUI-thread policy. Start/focus/overlay transitions require all controls to
/// return to neutral, keeping the host device connected while input is paused.
/// View/Menu are deferred for 150 ms so their simultaneous
/// chord is consumed locally; single presses and quick taps still reach games.
class DeckControllerRouter {
public:
    DeckControllerRoute capture(bool enabled, std::int64_t nowMs);
    DeckControllerRoute update(DeckControllerState state, bool connected, std::int64_t nowMs);
    DeckControllerRoute tick(std::int64_t nowMs);
    bool capturing() const { return capture_; }
    bool awaitingNeutral() const { return capture_ && !armed_; }
private:
    void append(DeckControllerRoute& result, DeckControllerPacket packet);
    DeckControllerState physical_;
    bool connected_ = false, capture_ = false, armed_ = false;
    std::optional<DeckControllerPacket> last_;
    std::uint32_t pendingMenu_ = 0;
    std::int64_t menuDeadline_ = 0;
    bool menuPassthrough_ = false;
};

using DeckControllerSend = std::function<int(const DeckControllerPacket&)>;
/// Called only by the native-session worker between start and stop. A neutral
/// mask-zero packet releases controls and removes controller zero on the host.
int sendDeckControllerPacket(const DeckControllerPacket& packet);

} // namespace nova::deck::stream
