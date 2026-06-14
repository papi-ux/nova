#include "deck_gamepad.h"

namespace nova::deck {

DeckGamepadAction decodeGamepadAction(const DeckGamepadEvent& event) {
    if ((event.type & kDeckGamepadInitEvent) != 0) {
        return DeckGamepadAction::None;
    }

    const auto eventType = static_cast<unsigned char>(event.type & ~kDeckGamepadInitEvent);
    if (eventType != kDeckGamepadButtonEvent) {
        return DeckGamepadAction::None;
    }

    if (event.number == kDeckGamepadPrimaryButton && event.value == 1) {
        return DeckGamepadAction::PrimaryPressed;
    }

    return DeckGamepadAction::None;
}

} // namespace nova::deck
