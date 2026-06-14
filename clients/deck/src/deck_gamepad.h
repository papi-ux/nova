#pragma once

#include <cstdint>

namespace nova::deck {

constexpr unsigned char kDeckGamepadButtonEvent = 0x01;
constexpr unsigned char kDeckGamepadAxisEvent = 0x02;
constexpr unsigned char kDeckGamepadInitEvent = 0x80;
constexpr unsigned char kDeckGamepadPrimaryButton = 0;

struct DeckGamepadEvent {
    std::uint32_t timeMs = 0;
    short value = 0;
    unsigned char type = 0;
    unsigned char number = 0;
};

enum class DeckGamepadAction {
    None,
    PrimaryPressed,
};

DeckGamepadAction decodeGamepadAction(const DeckGamepadEvent& event);

} // namespace nova::deck
