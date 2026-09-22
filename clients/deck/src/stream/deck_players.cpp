#include "stream/deck_players.h"
#include <algorithm>
#include <utility>

namespace nova::deck::stream {

bool DeckPlayers::connect(std::string id, std::string name, bool builtIn) {
    if (id.empty() || std::any_of(devices_.begin(), devices_.end(), [&](const auto& d) { return d.id == id; })) return false;
    DeckPlayerDevice device{std::move(id), std::move(name), builtIn};
    if (builtIn && !reassigned_) {
        // Do not steal a number already held by a controller discovered before
        // this device; Steam Deck reserves it at startup via its hardware probe.
        builtInReservation_ = true;
        if (!(activeMask() & 1u)) device.player = 0;
    }
    devices_.push_back(std::move(device));
    return true;
}

bool DeckPlayers::disconnect(const std::string& id) {
    return std::erase_if(devices_, [&](const auto& device) { return device.id == id; }) != 0;
}

int DeckPlayers::availablePlayer() const {
    const auto held = activeMask() | (builtInReservation_ ? 1u : 0u);
    for (int player = 0; player < maximumPlayers; ++player)
        if (!(held & (1u << player))) return player;
    return -1;
}

bool DeckPlayers::input(const std::string& id, bool neutral, bool buttonPressed) {
    const auto found = std::find_if(devices_.begin(), devices_.end(), [&](const auto& device) { return device.id == id; });
    if (found == devices_.end() || found->player >= 0) return false;
    if (neutral) found->readyToJoin = true;
    if (!found->readyToJoin || !buttonPressed) return false;
    const auto player = availablePlayer();
    if (player < 0) return false;
    found->player = player;
    found->readyToJoin = false;
    return true;
}

void DeckPlayers::reassign() {
    builtInReservation_ = false;
    reassigned_ = true;
    for (auto& device : devices_) {
        device.player = -1;
        device.readyToJoin = false;
    }
}

int DeckPlayers::player(const std::string& id) const {
    const auto found = std::find_if(devices_.begin(), devices_.end(), [&](const auto& device) { return device.id == id; });
    return found == devices_.end() ? -1 : found->player;
}

std::uint16_t DeckPlayers::activeMask() const {
    std::uint16_t mask = 0;
    for (const auto& device : devices_)
        if (device.player >= 0) mask |= std::uint16_t(1u << device.player);
    return mask;
}

} // namespace nova::deck::stream
