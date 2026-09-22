#pragma once

#include <cstdint>
#include <string_view>

namespace nova::deck::runtime {

struct DeckInputDeviceKind {
    bool steamVirtual = false;
    bool builtIn = false;
};

// Valve's virtual controller uses 28de:11ff, even when its displayed name is
// "Microsoft X-Box 360 pad N". A name alone cannot identify a physical device.
// Reference: SDL test/testevdev.c and linux/SDL_sysjoystick.c.
inline DeckInputDeviceKind classifyDeckInputDevice(std::uint16_t vendor, std::uint16_t product,
    std::string_view name, bool deck) {
    const bool steamVirtual = vendor == 0x28de && product == 0x11ff;
    const bool firstSteamSlot = name == "Microsoft X-Box 360 pad 0" || name == "Steam Virtual Gamepad";
    return {steamVirtual, (vendor == 0x28de && product == 0x1205) || (deck && steamVirtual && firstSteamSlot)};
}

} // namespace nova::deck::runtime
