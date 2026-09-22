#include "stream/deck_players.h"
#include "runtime/deck_input_device_policy.h"
#include <cstdlib>
#include <iostream>

using nova::deck::stream::DeckPlayers;
void require(bool ok, const char* message) {
    if (!ok) { std::cerr << message << '\n'; std::exit(1); }
}
int main() {
    using nova::deck::runtime::classifyDeckInputDevice;
    const auto virtualDeck = classifyDeckInputDevice(0x28de, 0x11ff, "Microsoft X-Box 360 pad 0", true);
    require(virtualDeck.steamVirtual && virtualDeck.builtIn, "Steam Input slot zero was not recognized on Deck");
    require(!classifyDeckInputDevice(0x045e, 0x028e, "Microsoft X-Box 360 pad 0", true).steamVirtual,
        "ordinary Xbox pad was mistaken for Steam Input");
    require(!classifyDeckInputDevice(0x28de, 0x11ff, "Microsoft X-Box 360 pad 1", true).builtIn,
        "external Steam Input slot became built-in");
    require(!classifyDeckInputDevice(0x28de, 0x11ff, "Microsoft X-Box 360 pad 0", false).builtIn,
        "desktop Steam Input reserved the handheld slot");
    require(classifyDeckInputDevice(0x28de, 0x1205, "Valve Software Steam Controller", true).builtIn,
        "OLED hardware identity was not recognized");
    DeckPlayers players(true);
    require(players.connect("external", "Wireless controller", false), "external discovery failed");
    require(players.devices().size() == 1 && players.player("external") == -1, "waiting controller missing or assigned before a press");
    require(!players.input("external", false, true), "held button joined before neutral");
    players.input("external", true, false);
    require(players.input("external", false, true) && players.player("external") == 1, "external stole handheld P1 reservation");
    players.connect("deck", "Steam Deck", true);
    require(players.player("deck") == 0 && players.activeMask() == 3, "built-in controls merged with P2");
    require(!players.connect("deck", "Duplicate", true), "same device became two controllers");
    players.reassign();
    require(players.activeMask() == 0, "reassign retained old player slots");
    require(!players.input("deck", false, true), "button that opened Reassign joined immediately");
    players.input("external", true, false);
    players.input("external", false, true);
    players.input("deck", true, false);
    players.input("deck", false, true);
    require(players.player("external") == 0 && players.player("deck") == 1, "explicit press order did not release built-in reservation");
    players.disconnect("external");
    require(players.player("deck") == 1 && players.activeMask() == 2, "unplug renumbered another player");
    players.connect("external", "Wireless controller", false);
    require(players.player("external") == -1, "reconnected device inherited an old player");
    for (int i = 0; i < 17; ++i) {
        const auto id = "pad-" + std::to_string(i);
        players.connect(id, id, false);
        players.input(id, true, false);
        players.input(id, false, true);
    }
    require(players.activeMask() == 0xffff && players.player("pad-16") == -1, "slot capacity overflowed the protocol mask");
    require(!players.input("unknown", true, true) && !players.disconnect("unknown"), "unknown controller changed players");
    std::cout << "Player discovery, P1 reservation, press order, neutral gate, hotplug and slot capacity passed\n";
}
