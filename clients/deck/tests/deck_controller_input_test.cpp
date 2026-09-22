#include "stream/deck_controller_input.h"
#include <Limelight.h>
#include <linux/joystick.h>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <iostream>

using namespace nova::deck;
using namespace nova::deck::stream;

namespace {
void require(bool ok, const char* message) {
    if (!ok) { std::cerr << message << '\n'; std::exit(1); }
}
struct Device {
    static inline bool fail = false;
    static inline bool xbox = true;
    static inline bool oversized = false;
    static inline int success = 64;
    static inline std::vector<unsigned char> axes{ABS_HAT0Y, ABS_RZ, ABS_X, ABS_RY, ABS_Y, ABS_Z, ABS_RX, ABS_HAT0X};
    static inline std::vector<unsigned short> buttons{BTN_TR, BTN_SOUTH, BTN_EAST, BTN_X, BTN_Y,
        BTN_TL, BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR, BTN_MODE, BTN_DPAD_LEFT};
    static int query(int, unsigned long request, void* data) {
        if (fail) return -1;
        if (request == JSIOCGAXES) *static_cast<unsigned char*>(data) = oversized ? 255 : axes.size();
        else if (request == JSIOCGBUTTONS) *static_cast<unsigned char*>(data) = buttons.size();
        else if (request == JSIOCGAXMAP) std::copy(axes.begin(), axes.end(), static_cast<unsigned char*>(data));
        else if (request == JSIOCGBTNMAP) std::copy(buttons.begin(), buttons.end(), static_cast<unsigned short*>(data));
        else if (request == JSIOCGNAME(128)) std::strcpy(static_cast<char*>(data), xbox ? "Microsoft X-Box 360 pad" : "Modern gamepad");
        else return -1;
        return success;
    }
};
void seed(DeckControllerDecoder& decoder) {
    for (std::size_t i = 0; i < Device::buttons.size(); ++i)
        decoder.consume({0, 0, kDeckGamepadButtonEvent | kDeckGamepadInitEvent, static_cast<unsigned char>(i)});
    for (std::size_t i = 0; i < Device::axes.size(); ++i) {
        const short value = Device::axes[i] == ABS_Z || Device::axes[i] == ABS_RZ ? -32767 : 0;
        decoder.consume({0, value, kDeckGamepadAxisEvent | kDeckGamepadInitEvent, static_cast<unsigned char>(i)});
    }
}
void axis(DeckControllerDecoder& decoder, unsigned char code, short value) {
    const auto index = std::find(Device::axes.begin(), Device::axes.end(), code) - Device::axes.begin();
    decoder.consume({0, value, kDeckGamepadAxisEvent, static_cast<unsigned char>(index)});
}
void testMappingAndDecoder() {
    for (int result : {0, 64}) {
        Device::success = result;
        const auto mapping = readDeckControllerMapping(0, Device::query);
        require(mapping.valid && mapping.xboxButtonLabels, "valid ioctl result rejected");
        DeckControllerDecoder decoder(mapping);
        require(!decoder.ready(), "controller armed before initialization snapshot");
        seed(decoder);
        require(decoder.ready() && decoder.state().neutral(), "initialized resting triggers/sticks were not neutral");
        const unsigned flags[] = {RB_FLAG, A_FLAG, B_FLAG, X_FLAG, Y_FLAG, LB_FLAG,
            BACK_FLAG, PLAY_FLAG, LS_CLK_FLAG, RS_CLK_FLAG, SPECIAL_FLAG, LEFT_FLAG};
        for (unsigned char i = 0; i < Device::buttons.size(); ++i) {
            decoder.consume({0, 1, kDeckGamepadButtonEvent, i});
            require(decoder.state().buttons == flags[i], "reordered Xbox button map changed face-button meaning");
            decoder.consume({0, 0, kDeckGamepadButtonEvent, i});
            require(decoder.state().neutral(), "button release was lost");
        }
        axis(decoder, ABS_X, -32768);
        axis(decoder, ABS_Y, -32768);
        axis(decoder, ABS_RX, 32767);
        axis(decoder, ABS_RY, 32767);
        axis(decoder, ABS_Z, 32767);
        axis(decoder, ABS_RZ, 0);
        axis(decoder, ABS_HAT0X, -32767);
        axis(decoder, ABS_HAT0Y, -32767);
        const auto state = decoder.state();
        require(state.leftX == -32768 && state.leftY == 32767 && state.rightX == 32767 && state.rightY == -32767,
            "stick orientation or signed extreme changed");
        require(state.leftTrigger == 255 && state.rightTrigger == 127, "trigger range was not normalized");
        require(state.buttons == (UP_FLAG | LEFT_FLAG), "diagonal hat state lost");
        axis(decoder, ABS_X, 4096);
        require(decoder.state().leftX == 4096, "decoder discarded raw stick movement before the selected deadzone");
        const auto before = decoder.state();
        decoder.consume({0, 32767, kDeckGamepadAxisEvent, 255});
        decoder.consume({0, 1, kDeckGamepadButtonEvent, 255});
        require(before == decoder.state(), "out of range event changed controller state");
    }
    Device::xbox = false;
    DeckControllerDecoder modern(readDeckControllerMapping(0, Device::query));
    seed(modern);
    modern.consume({0, 1, kDeckGamepadButtonEvent, 3}); // BTN_NORTH follows physical geometry here.
    require(modern.state().buttons == Y_FLAG, "modern north button used legacy Xbox mapping");
    Device::fail = true;
    require(!readDeckControllerMapping(0, Device::query).valid, "failed mapping guessed a controller layout");
    Device::fail = false;
    Device::oversized = true;
    require(!readDeckControllerMapping(0, Device::query).valid, "oversized axis count accepted");
}

void testFaceButtonLayouts() {
    const unsigned labels[] = {A_FLAG, B_FLAG, X_FLAG, Y_FLAG};
    const unsigned positions[] = {B_FLAG, A_FLAG, Y_FLAG, X_FLAG};
    for (unsigned combination = 0; combination < 16; ++combination) {
        DeckControllerPacket packet{{.buttons = UP_FLAG | RB_FLAG | BACK_FLAG | PLAY_FLAG | 0x80000000u,
            .leftTrigger = 123, .rightTrigger = 255, .leftX = -32768, .leftY = 32767, .rightX = 6000, .rightY = -20000}, true};
        auto expected = packet;
        for (unsigned i = 0; i < 4; ++i) if (combination & (1u << i)) {
            packet.state.buttons |= labels[i];
            expected.state.buttons |= positions[i];
        }
        require(withFaceButtonLayout(packet, false) == packet, "label mode changed controller state");
        require(withFaceButtonLayout(packet, true) == expected, "position mode lost simultaneous buttons or unrelated controls");
        require(withFaceButtonLayout(expected, true) == packet, "position swap was not reversible");
    }
    require(withFaceButtonLayout({{}, false}, true) == DeckControllerPacket{{}, false}, "layout changed disconnect release");
}

void testStickDeadzone() {
    require(withStickDeadzone({.leftX=1600},5).neutral(), "positive cutoff retained drift");
    require(withStickDeadzone({.leftX=1700},5).leftX==1700, "positive cutoff rescaled movement");
    require(withStickDeadzone({.leftX=1200,.leftY=1200},5).leftX==1200, "radial cutoff became an axial square");
    require(withStickDeadzone({.leftX=1000,.leftY=1000},5).neutral(), "diagonal drift escaped the radial cutoff");
    require(withStickDeadzone({.leftX=6553},20).neutral() && withStickDeadzone({.leftX=6554},20).leftX==6554, "maximum cutoff boundary changed");
    for (int percent=-20;percent<=20;++percent) {
        const DeckControllerState edge{.buttons=A_FLAG|UP_FLAG,.leftTrigger=127,.rightTrigger=255,.leftX=-32768,.leftY=32767,.rightX=32767,.rightY=-32767};
        require(withStickDeadzone(edge,percent)==edge, "deadzone clipped full travel or changed buttons/triggers");
        require(withStickDeadzone({},percent).neutral(), "negative deadzone generated movement at rest");
        if (percent<=0) {
            require(withStickDeadzone({.leftX=327},percent).neutral(), "anti-deadzone amplified center noise");
            const auto near=withStickDeadzone({.leftX=328},percent);
            require(near.leftX>=328 && near.leftY==0, "anti-deadzone lost the first nonzero movement");
        }
    }
    const auto boosted=withStickDeadzone({.leftX=3000,.leftY=4000,.rightX=-3000,.rightY=-4000},-20);
    require(boosted.leftX>3000 && boosted.leftY>4000 && std::abs(boosted.leftX*4-boosted.leftY*3)<=4
        && boosted.rightX==-boosted.leftX && boosted.rightY==-boosted.leftY, "anti-deadzone changed direction or stick symmetry");
    require(withStickDeadzone({.leftX=1000},100).neutral(), "invalid threshold did not fall back to 5 percent");
    for (int percent : {-20,0,5,20}) {
        DeckControllerRouter router;
        const auto held=withStickDeadzone({.leftX=15000},percent);
        router.update(held,true,0); router.capture(true,1);
        require(router.awaitingNeutral() && router.update(held,true,2).packets.empty(), "held stick bypassed the neutral gate");
        router.update(withStickDeadzone({.leftX=100},percent),true,3);
        require(!router.awaitingNeutral(), "center drift prevented rearming");
        router.update(held,true,4);
        auto released=router.capture(false,5);
        require(released.discardPendingInput && released.packets.back().state.neutral(), "pause failed to release a filtered stick");
        router.capture(true,6); require(router.awaitingNeutral(), "held stick replayed on focus return");
        released=router.update({},false,7);
        require(!released.packets.back().connected && released.packets.back().state.neutral(), "unplug lost its neutral release");
    }
}

void testNeutralAndRelease() {
    DeckControllerRouter router;
    router.update({.buttons = A_FLAG}, true, 0);
    auto route = router.capture(true, 1);
    require(router.awaitingNeutral() && route.discardPendingInput && route.packets.size() == 1 && route.packets.front() == DeckControllerPacket{{}, true},
        "launch removed the host controller or leaked a held button");
    require(router.update({.buttons = A_FLAG}, true, 2).packets.empty(), "held initialization was forwarded");
    route = router.update({}, true, 3);
    require(!router.awaitingNeutral() && route.packets.empty(), "neutral did not arm the already connected controller");
    route = router.update({.buttons = A_FLAG | B_FLAG, .leftTrigger = 255}, true, 4);
    require(route.packets.size() == 1 && route.packets.front().state.buttons == (A_FLAG | B_FLAG), "gameplay A/B were consumed as UI actions");
    route = router.capture(false, 5);
    require(route.discardPendingInput && route.packets.size() == 1 && route.packets.front() == DeckControllerPacket{{}, true}, "focus loss did not release controls while preserving the host device");
    require(router.update({.buttons = X_FLAG}, true, 6).packets.empty(), "background input forwarded");
    router.capture(true, 7);
    require(router.awaitingNeutral(), "held focus-return input was armed");
    router.update({}, true, 8);
    router.update({.buttons = Y_FLAG}, true, 9);
    route = router.update({}, false, 10);
    require(route.packets.size() == 1 && !route.packets.front().connected, "unplug did not release/remove controller");
    route = router.update({.buttons = A_FLAG}, true, 11);
    require(route.discardPendingInput && route.packets.size() == 1 && route.packets.front() == DeckControllerPacket{{}, true}, "replug replayed a held button");
    router.update({}, true, 12);
    require(!router.awaitingNeutral(), "replug failed to re-arm at neutral");
}

void testPausedDeviceLifetime() {
    DeckControllerRouter router;
    router.update({}, true, 0);
    auto route = router.capture(true, 1);
    require(route.packets.size() == 1 && route.packets.front() == DeckControllerPacket{{}, true},
        "neutral startup briefly unplugged the host controller");
    route = router.capture(false, 2);
    require(route.discardPendingInput && route.packets.size() == 1 && route.packets.front() == DeckControllerPacket{{}, true},
        "pausing with a queued neutral packet omitted the replacement release");
    route = router.update({}, false, 3);
    require(route.discardPendingInput && route.packets.size() == 1 && !route.packets.front().connected,
        "physical unplug while overlay was open did not remove the device");
    route = router.update({.buttons = A_FLAG}, true, 4);
    require(route.packets.size() == 1 && route.packets.front() == DeckControllerPacket{{}, true},
        "reconnect while paused leaked input or left the host device absent");
    route = router.capture(true, 5);
    require(router.awaitingNeutral() && route.discardPendingInput && route.packets.size() == 1 && route.packets.front() == DeckControllerPacket{{}, true},
        "resuming with a held button changed host device identity");
}

void testOverlayChordAndSingleButtons() {
    for (const unsigned first : {BACK_FLAG, PLAY_FLAG}) {
        const unsigned second = first == BACK_FLAG ? PLAY_FLAG : BACK_FLAG;
        DeckControllerRouter router;
        router.update({}, true, 0);
        router.capture(true, 0);
        require(router.update({.buttons = first}, true, 10).packets.empty(), "shortcut's first button leaked");
        const auto chord = router.update({.buttons = first | second}, true, 159);
        require(chord.openControls && chord.discardPendingInput && chord.packets.size() == 1 && chord.packets.front() == DeckControllerPacket{{}, true},
            "simultaneous View/Menu did not release controls while preserving the host device");
        router.capture(true, 160);
        require(router.awaitingNeutral(), "held shortcut leaked on resume");
        router.update({}, true, 161);
        require(router.update({.buttons = first}, true, 170).packets.empty(), "single menu press was not deferred");
        const auto tap = router.update({}, true, 180);
        require(tap.packets.size() == 2 && tap.packets[0].state.buttons == first && tap.packets[1].state.neutral(),
            "quick menu-button tap lost an edge");
        router.update({.buttons = first}, true, 200);
        require(router.tick(349).packets.empty(), "menu button flushed too early");
        const auto held = router.tick(350);
        require(held.packets.size() == 1 && held.packets[0].state.buttons == first, "held single menu button was swallowed");
        const auto late = router.update({.buttons = first | second}, true, 351);
        require(!late.openControls && late.packets[0].state.buttons == (first | second), "late second menu button opened overlay after leakage");
        router.update({}, true, 360);
        router.update({.buttons = first}, true, 400);
        const auto unplug = router.update({}, false, 600);
        require(unplug.packets.size() == 1 && !unplug.packets[0].connected, "unplug flushed a pending shortcut press");
    }
}
}

int main() {
    testMappingAndDecoder();
    testFaceButtonLayouts();
    testStickDeadzone();
    testNeutralAndRelease();
    testPausedDeviceLifetime();
    testOverlayChordAndSingleButtons();
    std::cout << "Controller mapping, neutral gate, focus/unplug release and overlay chord checks passed\n";
}
