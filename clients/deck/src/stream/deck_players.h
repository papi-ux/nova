#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace nova::deck::stream {

struct DeckPlayerDevice {
    std::string id;
    std::string name;
    bool builtIn = false;
    int player = -1;
    bool readyToJoin = false;
};

// Device discovery is independent of player assignment. Waiting controllers are
// visible before input; an explicit Reassign releases the handheld reservation.
class DeckPlayers {
public:
    static constexpr int maximumPlayers = 16;
    explicit DeckPlayers(bool reserveBuiltIn = false) : builtInReservation_(reserveBuiltIn) {}
    bool connect(std::string id, std::string name, bool builtIn);
    bool disconnect(const std::string& id);
    bool input(const std::string& id, bool neutral, bool buttonPressed);
    void reassign();
    int player(const std::string& id) const;
    std::uint16_t activeMask() const;
    const std::vector<DeckPlayerDevice>& devices() const { return devices_; }
private:
    int availablePlayer() const;
    bool builtInReservation_;
    bool reassigned_ = false;
    std::vector<DeckPlayerDevice> devices_;
};

} // namespace nova::deck::stream
