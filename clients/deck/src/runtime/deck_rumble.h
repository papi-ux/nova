#pragma once

#include <cstdint>
#include <functional>
#include <memory>

namespace nova::deck::runtime {
struct DeckRumble {
    std::uint16_t low = 0, high = 0;
    bool operator==(const DeckRumble&) const = default;
    explicit operator bool() const { return low || high; }
};

class DeckRumbleDevice {
public:
    virtual ~DeckRumbleDevice() = default;
    // Zero stops. Nonzero refreshes a short device-side lease.
    // Recheck after a potentially blocking upload, before starting playback.
    virtual bool play(DeckRumble value, const std::function<bool()>& current = [] { return true; }) = 0;
};

std::unique_ptr<DeckRumbleDevice> openDeckRumbleDevice(int joystickFd);

// The GUI supplies the exact joystick it reads. Device work runs on one worker;
// callbacks/GUI only replace a bounded latest-value mailbox. Device changes
// clear old commands. A failed/unsupported output never falls back to another.
class DeckRumbleController final {
public:
    using Factory = std::function<std::unique_ptr<DeckRumbleDevice>(int)>;
    explicit DeckRumbleController(Factory factory = openDeckRumbleDevice);
    ~DeckRumbleController();
    void setDevice(int joystickFd); // retains a duplicate; -1 disconnects
    void submit(DeckRumble value);
    DeckRumbleController(const DeckRumbleController&) = delete;
    DeckRumbleController& operator=(const DeckRumbleController&) = delete;
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
}
